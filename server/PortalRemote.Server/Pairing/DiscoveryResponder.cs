using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PortalRemote.Config;

namespace PortalRemote.Pairing;

/// <summary>
/// Answers the phone's LAN discovery probe, so a PC can be picked from a list
/// instead of having its address typed in.
///
/// The reply deliberately carries nothing secret — a name, the HTTP port and a
/// version, all of which anything on the LAN could already learn by scanning.
/// The pairing token is only ever handed out by <c>/pair/request</c>, which needs
/// someone to click Allow on this PC.
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    /// <summary>UDP, and fixed — the phone has to know where to shout before it
    /// knows anything else about this PC, including which HTTP port it chose.</summary>
    public const int Port = 8765;

    public const string Probe = "PORTALREMOTE?";

    private readonly ServerConfig _config;
    private readonly CancellationTokenSource _cts = new();
    private UdpClient? _udp;

    public DiscoveryResponder(ServerConfig config) => _config = config;

    /// <summary>Returns false if the socket could not be bound; discovery is a
    /// convenience (QR and manual entry both still work), so a failure here must
    /// never stop the server from starting.</summary>
    public bool Start()
    {
        try
        {
            _udp = new UdpClient(new IPEndPoint(IPAddress.Any, Port)) { EnableBroadcast = true };
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine($"  Discovery disabled: UDP {Port} unavailable ({ex.SocketErrorCode}).");
            return false;
        }

        _ = Task.Run(() => LoopAsync(_udp, _cts.Token));
        return true;
    }

    private async Task LoopAsync(UdpClient udp, CancellationToken ct)
    {
        var reply = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
        {
            name = Environment.MachineName,
            // So a phone holding a stale IP for this PC can tell it is the same PC.
            id = _config.Id,
            // RunningPort, not Port: a port edited in the settings window is only
            // live after a restart — same reason PairingService uses it.
            port = _config.RunningPort,
            version = ServerInfo.Version,
        }));

        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult received;
            try
            {
                received = await udp.ReceiveAsync(ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (SocketException)
            {
                // Typically an ICMP port-unreachable queued against an earlier
                // reply. Not fatal — keep listening.
                continue;
            }

            if (Encoding.UTF8.GetString(received.Buffer).Trim() != Probe) continue;

            try
            {
                await udp.SendAsync(reply, received.RemoteEndPoint, ct);
            }
            catch (Exception ex) when (ex is SocketException or ObjectDisposedException)
            {
                // The prober went away; nothing to do about it.
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp?.Dispose();
        _cts.Dispose();
    }
}
