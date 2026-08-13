using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using PortalRemote.Input;

namespace PortalRemote.Cast;

/// <summary>
/// A UPnP/DLNA <c>MediaRenderer</c> — the sender half, step 4j of
/// <c>docs/phase4-casting.md</c>, and the mirror image of <see cref="Dlna.DlnaRenderer"/>
/// which makes this PC one. One implementation reaches Xbox, PS4, a lot of
/// Samsung/Sony/Philips TVs, AV receivers and Kodi, which is the best devices-per-line
/// ratio after the receiver page. (Not VLC, despite what the doc's table used to say —
/// VLC browses DLNA *servers* and casts to Chromecast; it is not a renderer.)
///
/// Unlike a Roku this speaks real transport: absolute seek and absolute volume both
/// exist in the standard, so the phone gets a working scrubber.
///
/// <b>It fetches the URL naked</b>, same as a Roku — no <c>Referer</c>, no
/// <c>Cookie</c>. See <see cref="RokuPlayer"/>.
/// </summary>
internal sealed class DlnaPlayer : IRemotePlayer
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(5) };

    private const string AvTransport = "urn:schemas-upnp-org:service:AVTransport:1";
    private const string RenderingControl = "urn:schemas-upnp-org:service:RenderingControl:1";

    /// <summary>The AVTransport endpoint — every transport command goes here.</summary>
    internal Uri Control { get; }

    /// <summary>The RenderingControl endpoint, if this device has one. Null is common:
    /// plenty of renderers have transport and no volume of their own.</summary>
    internal Uri? Rendering { get; }

    private readonly object gate = new();
    private bool paused = true, playing;

    /// <summary>Where the last poll found the playhead — the base a relative seek counts
    /// from, since the standard has no relative seek of its own.</summary>
    private double lastPosition;

    private DlnaPlayer(string id, string name, Uri control, Uri? rendering)
    {
        Id = id;
        Name = name;
        Control = control;
        Rendering = rendering;
    }

    public string Id { get; }
    public string Name { get; }
    public string Kind => "dlna";

    public Caps Caps => new(Seek: true, Volume: Rendering is not null, Status: true);

    public bool Available => true;

    public bool Live
    {
        get { lock (gate) return playing; }
    }

    public void Load(string url, string? title)
    {
        // Order matters and is not negotiable: a renderer that is handed Play before it
        // has a URI plays the previous one, or nothing.
        var metadata = Didl(url, title);
        if (!Invoke(Control, AvTransport, "SetAVTransportURI",
                $"<InstanceID>0</InstanceID><CurrentURI>{Escape(url)}</CurrentURI>" +
                $"<CurrentURIMetaData>{Escape(metadata)}</CurrentURIMetaData>"))
            throw new UnknownMessageException($"{Name} refused the URL");

        Invoke(Control, AvTransport, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");

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
            case "play":
                return Invoke(Control, AvTransport, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");

            case "pause":
                return Invoke(Control, AvTransport, "Pause", "<InstanceID>0</InstanceID>");

            case "toggle":
            {
                bool wasPaused;
                lock (gate) wasPaused = paused;
                return Command(wasPaused ? "play" : "pause", to, by, level, muted);
            }

            case "stop":
                lock (gate) playing = false;
                CastHub.Instance.ClearStatus();
                return Invoke(Control, AvTransport, "Stop", "<InstanceID>0</InstanceID>");

            case "seek":
            {
                // REL_TIME is a position within the track, which is the only one every
                // renderer implements; ABS_TIME is for broadcast sources.
                double? target;
                lock (gate) target = to ?? (by is not null ? lastPosition + by.Value : null);
                if (target is null) return false;
                return Invoke(Control, AvTransport, "Seek",
                    $"<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                    $"<Target>{FormatTime(Math.Max(0, target.Value))}</Target>");
            }

            case "volume":
            {
                if (Rendering is null) return false;
                var done = false;
                if (level is not null)
                    done |= Invoke(Rendering, RenderingControl, "SetVolume",
                        $"<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                        $"<DesiredVolume>{(int)Math.Round(Math.Clamp(level.Value, 0, 1) * 100)}</DesiredVolume>");
                if (muted is not null)
                    done |= Invoke(Rendering, RenderingControl, "SetMute",
                        $"<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                        $"<DesiredMute>{(muted.Value ? 1 : 0)}</DesiredMute>");
                return done;
            }

            default:
                return false;
        }
    }

    /// <summary>
    /// Two calls a second apart would be two round trips; <c>GetPositionInfo</c> alone
    /// carries the times but not whether it is paused, so both are asked and the
    /// transport state is what decides whether the phone's bar ticks.
    /// </summary>
    public async Task PollAsync()
    {
        var position = await InvokeAsync(Control, AvTransport, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        var transport = await InvokeAsync(Control, AvTransport, "GetTransportInfo", "<InstanceID>0</InstanceID>");

        if (position is null || transport is null)
        {
            // Off, asleep, or someone unplugged the TV. Same handling as mpv's window
            // closing — stop drawing transport for something that is not there.
            lock (gate) playing = false;
            CastHub.Instance.ClearStatus();
            return;
        }

        var state = Value(transport, "CurrentTransportState") ?? "STOPPED";
        var isPlaying = state is "PLAYING" or "PAUSED_PLAYBACK" or "TRANSITIONING";
        var isPaused = state != "PLAYING";

        var at = ParseTime(Value(position, "RelTime"));
        var duration = ParseTime(Value(position, "TrackDuration"));

        lock (gate)
        {
            playing = isPlaying;
            paused = isPaused;
            lastPosition = at;
        }

        if (!isPlaying)
        {
            CastHub.Instance.ClearStatus();
            return;
        }

        var volume = Rendering is null
            ? 1.0
            : ParseVolume(await InvokeAsync(Rendering, RenderingControl, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>"));

        CastHub.Instance.OnStatus(JsonSerializer.Serialize(new
        {
            paused = isPaused,
            ended = false,
            waitingForGesture = false,
            position = at,
            duration,
            muted = false,
            volume,
            error = 0,
        }));
    }

    // ---- SOAP ----------------------------------------------------------------

    private bool Invoke(Uri endpoint, string service, string action, string body) =>
        InvokeAsync(endpoint, service, action, body).GetAwaiter().GetResult() is not null;

    /// <summary>The response body, or null if the device refused or never answered.</summary>
    private static async Task<XElement?> InvokeAsync(Uri endpoint, string service, string action, string body)
    {
        var envelope =
            "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            $"<s:Body><u:{action} xmlns:u=\"{service}\">{body}</u:{action}></s:Body></s:Envelope>";

        using var request = new HttpRequestMessage(HttpMethod.Post, endpoint)
        {
            Content = new StringContent(envelope, Encoding.UTF8)
        };
        // text/xml, not application/xml: several renderers reject the latter outright.
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("text/xml") { CharSet = "utf-8" };
        request.Headers.TryAddWithoutValidation("SOAPACTION", $"\"{service}#{action}\"");

        try
        {
            using var response = await Http.SendAsync(request);
            var text = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode) return null;
            return XDocument.Parse(text).Root;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Xml.XmlException)
        {
            return null;
        }
    }

    /// <summary>First element with this local name, anywhere in the response. Namespaces
    /// on the reply vary by vendor and none of them carry information we need.</summary>
    internal static string? Value(XElement root, string name) =>
        root.Descendants().FirstOrDefault(e => e.Name.LocalName == name)?.Value;

    private static double ParseVolume(XElement? response)
    {
        var text = response is null ? null : Value(response, "CurrentVolume");
        return int.TryParse(text, out var value) ? Math.Clamp(value / 100.0, 0, 1) : 1.0;
    }

    // ---- Parsing -------------------------------------------------------------

    /// <summary>
    /// UPnP times are <c>H:MM:SS</c>, sometimes with fractional seconds, and
    /// <c>NOT_IMPLEMENTED</c> or <c>0:00:00</c> for a live stream with no length.
    /// </summary>
    internal static double ParseTime(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return 0;

        var parts = text.Trim().Split(':');
        if (parts.Length is < 2 or > 3) return 0;

        double total = 0;
        foreach (var part in parts)
        {
            if (!double.TryParse(part, NumberStyles.Float, CultureInfo.InvariantCulture, out var value)) return 0;
            total = total * 60 + value;
        }
        return total;
    }

    /// <summary>Back the other way, for <c>Seek</c>.</summary>
    internal static string FormatTime(double seconds)
    {
        var span = TimeSpan.FromSeconds(seconds);
        return $"{(int)span.TotalHours}:{span.Minutes:00}:{span.Seconds:00}";
    }

    /// <summary>
    /// The one-item content directory a renderer expects alongside the URL. Samsung and
    /// several others reject an empty <c>CurrentURIMetaData</c> outright, so this is not
    /// optional politeness.
    /// </summary>
    internal static string Didl(string url, string? title)
    {
        var name = string.IsNullOrWhiteSpace(title) ? "Portal Remote" : title;
        return
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            $"<dc:title>{Escape(name)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            // The wildcards say "work it out": we do not know the codec, and claiming a
            // wrong one is how a renderer decides up front that it cannot play something
            // it actually can.
            $"<res protocolInfo=\"http-get:*:*:*\">{Escape(url)}</res>" +
            "</item></DIDL-Lite>";
    }

    private static string Escape(string value) =>
        value.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace("\"", "&quot;");

    /// <summary>
    /// Turn an SSDP hit into a target by fetching and reading its device description.
    /// Null when it has no <c>AVTransport</c> — plenty of UPnP devices answer a
    /// <c>MediaRenderer</c> search and then turn out to be a speaker with no transport,
    /// and listing one is offering a cast that cannot happen.
    /// </summary>
    public static async Task<DlnaPlayer?> ProbeAsync(SsdpHit hit)
    {
        string xml;
        try
        {
            xml = await Http.GetStringAsync(hit.Location);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or UriFormatException)
        {
            return null;
        }

        return FromDescription(xml, hit.Location, hit.Usn);
    }

    /// <summary>
    /// Pull the name and the two control URLs out of a UPnP device description. Matched
    /// on local names because the description's namespace is versioned and vendors are
    /// not consistent about which one they declare.
    /// </summary>
    internal static DlnaPlayer? FromDescription(string xml, string location, string usn)
    {
        XElement? root;
        try
        {
            root = XDocument.Parse(xml).Root;
        }
        catch (System.Xml.XmlException)
        {
            return null;
        }
        if (root is null) return null;

        if (!Uri.TryCreate(location, UriKind.Absolute, out var baseUri)) return null;
        // URLBase is legal and some devices serve their description from a different
        // port than the one their services live on.
        if (Value(root, "URLBase") is { Length: > 0 } declared &&
            Uri.TryCreate(declared, UriKind.Absolute, out var declaredBase))
            baseUri = declaredBase;

        Uri? control = null, rendering = null;
        foreach (var service in root.Descendants().Where(e => e.Name.LocalName == "service"))
        {
            var type = Value(service, "serviceType");
            var path = Value(service, "controlURL");
            if (string.IsNullOrWhiteSpace(path)) continue;
            if (!Uri.TryCreate(baseUri, path, out var resolved)) continue;

            if (type == AvTransport) control = resolved;
            else if (type == RenderingControl) rendering = resolved;
        }

        if (control is null) return null;

        var name = Value(root, "friendlyName")?.Trim();
        return new DlnaPlayer(
            id: $"dlna:{usn}",
            name: string.IsNullOrWhiteSpace(name) ? $"DLNA ({baseUri.Host})" : name,
            control,
            rendering);
    }
}
