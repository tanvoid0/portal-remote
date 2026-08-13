using System.Globalization;
using System.Text.Json;
using System.Xml.Linq;
using PortalRemote.Input;

namespace PortalRemote.Cast;

/// <summary>
/// A Roku stick or Roku TV, over ECP — step 4i of <c>docs/phase4-casting.md</c>. ECP is
/// plain unauthenticated HTTP on port 8060 and is publicly documented, which is why the
/// doc puts it first among the third-party targets: it is the cheapest one to write and
/// it pays for the SSDP that the DLNA sender then reuses.
///
/// Playback goes through channel <c>2213</c>, the built-in Roku Media Player, because
/// ECP has no "play this URL" of its own — <c>/launch/&lt;channel&gt;?u=…</c> is how every
/// caster does it.
///
/// <b>A Roku fetches the URL naked.</b> It cannot send a <c>Referer</c> or a
/// <c>Cookie</c>, so a link lifted from a site that checks either will 403 — §4 of the
/// doc calls this out and the phone's own <c>/f/</c> and <c>/p/</c> server is the answer.
/// Files picked on the phone (4d) already work, because those are served without a
/// session in the first place.
/// </summary>
internal sealed class RokuPlayer(string host, string name) : IRemotePlayer
{
    /// <summary>The built-in Roku Media Player channel.</summary>
    private const string MediaPlayerChannel = "2213";

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(4) };

    private readonly object gate = new();
    private bool paused = true, playing;

    public string Host { get; } = host;

    public string Id => $"roku:{Host}";
    public string Name => name;
    public string Kind => "roku";

    /// <summary>
    /// No absolute seek and no absolute volume: ECP's whole transport surface is the
    /// physical remote's buttons, so it can skip and it can nudge the volume, but it
    /// cannot be told "go to 41:12" or "be at 60%". Reported honestly so the phone draws
    /// a read-only progress bar rather than a scrubber that ignores the drag.
    /// </summary>
    public Caps Caps => new(Seek: false, Volume: false, Status: true);

    public bool Available => true;

    public bool Live
    {
        get { lock (gate) return playing; }
    }

    public void Load(string url, string? title)
    {
        var query = $"?t=v&u={Uri.EscapeDataString(url)}";
        if (!string.IsNullOrWhiteSpace(title)) query += $"&videoName={Uri.EscapeDataString(title)}";
        if (VideoFormat(url) is { } format) query += $"&videoFormat={format}";

        // Launch is asynchronous on the Roku's side — it answers as soon as it has
        // accepted the intent, not when the video is up.
        if (!Post($"/launch/{MediaPlayerChannel}{query}"))
            throw new UnknownMessageException($"{Name} did not answer on port 8060");

        lock (gate)
        {
            playing = true;
            paused = false;
        }
    }

    public bool Command(string action, double? to, double? by, double? level, bool? muted)
    {
        switch (action)
        {
            // ECP has one Play key and it toggles, so "play" while already playing would
            // pause it. The last poll is what makes the difference expressible.
            case "toggle":
                return Post("/keypress/Play");
            case "play":
                lock (gate) if (!paused) return true;
                return Post("/keypress/Play");
            case "pause":
                lock (gate) if (paused) return true;
                return Post("/keypress/Play");

            case "stop":
                lock (gate) playing = false;
                CastHub.Instance.ClearStatus();
                return Post("/keypress/Home");

            case "seek":
                // ponytail: the skip buttons map onto the remote's Fwd/Rev keys, whose
                // jump size the Roku decides — so ±10s on the phone is "a skip", not ten
                // seconds. Honest alternative is nothing at all; ECP has no absolute
                // seek to upgrade to.
                if (by is null or 0) return false;
                return Post(by > 0 ? "/keypress/Fwd" : "/keypress/Rev");

            case "volume":
                // Only Roku TVs have a volume; sticks pass this to the TV over CEC when
                // that is set up, and drop it otherwise.
                if (muted is not null) return Post("/keypress/VolumeMute");
                return false;

            default:
                return false;
        }
    }

    /// <summary>
    /// Ask the Roku where it is and publish it in the receiver page's shape, which is
    /// what gives a Roku cast the same progress bar and play/pause toggle as everything
    /// else with no phone-side work.
    /// </summary>
    public async Task PollAsync()
    {
        string xml;
        try
        {
            xml = await Http.GetStringAsync($"http://{Host}:8060/query/media-player");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            // Unplugged, asleep or off the network. Treat it like mpv's window closing:
            // the phone must stop drawing transport for something that isn't there.
            lock (gate) playing = false;
            CastHub.Instance.ClearStatus();
            return;
        }

        var status = ParseStatus(xml);
        if (status is null) return;

        lock (gate)
        {
            playing = status.Playing;
            paused = status.Paused;
        }

        if (!status.Playing)
        {
            CastHub.Instance.ClearStatus();
            return;
        }

        CastHub.Instance.OnStatus(JsonSerializer.Serialize(new
        {
            paused = status.Paused,
            ended = false,
            waitingForGesture = false,
            position = status.Position,
            duration = status.Duration,
            muted = false,
            volume = 1.0,
            error = status.Error ? 1 : 0,
        }));
    }

    /// <param name="Playing">There is something loaded — as opposed to the channel
    /// having closed, which is what the Roku reports once you leave it.</param>
    internal sealed record RokuStatus(bool Playing, bool Paused, double Position, double Duration, bool Error);

    /// <summary>
    /// Parse <c>/query/media-player</c>. Returns null for XML we don't recognise rather
    /// than a zeroed status, which would park the phone's bar at 0:00 and claim that is
    /// where the film is.
    /// </summary>
    internal static RokuStatus? ParseStatus(string xml)
    {
        XElement root;
        try
        {
            root = XDocument.Parse(xml).Root ?? throw new System.Xml.XmlException("empty");
        }
        catch (System.Xml.XmlException)
        {
            return null;
        }

        if (root.Name.LocalName != "player") return null;

        // "close" is the channel not being open at all; "play", "pause" and "buffer" are
        // the three that mean something is loaded.
        var state = (string?)root.Attribute("state") ?? "close";
        var playing = state is "play" or "pause" or "buffer" or "startup";

        return new RokuStatus(
            Playing: playing,
            // Buffering is not paused, but it is not advancing either — reporting it as
            // paused stops the phone's interpolation from running ahead of the picture.
            Paused: state is "pause" or "buffer" or "startup",
            Position: Milliseconds(root.Element("position")) / 1000.0,
            Duration: Milliseconds(root.Element("duration")) / 1000.0,
            Error: string.Equals((string?)root.Attribute("error"), "true", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Roku writes these as <c>"93000 ms"</c> — a number, a space, a unit.</summary>
    private static double Milliseconds(XElement? element)
    {
        var text = element?.Value.Trim();
        if (string.IsNullOrEmpty(text)) return 0;
        var number = text.Split(' ')[0];
        return double.TryParse(number, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ? value : 0;
    }

    /// <summary>
    /// The Roku Media Player wants to be told the container up front; guessing wrong is
    /// worse than not guessing, so anything unrecognised is left out and the Roku sniffs
    /// it itself.
    /// </summary>
    internal static string? VideoFormat(string url)
    {
        var path = Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.AbsolutePath : url;
        return Path.GetExtension(path).ToLowerInvariant() switch
        {
            ".mp4" or ".m4v" or ".mov" => "mp4",
            ".m3u8" => "hls",
            ".mkv" => "mkv",
            ".mpd" => "dash",
            _ => null,
        };
    }

    private bool Post(string path)
    {
        try
        {
            using var response = Http.PostAsync($"http://{Host}:8060{path}", content: null).GetAwaiter().GetResult();
            return response.IsSuccessStatusCode;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return false;
        }
    }

    /// <summary>
    /// Turn an SSDP hit into a named target. The name is asked for over ECP rather than
    /// taken from the search reply, which carries none — and a house with two Rokus
    /// needs "Bedroom Roku", not two rows called Roku.
    /// </summary>
    public static async Task<RokuPlayer?> ProbeAsync(SsdpHit hit)
    {
        var host = ParseHost(hit) ?? hit.Address.ToString();
        try
        {
            var xml = await Http.GetStringAsync($"http://{host}:8060/query/device-info");
            return new RokuPlayer(host, FriendlyName(xml) ?? $"Roku ({host})");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Xml.XmlException)
        {
            // Answered the search but not the query — count it anyway, under its address.
            return new RokuPlayer(host, $"Roku ({host})");
        }
    }

    /// <summary>
    /// The user-set name first: that is the one written on the device in the Roku app,
    /// and it is what distinguishes two of them.
    /// </summary>
    internal static string? FriendlyName(string deviceInfoXml)
    {
        XElement? root;
        try
        {
            root = XDocument.Parse(deviceInfoXml).Root;
        }
        catch (System.Xml.XmlException)
        {
            return null;
        }

        foreach (var field in new[] { "user-device-name", "friendly-device-name", "model-name" })
        {
            var value = root?.Element(field)?.Value.Trim();
            if (!string.IsNullOrWhiteSpace(value)) return value;
        }
        return null;
    }

    /// <summary>Host out of a <c>LOCATION</c> of <c>http://192.168.1.5:8060/</c>.</summary>
    private static string? ParseHost(SsdpHit hit) =>
        Uri.TryCreate(hit.Location, UriKind.Absolute, out var uri) && !string.IsNullOrEmpty(uri.Host)
            ? uri.Host
            : null;
}
