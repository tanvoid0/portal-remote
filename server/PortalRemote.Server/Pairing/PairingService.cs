using System.Net;
using System.Net.Sockets;
using PortalRemote.Config;
using QRCoder;

namespace PortalRemote.Pairing;

/// <summary>
/// Builds the pairing payload the phone scans:
/// <c>portalremote://&lt;host&gt;:&lt;port&gt;/&lt;token&gt;</c>.
/// </summary>
public static class PairingService
{
    public const string Scheme = "portalremote";

    /// <summary>
    /// Best-effort LAN address of the interface that reaches the default gateway.
    /// Connecting a UDP socket sends no packets but makes the OS select the
    /// outbound interface, which is the address the phone should dial.
    /// </summary>
    public static string LanIp()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.Connect("8.8.8.8", 65530);
            if (socket.LocalEndPoint is IPEndPoint ep)
                return ep.Address.ToString();
        }
        catch (SocketException)
        {
            // No route to the internet; fall through to loopback.
        }
        return "127.0.0.1";
    }

    public static string PairUrl(string host, int port, string token) =>
        $"{Scheme}://{host}:{port}/{token}";

    // RunningPort, not Port: a port edited in the settings window is only live after
    // a restart, and handing the phone an address nothing is listening on is worse
    // than showing the old one.
    public static string PairUrl(ServerConfig config) =>
        PairUrl(LanIp(), config.RunningPort, config.Token);

    public static string HttpBase(ServerConfig config) => $"http://{LanIp()}:{config.RunningPort}";

    /// <summary>
    /// The cast receiver page, token and all. Opened on any other screen — a TV, a
    /// laptop, a console — that screen becomes a cast target. The token rides in the
    /// query string because a browser can't set an Authorization header on its own
    /// navigation, which also means this URL is as good as the pairing token: it goes
    /// on screens the user is already trusting with the QR code, and nowhere else.
    /// </summary>
    public static string ReceiverUrl(ServerConfig config) =>
        $"{HttpBase(config)}/cast/receiver?token={Uri.EscapeDataString(config.Token)}";

    /// <summary>PNG bytes for the pairing QR code.</summary>
    public static byte[] QrPng(string payload, int pixelsPerModule = 8)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data);
        return png.GetGraphic(pixelsPerModule);
    }

    /// <summary>QR rendered with block characters, for the console.</summary>
    public static string QrAscii(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        // Two spaces per module keeps the aspect ratio square in a terminal.
        return new AsciiQRCode(data).GetGraphic(1, "██", "  ", drawQuietZones: true);
    }
}
