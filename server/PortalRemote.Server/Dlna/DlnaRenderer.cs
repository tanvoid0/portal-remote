using System.Net;
using System.Net.Sockets;
using System.Text;
using PortalRemote.Config;

namespace PortalRemote.Dlna;

/// <summary>
/// Announces this PC as a UPnP/DLNA <c>MediaRenderer</c> — step 4l of
/// <c>docs/phase4-casting.md</c>. DLNA is the protocol Web Video Caster, VLC, BubbleUPnP
/// and most Android gallery apps already speak, so answering it makes this PC a cast
/// target for all of them <b>without a line of client code</b>, and gives the mpv player
/// built in 4b a test harness that isn't our own phone.
///
/// <b>Off unless asked for.</b> A DLNA controller cannot present our pairing token —
/// that is the whole point of speaking someone else's protocol — so this endpoint is
/// open to the LAN, and "anyone on this network can put a video fullscreen on my PC" is
/// not a default anyone chose. <see cref="ServerConfig.EnableDlnaRenderer"/>.
/// </summary>
public sealed class DlnaRenderer : IDisposable
{
    private const string MulticastAddress = "239.255.255.250";
    private const int SsdpPort = 1900;

    /// <summary>The three things a controller searches for that we answer to.</summary>
    private static readonly string[] SearchTargets =
    [
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
    ];

    private readonly ServerConfig config;
    private readonly CancellationTokenSource stopping = new();
    private UdpClient? socket;

    public DlnaRenderer(ServerConfig config) => this.config = config;

    /// <summary>Stable across restarts: derived from the install id, so a controller
    /// that remembered this renderer yesterday still recognises it.</summary>
    public string Uuid => $"uuid:{Guid.Parse(config.Id):D}";

    /// <summary>Where the device description lives. Filled per-request with the address
    /// the controller reached us on — a PC with several NICs has no single right answer,
    /// and the one it dialled is the one it can dial again.</summary>
    public string DescriptionUrl(string host) => $"http://{host}/dlna/device.xml";

    public void Start()
    {
        try
        {
            socket = new UdpClient();
            // Windows already runs an SSDP service on 1900; without this the bind is
            // refused and DLNA silently never works on a machine that has it enabled.
            socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            socket.Client.Bind(new IPEndPoint(IPAddress.Any, SsdpPort));
            socket.JoinMulticastGroup(IPAddress.Parse(MulticastAddress));
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine($"  DLNA renderer disabled: UDP {SsdpPort} unavailable ({ex.SocketErrorCode}).");
            socket?.Dispose();
            socket = null;
            return;
        }

        _ = Task.Run(ListenAsync);
        // Announce rather than wait to be found: a controller already open when the
        // server starts would otherwise not see this PC until its next search.
        _ = Task.Run(() => NotifyAsync("ssdp:alive"));
    }

    private async Task ListenAsync()
    {
        while (!stopping.IsCancellationRequested && socket is not null)
        {
            UdpReceiveResult packet;
            try
            {
                packet = await socket.ReceiveAsync(stopping.Token);
            }
            catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or SocketException)
            {
                return;
            }

            var text = Encoding.UTF8.GetString(packet.Buffer);
            if (!text.StartsWith("M-SEARCH", StringComparison.OrdinalIgnoreCase)) continue;

            var target = HeaderValue(text, "ST");
            // ssdp:all is "tell me everything you are", so it gets one reply per target.
            var matches = target == "ssdp:all"
                ? SearchTargets
                : SearchTargets.Where(t => t == target).ToArray();
            if (matches.Length == 0 && target != Uuid) continue;

            // MX is the controller telling us to spread replies over N seconds so it
            // isn't flooded. Honour it, but cap it: a broken MX of 120 shouldn't mean
            // this PC takes two minutes to show up in a list.
            var mx = Math.Clamp(int.TryParse(HeaderValue(text, "MX"), out var m) ? m : 1, 0, 3);
            var delay = Random.Shared.Next(0, Math.Max(1, mx * 1000));

            _ = Task.Run(async () =>
            {
                await Task.Delay(delay, stopping.Token);
                var host = LocalAddressFor(packet.RemoteEndPoint.Address);
                foreach (var match in matches.Length > 0 ? matches : [Uuid])
                    await ReplyAsync(packet.RemoteEndPoint, match, host);
            }, stopping.Token);
        }
    }

    private async Task ReplyAsync(IPEndPoint to, string target, string host)
    {
        var usn = target.StartsWith("uuid:") ? Uuid : $"{Uuid}::{target}";
        var reply =
            "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            $"LOCATION: {DescriptionUrl(host)}\r\n" +
            $"SERVER: Windows/10 UPnP/1.0 {ServerInfo.Name}/{ServerInfo.Version}\r\n" +
            $"ST: {target}\r\n" +
            $"USN: {usn}\r\n\r\n";

        try
        {
            var bytes = Encoding.UTF8.GetBytes(reply);
            // A fresh socket per reply: the listener is bound to the multicast port and
            // a unicast answer has to come from an ephemeral one.
            using var responder = new UdpClient();
            await responder.SendAsync(bytes, bytes.Length, to);
        }
        catch (SocketException)
        {
            // One controller we couldn't answer. It will search again.
        }
    }

    /// <summary>NOTIFY to the multicast group — alive on start, byebye on exit. The
    /// byebye is what stops a controller listing a PC that has gone.</summary>
    private async Task NotifyAsync(string subtype)
    {
        var group = new IPEndPoint(IPAddress.Parse(MulticastAddress), SsdpPort);
        var host = LocalAddressFor(IPAddress.Parse(MulticastAddress));

        foreach (var target in SearchTargets)
        {
            var message =
                "NOTIFY * HTTP/1.1\r\n" +
                $"HOST: {MulticastAddress}:{SsdpPort}\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                $"LOCATION: {DescriptionUrl(host)}\r\n" +
                $"NT: {target}\r\n" +
                $"NTS: {subtype}\r\n" +
                $"SERVER: Windows/10 UPnP/1.0 {ServerInfo.Name}/{ServerInfo.Version}\r\n" +
                $"USN: {Uuid}::{target}\r\n\r\n";

            try
            {
                var bytes = Encoding.UTF8.GetBytes(message);
                using var announcer = new UdpClient();
                await announcer.SendAsync(bytes, bytes.Length, group);
            }
            catch (SocketException)
            {
                return;
            }
        }
    }

    /// <summary>Our address on the route to <paramref name="peer"/>, and the port
    /// Kestrel is actually listening on.</summary>
    private string LocalAddressFor(IPAddress peer)
    {
        try
        {
            // A connect on UDP sends nothing; it just resolves which local address the
            // routing table would use. Cheaper and more correct than enumerating NICs.
            using var probe = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            probe.Connect(peer, SsdpPort);
            var local = ((IPEndPoint?)probe.LocalEndPoint)?.Address;
            if (local is not null) return $"{local}:{config.RunningPort}";
        }
        catch (SocketException)
        {
            // Fall through.
        }
        return $"{IPAddress.Loopback}:{config.RunningPort}";
    }

    /// <summary>Shared with the sender half, which parses the same headers off the
    /// replies to its own searches.</summary>
    private static string? HeaderValue(string message, string name) => Cast.Ssdp.HeaderValue(message, name);

    public void Dispose()
    {
        // Best-effort and synchronous: the process is on its way out, and a controller
        // that misses this just times the entry out instead.
        if (socket is not null) NotifyAsync("ssdp:byebye").Wait(TimeSpan.FromMilliseconds(500));
        stopping.Cancel();
        socket?.Dispose();
        stopping.Dispose();
    }
}
