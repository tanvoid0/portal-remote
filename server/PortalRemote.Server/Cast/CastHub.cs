using System.Net.WebSockets;
using System.Text.Json;

namespace PortalRemote.Cast;

/// <summary>
/// The set of receiver pages currently attached, and whatever they were last told to
/// play. Phase 4g of <c>docs/phase4-casting.md</c>: a browser is a media player that
/// every device already has, so a page plus a socket is a receiver — no codec work,
/// no install, and the same page works on a TV, a console or a laptop later.
/// </summary>
public sealed class CastHub
{
    // ponytail: one process, one desktop, one user — a static instance beats
    // threading a reference through InputActions.Dispatch's static call chain. If a
    // second hub is ever needed (per-user? per-monitor?), make it an injected
    // singleton then, not now.
    public static readonly CastHub Instance = new();

    private readonly List<WebSocket> receivers = [];
    private readonly object gate = new();

    /// <summary>
    /// Raised whenever <see cref="Snapshot"/> would answer differently — a status
    /// arrived, something new was cast, or the last receiver went away. Phase 4b of
    /// <c>docs/phase4-casting.md</c>: the page already reports position and duration
    /// at 1 Hz, so a scrub bar on the phone only needs that to be *pushed* rather than
    /// polled. Wired to the share hub's broadcast in <c>Program.cs</c>, exactly like
    /// <c>NowPlaying.Changed</c>.
    /// </summary>
    public event Action<object>? Changed;

    /// <summary>Last thing cast, replayed to a receiver that attaches later.</summary>
    private object? nowPlaying;

    /// <summary>Last status the receiver reported, as raw JSON. Null until one arrives.</summary>
    private string? lastStatus;

    /// <summary>
    /// Whether anything is actually listening. Checks socket state rather than list
    /// membership and drops what has died: a receiver that closed without its read
    /// loop noticing yet would otherwise make a cast report success and then play
    /// nowhere at all — worse than falling back to the shell.
    /// </summary>
    public bool HasReceivers
    {
        get
        {
            lock (gate)
            {
                receivers.RemoveAll(s => s.State != WebSocketState.Open);
                return receivers.Count > 0;
            }
        }
    }

    public void Add(WebSocket socket)
    {
        lock (gate) receivers.Add(socket);
        Publish();
    }

    public void Remove(WebSocket socket)
    {
        lock (gate) receivers.Remove(socket);
        // The phone's transport buttons are drawn from `receiver`, so a page closing
        // has to reach it — otherwise the controls stay live against nothing until
        // the next command comes back "no cast receiver is attached".
        Publish();
    }

    /// <summary>
    /// Everything the phone needs to draw the transport: whether there is anything to
    /// drive, and what it last said it was doing. One definition, used by both the
    /// <c>cast_status</c> request and the push.
    /// </summary>
    public object Snapshot()
    {
        bool attached;
        string? status;
        lock (gate)
        {
            receivers.RemoveAll(s => s.State != WebSocketState.Open);
            // A running mpv, a Roku or a TV across the room is a receiver as far as the
            // phone is concerned: each holds a position, takes transport commands, and
            // can go away. The phone draws its controls from this one boolean and does
            // not need to learn the difference between a browser tab, a player window
            // and something speaking DLNA.
            attached = receivers.Count > 0 || CastRouter.LiveElsewhere;
            status = lastStatus;
        }

        return new
        {
            t = "cast_status",
            receiver = attached,
            // Deserialize rather than JsonDocument.Parse: this element outlives the
            // call, and a JsonDocument's buffer is pooled — reading its RootElement
            // after disposal is undefined, and never disposing it leaks the rental.
            status = status is not null ? JsonSerializer.Deserialize<JsonElement>(status) : (JsonElement?)null
        };
    }

    private void Publish() => Changed?.Invoke(Snapshot());

    /// <summary>What a freshly-attached receiver should resume, if anything.</summary>
    public object? NowPlaying
    {
        get { lock (gate) return nowPlaying; }
    }

    /// <summary>
    /// What the receiver last reported it was doing. The server cannot see inside a
    /// page it merely serves, so without this "did it actually play?" is unanswerable
    /// — from the phone, and from anyone debugging.
    /// </summary>
    public string? LastStatus
    {
        get { lock (gate) return lastStatus; }
    }

    public void OnStatus(string json)
    {
        lock (gate) lastStatus = json;
        Publish();
    }

    /// <summary>
    /// Whoever was reporting has gone — the mpv window was closed, or its pipe broke.
    /// Dropping the last status matters as much as the notification: a receiver page
    /// attaching afterwards would otherwise inherit mpv's playhead until its own first
    /// tick.
    /// </summary>
    public void ClearStatus()
    {
        lock (gate) lastStatus = null;
        Publish();
    }

    public void Load(string url, string? title)
    {
        var message = new { t = "load", url, title };
        lock (gate)
        {
            nowPlaying = message;
            // Belongs to the previous item; keeping it would report the old film's
            // position against the new one until the first status arrives.
            lastStatus = null;
        }
        Broadcast(message);
        // Clears the previous item's position on the phone straight away, rather than
        // leaving the old film's scrub bar up until the receiver's first tick.
        Publish();
    }

    /// <summary>Forward a transport command (play/pause/seek/volume) to every receiver.</summary>
    public void Command(object message) => Broadcast(message);

    private void Broadcast(object message)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message);
        WebSocket[] targets;
        lock (gate) targets = [.. receivers];

        foreach (var socket in targets)
        {
            if (socket.State != WebSocketState.Open)
            {
                Remove(socket);
                continue;
            }
            // Fire-and-forget: a receiver that has wandered off shouldn't block the
            // phone's control socket waiting for a send that will never drain.
            _ = socket
                .SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, CancellationToken.None)
                .ContinueWith(_ => Remove(socket), TaskContinuationOptions.OnlyOnFaulted);
        }
    }
}
