using System.Text;
using System.Text.Json;
using System.Xml.Linq;
using PortalRemote.Cast;
using PortalRemote.Config;

namespace PortalRemote.Dlna;

/// <summary>
/// The HTTP half of the DLNA renderer: the device description a controller fetches
/// after SSDP, and the <c>AVTransport</c> SOAP actions it then calls. Four of those
/// actions do the work — <c>SetAVTransportURI</c>, <c>Play</c>, <c>Pause</c>,
/// <c>Seek</c> — and the two <c>Get…Info</c> ones are what stop a controller drawing a
/// dead scrub bar.
///
/// Everything is answered from <see cref="CastRouter"/> and <see cref="CastHub"/>, so a
/// copy of VLC and the phone are driving exactly the same player.
/// </summary>
public static class DlnaEndpoints
{
    private const string AvTransport = "urn:schemas-upnp-org:service:AVTransport:1";
    private const string ConnectionManager = "urn:schemas-upnp-org:service:ConnectionManager:1";

    public static void MapDlnaEndpoints(this WebApplication app, ServerConfig config, DlnaRenderer renderer)
    {
        if (!config.EnableDlnaRenderer) return;

        app.MapGet("/dlna/device.xml", (HttpContext http) =>
            Results.Content(DeviceDescription(renderer.Uuid, http.Request.Host.Value ?? "localhost"),
                "text/xml; charset=\"utf-8\""));

        // Controllers fetch the service description before calling anything, and a 404
        // here is read as "this renderer is broken" rather than "no such file".
        app.MapGet("/dlna/AVTransport.xml", () => Results.Content(AvTransportScpd, "text/xml; charset=\"utf-8\""));

        app.MapPost("/dlna/control/AVTransport", async (HttpContext http) =>
        {
            var action = SoapAction(http);
            var body = await new StreamReader(http.Request.Body).ReadToEndAsync();
            return Handle(action, body);
        });

        // GetProtocolInfo is called before a cast by most controllers; answering it with
        // a 500 is how a sender decides this renderer can't play anything.
        app.MapPost("/dlna/control/ConnectionManager", (HttpContext http) =>
            SoapAction(http) == "GetProtocolInfo"
                ? Soap("GetProtocolInfo", ConnectionManager,
                    ("Source", ""), ("Sink", ProtocolInfo))
                : SoapFault(401, "Invalid Action"));
    }

    private static string SoapAction(HttpContext http) =>
        (http.Request.Headers["SOAPAction"].ToString() ?? string.Empty)
            .Trim('"').Split('#').LastOrDefault() ?? string.Empty;

    private static IResult Handle(string action, string body) => action switch
    {
        // The cast itself. Metadata is DIDL-Lite; the only field worth reading out of it
        // is the title, which is what a receiver or mpv window then shows.
        "SetAVTransportURI" => Cast(Element(body, "CurrentURI"), TitleOf(Element(body, "CurrentURIMetaData"))),

        "Play" => Transport("play"),
        "Pause" => Transport("pause"),
        "Stop" => Transport("stop"),
        "Seek" => Seek(Element(body, "Target")),

        "GetTransportInfo" => Soap("GetTransportInfo", AvTransport,
            ("CurrentTransportState", TransportState()),
            ("CurrentTransportStatus", "OK"),
            ("CurrentSpeed", "1")),

        "GetPositionInfo" => Position(),

        // Answered rather than faulted: a controller polls these and treats a fault as
        // a renderer that has gone away.
        "GetMediaInfo" => Soap("GetMediaInfo", AvTransport,
            ("NrTracks", "1"),
            ("MediaDuration", Timecode(StatusValue("duration"))),
            ("CurrentURI", ""), ("CurrentURIMetaData", ""),
            ("NextURI", ""), ("NextURIMetaData", ""),
            ("PlayMedium", "NETWORK"), ("RecordMedium", "NOT_IMPLEMENTED"),
            ("WriteStatus", "NOT_IMPLEMENTED")),

        "SetNextAVTransportURI" => Soap("SetNextAVTransportURI", AvTransport),
        "GetDeviceCapabilities" => Soap("GetDeviceCapabilities", AvTransport,
            ("PlayMedia", "NETWORK"), ("RecMedia", "NOT_IMPLEMENTED"), ("RecQualityModes", "NOT_IMPLEMENTED")),
        "GetTransportSettings" => Soap("GetTransportSettings", AvTransport,
            ("PlayMode", "NORMAL"), ("RecQualityMode", "NOT_IMPLEMENTED")),

        _ => SoapFault(401, "Invalid Action"),
    };

    private static IResult Cast(string? url, string? title)
    {
        if (string.IsNullOrWhiteSpace(url)) return SoapFault(402, "Invalid Args");
        try
        {
            // Same validation as everything else: only http(s) reaches a player, so a
            // controller cannot turn "cast this" into "run this".
            CastRouter.Cast(url, title);
            return Soap("SetAVTransportURI", AvTransport);
        }
        catch (Input.UnknownMessageException)
        {
            return SoapFault(714, "Illegal MIME-type");
        }
    }

    private static IResult Transport(string action) =>
        CastRouter.Transport(action, null, null, null, null)
            ? Soap(action == "play" ? "Play" : action == "pause" ? "Pause" : "Stop", AvTransport)
            // 701 is the standard "not in a state where that makes sense", which is
            // exactly true: nothing is attached to play.
            : SoapFault(701, "Transition not available");

    private static IResult Seek(string? target)
    {
        var seconds = Seconds(target);
        if (seconds is null) return SoapFault(711, "Illegal seek target");
        return CastRouter.Transport("seek", seconds, null, null, null)
            ? Soap("Seek", AvTransport)
            : SoapFault(701, "Transition not available");
    }

    private static IResult Position() => Soap("GetPositionInfo", AvTransport,
        ("Track", "1"),
        ("TrackDuration", Timecode(StatusValue("duration"))),
        ("TrackMetaData", ""),
        ("TrackURI", ""),
        ("RelTime", Timecode(StatusValue("position"))),
        ("AbsTime", Timecode(StatusValue("position"))),
        ("RelCount", "2147483647"),
        ("AbsCount", "2147483647"));

    /// <summary>Whatever the receiver page or mpv last reported, in UPnP's words.</summary>
    private static string TransportState()
    {
        var status = CastHub.Instance.LastStatus;
        if (status is null) return "STOPPED";
        return StatusFlag(status, "ended") ? "STOPPED"
            : StatusFlag(status, "paused") ? "PAUSED_PLAYBACK"
            : "PLAYING";
    }

    private static double StatusValue(string name)
    {
        var status = CastHub.Instance.LastStatus;
        if (status is null) return 0;
        try
        {
            using var document = JsonDocument.Parse(status);
            return document.RootElement.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number
                ? value.GetDouble()
                : 0;
        }
        catch (JsonException)
        {
            return 0;
        }
    }

    private static bool StatusFlag(string status, string name)
    {
        try
        {
            using var document = JsonDocument.Parse(status);
            return document.RootElement.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    /// <summary>UPnP times are <c>H:MM:SS</c>, and a controller that gets anything else
    /// shows nothing at all rather than complaining.</summary>
    private static string Timecode(double seconds)
    {
        var span = TimeSpan.FromSeconds(Math.Max(0, seconds));
        return $"{(int)span.TotalHours}:{span.Minutes:00}:{span.Seconds:00}";
    }

    private static double? Seconds(string? timecode)
    {
        if (string.IsNullOrWhiteSpace(timecode)) return null;
        var parts = timecode.Split(':');
        if (parts.Length != 3) return null;
        if (!int.TryParse(parts[0], out var hours) || !int.TryParse(parts[1], out var minutes)) return null;
        // Seconds can carry a fraction, and some controllers send one.
        if (!double.TryParse(parts[2], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var seconds)) return null;
        return hours * 3600 + minutes * 60 + seconds;
    }

    /// <summary>The text of the first matching element, whatever namespace it is in.</summary>
    private static string? Element(string xml, string name)
    {
        try
        {
            return XDocument.Parse(xml).Descendants()
                .FirstOrDefault(e => e.Name.LocalName == name)?.Value;
        }
        catch (System.Xml.XmlException)
        {
            return null;
        }
    }

    /// <summary><c>dc:title</c> out of a DIDL-Lite blob, which arrives XML-escaped
    /// inside another XML document.</summary>
    private static string? TitleOf(string? didl) =>
        string.IsNullOrWhiteSpace(didl) ? null : Element(didl, "title");

    private static IResult Soap(string action, string service, params (string Name, string Value)[] fields)
    {
        var body = new StringBuilder();
        body.Append($"<u:{action}Response xmlns:u=\"{service}\">");
        foreach (var (name, value) in fields)
            body.Append($"<{name}>{System.Security.SecurityElement.Escape(value)}</{name}>");
        body.Append($"</u:{action}Response>");

        return Results.Content(Envelope(body.ToString()), "text/xml; charset=\"utf-8\"");
    }

    private static IResult SoapFault(int code, string description) =>
        Results.Content(
            Envelope(
                "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
                "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                $"<errorCode>{code}</errorCode><errorDescription>{description}</errorDescription>" +
                "</UPnPError></detail></s:Fault>"),
            "text/xml; charset=\"utf-8\"",
            statusCode: 500);

    private static string Envelope(string body) =>
        "<?xml version=\"1.0\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        $"<s:Body>{body}</s:Body></s:Envelope>";

    /// <summary>
    /// What this renderer claims it can play. Deliberately broad: the player behind it
    /// is mpv, whose format coverage is ffmpeg's, so listing a narrow set would only
    /// stop senders offering things that would in fact have worked.
    /// </summary>
    private const string ProtocolInfo =
        "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*,http-get:*:application/x-mpegURL:*";

    private static string DeviceDescription(string uuid, string host) =>
        $"""
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>{System.Security.SecurityElement.Escape(Environment.MachineName)} ({ServerInfo.Name})</friendlyName>
            <manufacturer>{ServerInfo.Name}</manufacturer>
            <modelName>{ServerInfo.Name}</modelName>
            <modelNumber>{ServerInfo.Version}</modelNumber>
            <UDN>{uuid}</UDN>
            <serviceList>
              <service>
                <serviceType>{AvTransport}</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>http://{host}/dlna/AVTransport.xml</SCPDURL>
                <controlURL>http://{host}/dlna/control/AVTransport</controlURL>
                <eventSubURL></eventSubURL>
              </service>
              <service>
                <serviceType>{ConnectionManager}</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>http://{host}/dlna/AVTransport.xml</SCPDURL>
                <controlURL>http://{host}/dlna/control/ConnectionManager</controlURL>
                <eventSubURL></eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
        """;

    /// <summary>
    /// The actions we implement, and nothing else. A controller reads this to decide
    /// what to offer, so listing an action we fault on would be a worse lie than not
    /// listing it. Empty <c>eventSubURL</c> above says the same about GENA eventing:
    /// controllers fall back to polling <c>GetPositionInfo</c>, which we answer.
    /// </summary>
    private const string AvTransportScpd =
        """
        <?xml version="1.0"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action><name>SetAVTransportURI</name></action>
            <action><name>Play</name></action>
            <action><name>Pause</name></action>
            <action><name>Stop</name></action>
            <action><name>Seek</name></action>
            <action><name>GetTransportInfo</name></action>
            <action><name>GetPositionInfo</name></action>
            <action><name>GetMediaInfo</name></action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
        """;
}
