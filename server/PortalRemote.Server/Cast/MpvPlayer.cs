using System.Diagnostics;
using System.IO.Pipes;
using System.Text.Json;

namespace PortalRemote.Cast;

/// <summary>
/// Phase 4b of <c>docs/phase4-casting.md</c>: a real player on the PC, driven over
/// mpv's JSON IPC pipe. The receiver page (4g) reaches every device with a browser but
/// cannot play HLS or DASH and dies when the tab closes; mpv plays whatever ffmpeg
/// plays and outlives nothing but itself.
///
/// It reports through <see cref="CastHub.OnStatus"/> in the receiver page's own status
/// shape, so the phone's scrub bar, toggle and "nothing is attached" handling all work
/// against this with no new wire message and no new parsing.
/// </summary>
public sealed class MpvPlayer
{
    // ponytail: same reasoning as CastHub.Instance — one desktop, one player window.
    public static readonly MpvPlayer Instance = new();

    /// <summary>Fixed, because adopting an existing one is a feature (see <see cref="Start"/>).</summary>
    private const string PipeName = "portalremote-mpv";

    /// <summary>
    /// Guards the observed state below. <b>Never held across pipe I/O</b> — a write to
    /// a named pipe blocks until the other end reads, and the read loop needs this lock
    /// to record what it read. Holding it over a write deadlocks the two against each
    /// other, and takes the phone's control socket down with them.
    /// </summary>
    private readonly object gate = new();

    /// <summary>Serialises writes only. One command per line, one writer at a time.</summary>
    private readonly object writeGate = new();

    private NamedPipeClientStream? pipe;

    /// <summary>Latest values from <c>observe_property</c>. Guarded by <see cref="gate"/>.</summary>
    private double position, duration, volume = 1;
    private bool paused = true, ended, muted;

    /// <summary>
    /// When the last status went out. <c>time-pos</c> fires far faster than anything
    /// needs to be pushed to a phone, so position-only changes are held to the same
    /// 1 Hz the receiver page ticks at.
    /// </summary>
    private DateTime lastPositionPush = DateTime.MinValue;

    /// <summary><c>MpvPath</c> from the config, if the user set one.</summary>
    public string? ConfiguredPath { get; set; }

    /// <summary>An mpv is up and taking commands.</summary>
    public bool Running
    {
        get { lock (gate) return pipe is { IsConnected: true }; }
    }

    /// <summary>
    /// Where mpv is, or null. Detect, do not bundle (§6): next to our own exe first so
    /// a "portable" folder wins, then whatever is on PATH.
    /// </summary>
    public string? ExePath
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(ConfiguredPath) && File.Exists(ConfiguredPath))
                return ConfiguredPath;

            var beside = Path.Combine(AppContext.BaseDirectory, "mpv.exe");
            if (File.Exists(beside)) return beside;

            return (Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
                .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
                .Select(dir => Path.Combine(dir.Trim('"'), "mpv.exe"))
                .FirstOrDefault(File.Exists);
        }
    }

    /// <summary>Worth routing a cast to. An already-running mpv counts even if we
    /// can't find the binary that started it.</summary>
    public bool Available => Running || ExePath is not null;

    /// <summary>Play <paramref name="url"/>, starting mpv if it isn't up yet.</summary>
    public void Load(string url, string? title)
    {
        Start();
        lock (gate)
        {
            // Belongs to the previous file; leaving it would report the old position
            // against the new one until mpv's first tick.
            position = duration = 0;
            ended = false;
            paused = false;
        }
        Send("loadfile", url, "replace");
        // Set after the load rather than as a per-file option: the option syntax for
        // loadfile changed across mpv versions, this property has not.
        if (!string.IsNullOrWhiteSpace(title)) Send("set_property", "force-media-title", title);
        Publish();
    }

    /// <summary>One of <c>InputActions.PlayerActions</c>, applied to mpv.</summary>
    public void Command(string action, double? to, double? by, double? level, bool? mute)
    {
        switch (action)
        {
            case "play": Send("set_property", "pause", false); break;
            case "pause": Send("set_property", "pause", true); break;
            case "toggle": Send("cycle", "pause"); break;
            case "stop": Quit(); break;

            case "seek":
                if (to is not null) Send("seek", to.Value, "absolute");
                else if (by is not null) Send("seek", by.Value, "relative");
                break;

            case "volume":
                // mpv's scale is 0-100, the receiver page's (and the phone's) is 0-1.
                if (level is not null) Send("set_property", "volume", Math.Clamp(level.Value, 0, 1) * 100);
                if (mute is not null) Send("set_property", "mute", mute.Value);
                break;
        }
    }

    /// <summary>Close the player. Called on <c>stop</c> and on server shutdown — an
    /// mpv we launched and can no longer reach is an orphan window.</summary>
    public void Quit()
    {
        if (Running) Send("quit");
        // Don't wait for it: the read loop sees the pipe break and cleans up.
    }

    /// <summary>
    /// Connect to a running mpv, or launch one and connect to that.
    /// <b>Adopt, don't duplicate</b> — if the pipe already answers, an mpv from an
    /// earlier run of this server is still up, and a second one would fight it for
    /// the screen.
    /// </summary>
    private void Start()
    {
        if (Running) return;

        if (!TryConnect(TimeSpan.Zero))
        {
            var exe = ExePath ?? throw new Input.UnknownMessageException("mpv is not installed");
            Process.Start(new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                ArgumentList =
                {
                    $"--input-ipc-server=\\\\.\\pipe\\{PipeName}",
                    // Idle so the window is up and controllable before the first file,
                    // and stays up between them.
                    "--idle=yes",
                    "--force-window=yes",
                    "--fullscreen",
                    // Hold the last frame at the end instead of closing: "ended" is a
                    // state the phone draws, not a reason to tear the window down.
                    "--keep-open=yes",
                    "--title=Portal Remote",
                }
            })?.Dispose();

            // mpv creates the pipe as it starts; a cold start is a second or two.
            if (!TryConnect(TimeSpan.FromSeconds(10)))
                throw new Input.UnknownMessageException("mpv started but never answered its control pipe");
        }
    }

    private bool TryConnect(TimeSpan patience)
    {
        var deadline = DateTime.UtcNow + patience;
        do
        {
            var client = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            try
            {
                client.Connect(200);
            }
            catch (Exception ex) when (ex is TimeoutException or IOException)
            {
                client.Dispose();
                continue;
            }

            lock (gate) pipe = client;

            // One id per property; we only ever read the name back, so they exist to
            // satisfy the protocol rather than to be matched on.
            var properties = new[] { "time-pos", "duration", "pause", "eof-reached", "mute", "volume" };
            for (var i = 0; i < properties.Length; i++) Send("observe_property", i + 1, properties[i]);

            _ = Task.Run(() => ReadLoop(client));
            return true;
        }
        while (DateTime.UtcNow < deadline);

        return false;
    }

    /// <summary>
    /// One JSON command per line, which is mpv's entire input protocol. Written to the
    /// pipe directly rather than through a <see cref="StreamWriter"/>: an auto-flushing
    /// writer calls <c>FlushFileBuffers</c>, which on a named pipe waits for the peer to
    /// read — a blocking call inside what should be a fire-and-forget send.
    /// </summary>
    private void Send(params object[] command)
    {
        var stream = pipe;
        if (stream is null) return;

        var bytes = System.Text.Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { command }) + "\n");
        lock (writeGate)
        {
            try
            {
                stream.Write(bytes);
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException)
            {
                // The window was closed under us. The read loop is about to notice and
                // clear the state; a command into a dead pipe is not worth a 500.
            }
        }
    }

    private async Task ReadLoop(NamedPipeClientStream client)
    {
        try
        {
            using var reader = new StreamReader(client);
            while (await reader.ReadLineAsync() is { } line)
                Handle(line);
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            // Pipe broke — same end as a clean close.
        }
        finally
        {
            lock (gate)
            {
                if (ReferenceEquals(pipe, client)) pipe = null;
            }
            client.Dispose();
            // The phone's transport is drawn from this: mpv going away has to reach it,
            // or the controls stay live against a window that isn't there.
            CastHub.Instance.ClearStatus();
        }
    }

    private void Handle(string line)
    {
        JsonElement root;
        try
        {
            root = JsonSerializer.Deserialize<JsonElement>(line);
        }
        catch (JsonException)
        {
            return;
        }

        if (root.ValueKind != JsonValueKind.Object) return;
        // Everything else on this pipe is a reply to one of our own commands.
        if (!root.TryGetProperty("event", out var ev) || ev.GetString() != "property-change") return;
        if (!root.TryGetProperty("name", out var nameElement)) return;

        var name = nameElement.GetString();
        var data = root.TryGetProperty("data", out var d) ? d : default;
        var positionOnly = false;

        lock (gate)
        {
            switch (name)
            {
                // Null data is mpv saying "unavailable" — no file loaded, or a stream
                // with no known length. Both are zero here, not a stale previous value.
                case "time-pos":
                    position = Number(data);
                    positionOnly = true;
                    break;
                case "duration": duration = Number(data); break;
                case "pause": paused = Flag(data, true); break;
                case "eof-reached": ended = Flag(data, false); break;
                case "mute": muted = Flag(data, false); break;
                case "volume": volume = Number(data) / 100; break;
                default: return;
            }

            if (positionOnly)
            {
                if (DateTime.UtcNow - lastPositionPush < TimeSpan.FromSeconds(1)) return;
                lastPositionPush = DateTime.UtcNow;
            }
        }

        Publish();
    }

    private static double Number(JsonElement e) => e.ValueKind == JsonValueKind.Number ? e.GetDouble() : 0;

    private static bool Flag(JsonElement e, bool fallback) => e.ValueKind switch
    {
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => fallback,
    };

    /// <summary>
    /// Report in the receiver page's shape. <c>waitingForGesture</c> is always false —
    /// that is a browser autoplay policy, and mpv has no such thing.
    /// </summary>
    private void Publish()
    {
        object status;
        lock (gate)
        {
            status = new
            {
                paused,
                ended,
                waitingForGesture = false,
                position,
                duration,
                muted,
                volume,
                error = 0,
            };
        }

        CastHub.Instance.OnStatus(JsonSerializer.Serialize(status));
    }
}
