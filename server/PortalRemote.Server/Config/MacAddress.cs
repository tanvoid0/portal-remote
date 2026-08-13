using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PortalRemote.Config;

/// <summary>
/// This PC's physical address, told to the phone in the hello so it can wake the
/// machine later — <c>docs/phase4-casting.md</c> §8. A magic packet is the only way to
/// reach a PC that is off, and the phone cannot learn a MAC by itself: ARP is not
/// readable from an app, and by the time the PC is asleep it can't be asked.
/// </summary>
public static class MacAddress
{
    // The adapters don't change while the process runs often enough to matter, and the
    // hello is sent on every connect.
    private static readonly Lazy<string?> Cached = new(Find);

    public static string? OfLanInterface() => Cached.Value;

    private static string? Find() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(nic => nic.OperationalStatus == OperationalStatus.Up)
            // Wake-on-LAN is an Ethernet/Wi-Fi feature; a tunnel or loopback adapter
            // has a physical address that no magic packet will ever reach.
            .Where(nic => nic.NetworkInterfaceType
                is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211)
            // The one with a gateway is the one on the network the phone is on. A
            // machine with several NICs would otherwise hand out whichever came first.
            .Where(nic => nic.GetIPProperties().GatewayAddresses
                .Any(gateway => gateway.Address.AddressFamily == AddressFamily.InterNetwork))
            .Select(nic => nic.GetPhysicalAddress().GetAddressBytes())
            .Where(bytes => bytes.Length == 6)
            .Select(bytes => string.Join(":", bytes.Select(b => b.ToString("x2"))))
            .FirstOrDefault();
}
