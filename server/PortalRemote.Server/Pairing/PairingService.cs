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

    public static string PairUrl(ServerConfig config) =>
        PairUrl(LanIp(), config.Port, config.Token);

    public static string HttpBase(ServerConfig config) => $"http://{LanIp()}:{config.Port}";

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
