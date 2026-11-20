using System.Security.AccessControl;
using System.Security.Principal;

namespace PortalRemote.Secure;

/// <summary>
/// A log file for the two processes that have nowhere to print. The service lives in
/// session 0 and the helper is spawned onto the lock screen's desktop: neither has a
/// console, neither has a tray, and a debugger cannot casually be attached to either.
/// Without this, the only symptom of anything going wrong is a phone that does nothing,
/// which is the same symptom as the feature being switched off.
///
/// Both writers are LocalSystem, which makes where this file lives a security question
/// rather than a tidiness one. <c>C:\ProgramData</c> lets any user create a subdirectory
/// and own what they create, so an ordinary account can make <c>PortalRemote\</c> — or
/// plant a junction where it would go — before the service first runs, and then SYSTEM is
/// appending attacker-chosen text to a file of their choosing. So the directory is created
/// with an explicit descriptor that only SYSTEM and administrators can write, and if one
/// is already there owned by somebody else, nothing is logged at all.
/// </summary>
public static class SecureLog
{
    private static readonly object Gate = new();

    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "PortalRemote", "secure-desktop.log");

    /// <summary>Kept small on purpose — this is a breadcrumb trail, not telemetry.</summary>
    private const long MaxBytes = 512 * 1024;

    /// <summary>Null until the directory has been vetted once; false means never log.</summary>
    private static bool? _usable;

    public static void Write(string line)
    {
        try
        {
            lock (Gate)
            {
                _usable ??= PrepareDirectory();
                if (_usable != true) return;

                // Truncate rather than rotate: the interesting part of this file is always
                // the last few minutes, and a second file is a second thing to tell
                // somebody about when they ask where the log is.
                if (File.Exists(Path) && new FileInfo(Path).Length > MaxBytes) File.Delete(Path);
                File.AppendAllText(Path,
                    $"{DateTime.Now:HH:mm:ss.fff} [{Environment.ProcessId}] {line}{Environment.NewLine}");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Logging must never be the reason input stops working.
        }
    }

    /// <summary>
    /// Make the directory safe to write to, or decide that it isn't. Returns false rather
    /// than throwing, and false is permanent for the life of the process: if this is not
    /// our directory, it will not become ours later.
    /// </summary>
    private static bool PrepareDirectory()
    {
        var directory = System.IO.Path.GetDirectoryName(Path);
        if (directory is null) return false;

        var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);

        try
        {
            if (Directory.Exists(directory))
            {
                // Somebody got here first. Only carry on if that somebody was privileged —
                // an ordinary user owning this path is exactly the case this guards against.
                var owner = new DirectoryInfo(directory).GetAccessControl()
                    .GetOwner(typeof(SecurityIdentifier)) as SecurityIdentifier;
                return owner is not null && (owner == system || owner == admins);
            }

            var security = new DirectorySecurity();
            security.SetOwner(system);
            // Protected, with inherited rules discarded: the point is to not receive
            // ProgramData's CREATOR OWNER grant for users.
            security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            foreach (var account in new[] { system, admins })
            {
                security.AddAccessRule(new FileSystemAccessRule(
                    account, FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                    PropagationFlags.None, AccessControlType.Allow));
            }

            new DirectoryInfo(directory).Create(security);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                                       or PrivilegeNotHeldException or InvalidOperationException)
        {
            return false;
        }
    }
}
