using System.ComponentModel;
using System.Diagnostics;
using System.Security.Principal;

namespace PortalRemote.Secure;

/// <summary>
/// Registering and removing the LocalSystem service, through <c>sc.exe</c> for the same
/// reason <c>Reachability</c> shells out to <c>netsh</c>: the advapi32 equivalents are
/// three P/Invokes and a handle to leak each, for a call that runs twice in the lifetime
/// of an install.
///
/// This is the switch the whole containment story rests on. Until somebody turns the
/// feature on there is no service, so there is no process able to reach the secure
/// desktop and nothing for the pairing token to be escalated through — the default
/// install is exactly the app it was before this existed.
///
/// <b>The service runs its own copy of the exe, under Program Files, and that is not a
/// detail.</b> This app installs per-user into <c>%LOCALAPPDATA%\Programs</c>
/// (<c>PrivilegesRequired=lowest</c>), a directory the user — and therefore anything
/// running as the user — can write to, because the in-place updater has to overwrite the
/// exe there. Pointing a LocalSystem service at that path would mean any code running as
/// the user could replace the binary and be SYSTEM at the next boot: a permanent
/// escalation bought with a single UAC prompt, and a far worse hole than the one this
/// feature is careful about. So <see cref="Install"/> copies the binary somewhere only
/// administrators can write and registers that copy instead.
///
/// The cost of that copy is staleness: an in-place update replaces the per-user exe and
/// leaves the service's copy alone. <see cref="IsStale"/> is how the tray notices, and
/// re-running the toggle is how it is fixed — a re-install refreshes the copy.
/// </summary>
public static class ServiceInstall
{
    /// <summary>Elevated one-shots, same shape as <c>Reachability.InstallFirewallArg</c>.</summary>
    public const string InstallArg = "--install-secure-desktop";

    /// <inheritdoc cref="InstallArg"/>
    public const string UninstallArg = "--uninstall-secure-desktop";

    /// <summary>Exit code <c>sc</c> uses for "no such service", which is not a failure here.</summary>
    private const int ServiceDoesNotExist = 1060;

    private static string ExePath => Environment.ProcessPath ?? "";

    /// <summary>
    /// Under Program Files, so its ACL is the one Windows puts there: administrators and
    /// SYSTEM write, everyone else reads. That is the whole defence — see the class remark.
    /// </summary>
    private static string ServiceDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Portal Remote");

    private static string ServiceExePath => Path.Combine(ServiceDirectory, "PortalRemote.exe");

    /// <summary>True if the service is registered — not necessarily running.</summary>
    public static bool IsInstalled() => Sc("query", SecureChannel.ServiceName) == 0;

    /// <summary>
    /// True when the service is installed but running a build older than this one, which
    /// an in-place update leaves behind. Worth surfacing rather than leaving quiet: this
    /// is the one copy of the app that runs as SYSTEM, so it is the last one that should
    /// silently miss a fix.
    /// </summary>
    public static bool IsStale()
    {
        if (!File.Exists(ServiceExePath) || ExePath.Length == 0) return false;

        try
        {
            var running = FileVersionInfo.GetVersionInfo(ExePath).FileVersion;
            var installed = FileVersionInfo.GetVersionInfo(ServiceExePath).FileVersion;
            return running is not null && installed is not null && running != installed;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>
    /// Create, configure and start the service. Runs inside the elevated re-launch, so by
    /// the time it is called the UAC prompt has already been answered — which is also what
    /// makes it able to write to Program Files.
    /// </summary>
    public static int Install()
    {
        if (ExePath.Length == 0) return 1;

        // Delete first: it stops the old service, which is what frees its copy of the exe
        // for the overwrite below, and it clears a registration whose binPath may point at
        // a path that no longer exists.
        Uninstall();

        if (!StageBinary()) return 1;

        // obj= LocalSystem said out loud rather than left to the default: every line of
        // what this service does depends on being SYSTEM, and a reader should not have to
        // know sc.exe's defaults to see that.
        var created = Sc(
            "create", SecureChannel.ServiceName,
            "binPath=", $"\"{ServiceExePath}\" {SecureChannel.ServiceArg}",
            "start=", "auto",
            "obj=", "LocalSystem",
            "DisplayName=", "Portal Remote secure desktop");
        if (created != 0) return created;

        Sc("description", SecureChannel.ServiceName,
            "Lets Portal Remote reach the Windows lock screen and elevation prompts.");

        // If it dies, bring it back: the only symptom the user would otherwise see is a
        // phone that stops working on the lock screen and nowhere else.
        Sc("failure", SecureChannel.ServiceName,
            "reset=", "60",
            "actions=", "restart/5000/restart/5000/restart/5000");

        return Sc("start", SecureChannel.ServiceName);
    }

    /// <summary>
    /// Put this build where only an administrator can rewrite it. Retried rather than
    /// attempted once: <c>sc stop</c> returns when the stop has been <i>asked</i> for, so
    /// the previous service can still hold its image open for a moment afterwards.
    /// </summary>
    private static bool StageBinary()
    {
        var source = System.IO.Path.GetDirectoryName(ExePath);
        if (source is null) return false;

        try
        {
            Directory.CreateDirectory(ServiceDirectory);

            for (var attempt = 0; attempt < 10; attempt++)
            {
                try
                {
                    CopyTree(source, ServiceDirectory);
                    return true;
                }
                catch (IOException)
                {
                    Thread.Sleep(300);
                }
            }
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or IOException
                                       or NotSupportedException or ArgumentException)
        {
            // Not elevated, or Program Files is not writable for some other reason.
        }

        return false;
    }

    /// <summary>
    /// The whole app directory, not just the exe.
    ///
    /// A shipped build is a single self-contained file, so this copies exactly one thing
    /// and the distinction never comes up. A build straight out of <c>dotnet build</c> is
    /// not: its exe is a stub that needs <c>PortalRemote.dll</c>, the runtime config and a
    /// handful of assemblies beside it. Staging only the exe from one of those produces a
    /// service that registers cleanly, starts, and dies immediately with nothing useful
    /// said anywhere — so copy everything and let the single-file case be the easy one.
    /// </summary>
    private static void CopyTree(string from, string to)
    {
        Directory.CreateDirectory(to);

        foreach (var file in Directory.EnumerateFiles(from))
        {
            var name = System.IO.Path.GetFileName(file);
            // The installer's own uninstaller belongs to the per-user install, and the
            // previous build the updater parked next door is 90MB of nothing.
            if (name.StartsWith("unins", StringComparison.OrdinalIgnoreCase)) continue;
            if (name.EndsWith(".old", StringComparison.OrdinalIgnoreCase)) continue;
            File.Copy(file, System.IO.Path.Combine(to, name), overwrite: true);
        }

        foreach (var directory in Directory.EnumerateDirectories(from))
            CopyTree(directory, System.IO.Path.Combine(to, System.IO.Path.GetFileName(directory)));
    }

    /// <summary>
    /// Stop and remove it, and take the staged copy with it. Succeeds when there was
    /// nothing there, so it is safe to call from the uninstaller and from
    /// <see cref="Install"/>'s own clean slate.
    /// </summary>
    public static int Uninstall()
    {
        Sc("stop", SecureChannel.ServiceName);
        var deleted = Sc("delete", SecureChannel.ServiceName);

        // Best effort, and after the delete: a copy left behind is inert — nothing starts
        // it — where a copy removed while the service still referenced it is a service
        // that fails to start with no explanation.
        try
        {
            if (Directory.Exists(ServiceDirectory)) Directory.Delete(ServiceDirectory, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Still running, or not ours to delete. The registration is gone either way.
        }

        return deleted is 0 or ServiceDoesNotExist ? 0 : deleted;
    }

    /// <summary>
    /// Remove the service from wherever the caller happens to be standing: already
    /// elevated, do it; otherwise ask for permission first. Used by both the tray and the
    /// uninstaller, and it starts by checking whether there is anything to remove — most
    /// installs never turn this on, and none of them should meet a UAC prompt on the way
    /// out because of a feature they never used.
    /// </summary>
    public static int RemoveIfPresent()
    {
        if (!IsInstalled()) return 0;
        if (IsElevated()) return Uninstall();
        // Re-enters this same switch in the elevated copy, which then takes the branch
        // above rather than prompting again.
        return Request(install: false) ? 0 : 1;
    }

    private static bool IsElevated()
    {
        using var identity = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
    }

    /// <summary>
    /// Ask Windows for permission, then do it. False means the user dismissed the UAC
    /// prompt, which is an answer rather than an error — turning this on is meant to be
    /// a decision, so declining has to be a supported outcome.
    /// </summary>
    public static bool Request(bool install)
    {
        if (ExePath.Length == 0) return false;

        try
        {
            using var elevated = Process.Start(new ProcessStartInfo
            {
                FileName = ExePath,
                Arguments = install ? InstallArg : UninstallArg,
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

    /// <summary>
    /// ArgumentList rather than one Arguments string: <c>sc</c> wants <c>binPath=</c> and
    /// its value as two separate arguments, and the value itself contains the quotes that
    /// keep an exe path with spaces in one piece. Letting .NET do that escaping is the
    /// difference between this working and working only on paths without spaces.
    /// </summary>
    private static int Sc(params string[] arguments)
    {
        var info = new ProcessStartInfo("sc.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var argument in arguments) info.ArgumentList.Add(argument);

        try
        {
            using var process = Process.Start(info);
            if (process is null) return -1;
            // Bounded: a stop can block on a service that is wedged, and this runs from
            // the tray menu.
            if (!process.WaitForExit(15_000)) return -1;
            return process.ExitCode;
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            return -1;
        }
    }
}
