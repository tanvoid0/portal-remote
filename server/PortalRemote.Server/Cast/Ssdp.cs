using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace PortalRemote.Cast;

/// <summary>One device that answered an <see cref="Ssdp.SearchAsync"/>.</summary>
/// <param name="Location">The <c>LOCATION</c> header — a device description URL for
/// DLNA, and just <c>http://ip:8060/</c> for a Roku.</param>
/// <param name="Usn">Unique service name. The stable part of a device's identity.</param>
/// <param name="Address">Who answered, which is the only address we know actually
/// works — a <c>LOCATION</c> can name an interface we cannot reach.</param>
public readonly record struct SsdpHit(string Location, string Usn, IPAddress Address);

/// <summary>
/// SSDP <c>M-SEARCH</c>, the discovery half of steps 4i/4j of
/// <c>docs/phase4-casting.md</c>. Roku (<c>roku:ecp</c>) and DLNA renderers
/// (<c>MediaRenderer:1</c>) are both found this way, which is why the doc puts Roku
/// first: it pays for the SSDP that DLNA then reuses.
///
/// The renderer half — answering someone else's M-SEARCH — is <see cref="Dlna.DlnaRenderer"/>.
/// </summary>
public static class Ssdp
{
    public const string MulticastAddress = "239.255.255.250";
    public const int Port = 1900;

    public const string RokuTarget = "roku:ecp";
    public const string MediaRendererTarget = "urn:schemas-upnp-org:device:MediaRenderer:1";

    /// <summary>
    /// Read one header out of an SSDP message. Case-insensitive on the name because
    /// devices are inconsistent about it, and the surrounding quotes some put on
    /// <c>ST</c> are stripped.
    /// </summary>
    public static string? HeaderValue(string message, string name)
    {
        foreach (var line in message.Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.StartsWith($"{name}:", StringComparison.OrdinalIgnoreCase))
                return trimmed[(name.Length + 1)..].Trim().Trim('"');
        }
        return null;
    }

    /// <summary>
    /// Search the LAN for <paramref name="target"/> and collect every distinct answer
    /// until <paramref name="patience"/> runs out. Deduplicated by <c>USN</c>: a device
    /// that hears the search on two interfaces answers twice, and so does one that
    /// retries.
    /// </summary>
    public static async Task<IReadOnlyList<SsdpHit>> SearchAsync(
        string target, TimeSpan patience, CancellationToken cancel = default)
    {
        // MX must be shorter than how long we listen, or well-behaved devices are still
        // spreading their replies when we stop reading.
        var mx = Math.Max(1, (int)(patience.TotalSeconds / 2));
        var request = Encoding.ASCII.GetBytes(
            "M-SEARCH * HTTP/1.1\r\n" +
            $"HOST: {MulticastAddress}:{Port}\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            $"MX: {mx}\r\n" +
            $"ST: {target}\r\n\r\n");

        var group = new IPEndPoint(IPAddress.Parse(MulticastAddress), Port);
        var found = new Dictionary<string, SsdpHit>();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(patience);

        // One socket per interface, not one on the default route. A PC with a VPN, a
        // Hyper-V switch or a second NIC routes multicast out whichever the metric
        // picks — which on this kind of machine is regularly not the Wi-Fi the TV is
        // on, and the failure looks like "no devices found" with nothing to debug.
        var sockets = LocalAddresses().Select(address =>
        {
            try
            {
                var client = new UdpClient(new IPEndPoint(address, 0));
                client.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 4);
                return client;
            }
            catch (SocketException)
            {
                return null;
            }
        }).OfType<UdpClient>().ToList();

        try
        {
            var readers = sockets.Select(async socket =>
            {
                try
                {
                    // Twice, a beat apart: a single multicast datagram is not
                    // retransmitted, and one lost packet is one missing TV.
                    await socket.SendAsync(request, request.Length, group);
                    await Task.Delay(TimeSpan.FromMilliseconds(300), deadline.Token);
                    await socket.SendAsync(request, request.Length, group);

                    while (!deadline.IsCancellationRequested)
                    {
                        var packet = await socket.ReceiveAsync(deadline.Token);
                        var text = Encoding.UTF8.GetString(packet.Buffer);
                        if (!text.StartsWith("HTTP/1.1 200", StringComparison.OrdinalIgnoreCase)) continue;

                        var location = HeaderValue(text, "LOCATION");
                        if (string.IsNullOrWhiteSpace(location)) continue;

                        var usn = HeaderValue(text, "USN") ?? location;
                        lock (found) found[usn] = new SsdpHit(location, usn, packet.RemoteEndPoint.Address);
                    }
                }
                catch (Exception ex) when (ex is OperationCanceledException or SocketException or ObjectDisposedException)
                {
                    // Out of patience, or this interface can't do multicast. Either way
                    // the other interfaces' answers still count.
                }
            });

            await Task.WhenAll(readers);
        }
        finally
        {
            foreach (var socket in sockets) socket.Dispose();
        }

        lock (found) return [.. found.Values];
    }

    /// <summary>Every up, non-loopback IPv4 address on this machine.</summary>
    private static IEnumerable<IPAddress> LocalAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(nic => nic.OperationalStatus == OperationalStatus.Up
                       && nic.NetworkInterfaceType != NetworkInterfaceType.Loopback
                       && nic.SupportsMulticast)
            .SelectMany(nic => nic.GetIPProperties().UnicastAddresses)
            .Select(unicast => unicast.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork);
}
