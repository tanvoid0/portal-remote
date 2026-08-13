using System.ComponentModel;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace PortalRemote.Config;

/// <summary>
/// The two reasons a phone on the same Wi-Fi still can't reach this PC: Windows
/// Firewall has no rule for this copy of the app, or a VPN is swallowing the
/// traffic. Both are invisible from the phone — it just sees a PC that never
/// answers — so the PC is where they have to be detected and said out loud.
/// </summary>
public static class Reachability
{
    /// <summary>Passed to a second, elevated instance of ourselves; adding a
    /// firewall rule needs admin and the tray app deliberately does not run as one.</summary>
    public const string InstallFirewallArg = "--install-firewall";

    private const string RuleName = "Portal Remote";

    /// <summary>Empty only if the process was started in a way that hides its own
    /// path, which shouldn't happen for a normal launch.</summary>
    private static string ExePath => Environment.ProcessPath ?? string.Empty;

    // Matched against adapter name and description. Deliberately substrings: vendors
    // version their adapter names ("TAP-Windows Adapter V9"), so anchoring on an
    // exact string dates fast.
    private static readonly string[] VpnAdapterHints =
    [
        "vpn", "wireguard", "openvpn", "tap-windows", "nordlynx", "tailscale",
        "zerotier", "proton", "mullvad", "expressvpn", "anyconnect", "globalprotect",
        "pulse secure", "forticlient", "surfshark", "windscribe", "private internet"
    ];

    // Tunnel-type adapters that are always present on Windows and mean nothing.
    private static readonly string[] NotReallyVpn = ["isatap", "teredo", "6to4"];

    /// <summary>The name of an active VPN adapter, or null if there isn't one.</summary>
    public static string? ActiveVpnName()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;

            var text = $"{nic.Name} {nic.Description}";
            if (NotReallyVpn.Any(skip => text.Contains(skip, StringComparison.OrdinalIgnoreCase)))
                continue;

            if (nic.NetworkInterfaceType is NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel)
                return nic.Name;

            if (VpnAdapterHints.Any(hint => text.Contains(hint, StringComparison.OrdinalIgnoreCase)))
                return nic.Name;
        }

        return null;
    }

    /// <summary>True when nothing lets the exe that is running right now accept
    /// connections. Because the check is against the live exe path, a rule left over
    /// from a previous install location correctly reads as "no rule".</summary>
    public static bool FirewallNeedsSetup() => !RuleExists();

    /// <summary>
    /// Create the rule. Runs in the elevated child process, so it must not touch the
    /// config — the parent records the path once this reports success, which also
    /// keeps things straight if the user elevated as a *different* admin account
    /// whose %APPDATA% is somewhere else entirely.
    /// </summary>
    public static int InstallFirewallRule()
    {
        if (ExePath.Length == 0) return 1;

        // Delete first so re-running after a move replaces the stale path rather
        // than leaving two rules, one of which points at an exe that no longer exists.
        Netsh($"advfirewall firewall delete rule name=\"{RuleName}\" dir=in");

        // No protocol= clause: that means every protocol, which is what's wanted —
        // TCP carries the session and UDP carries discovery, and a rule per protocol
        // is two things to keep in step for no benefit.
        // profile=any because a laptop changes profile when it changes network, and
        // a rule scoped to one profile silently stops applying when it does.
        return Netsh(
            $"advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow " +
            $"enable=yes profile=any program=\"{ExePath}\"");
    }

    /// <summary>
    /// Ask Windows for permission and add the rule. Returns false if the user
    /// declined the UAC prompt, which is a normal answer and not an error.
    /// </summary>
    public static bool RequestFirewallSetup()
    {
        if (ExePath.Length == 0) return false;

        try
        {
            using var elevated = Process.Start(new ProcessStartInfo
            {
                FileName = ExePath,
                Arguments = InstallFirewallArg,
                UseShellExecute = true, // required for the runas verb
                Verb = "runas",
            });

            if (elevated is null) return false;
            elevated.WaitForExit();
            return elevated.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            // The UAC prompt was dismissed.
            return false;
        }
    }

    /// <summary>One line for the startup balloon, or null when nothing is wrong.</summary>
    public static string? StartupWarning()
    {
        var vpn = ActiveVpnName();
        var firewall = FirewallNeedsSetup();

        return (firewall, vpn) switch
        {
            (true, not null) =>
                $"Windows Firewall has no rule for this app, and a VPN ({vpn}) is active. "
                + "Your phone probably cannot reach this PC — see Fix network access in the tray menu.",
            (true, null) =>
                "Windows Firewall has no rule for this app yet. If your phone cannot connect, "
                + "use Fix network access in the tray menu.",
            (false, not null) =>
                $"A VPN ({vpn}) is active. If your phone cannot connect, allow local network "
                + "access in the VPN app, or turn it off while using Portal Remote.",
            _ => null,
        };
    }

    // INetFwRule values; the COM interop is untyped here so they're spelled out.
    private const int DirectionIn = 1;
    private const int ActionAllow = 1;

    /// <summary>
    /// Is this exe already allowed in, by any rule at all?
    /// </summary>
    /// <remarks>
    /// Deliberately not "is there a rule called <see cref="RuleName"/>": Windows
    /// creates its own rule, named after the app, when someone answers the
    /// first-listen prompt. Only recognising our own name would report "no rule"
    /// at every launch on a machine that has been working fine for months, and a
    /// warning that cries wolf is worse than no warning.
    ///
    /// Enumerated over COM rather than parsed out of netsh, because netsh prints
    /// localised field names and any text parse breaks on a non-English Windows.
    /// </remarks>
    private static bool RuleExists()
    {
        try
        {
            var policyType = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if (policyType is null) return NamedRuleExists();

            dynamic policy = Activator.CreateInstance(policyType)!;
            foreach (dynamic rule in policy.Rules)
            {
                string? program;
                try
                {
                    if (!rule.Enabled) continue;
                    if ((int)rule.Direction != DirectionIn) continue;
                    if ((int)rule.Action != ActionAllow) continue;
                    program = rule.ApplicationName as string;
                }
                catch (COMException)
                {
                    // A rule that won't answer questions about itself isn't one of
                    // ours; skip it rather than abandoning the whole scan.
                    continue;
                }

                if (program is not null && string.Equals(program, ExePath, StringComparison.OrdinalIgnoreCase))
                    return true;
            }

            return false;
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or MissingMethodException)
        {
            // Firewall service disabled, or the COM class is unavailable.
            return NamedRuleExists();
        }
    }

    /// <summary>Fallback for when the COM API can't be reached: exit code only, since
    /// netsh's printed output is localised.</summary>
    private static bool NamedRuleExists() =>
        Netsh($"advfirewall firewall show rule name=\"{RuleName}\" dir=in") == 0;

    private static int Netsh(string arguments)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo("netsh", arguments)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });

            if (process is null) return -1;
            // Bounded: this runs on the UI thread from the tray menu, and a wedged
            // netsh must not take the window with it.
            if (!process.WaitForExit(10_000)) return -1;
            return process.ExitCode;
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            // netsh missing or unrunnable — report "can't tell" rather than throwing
            // out of a diagnostic.
            return -1;
        }
    }
}
