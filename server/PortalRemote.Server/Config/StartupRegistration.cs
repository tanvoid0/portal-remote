using Microsoft.Win32;

namespace PortalRemote.Config;

/// <summary>
/// "Start Portal Remote when I sign in", stored as a value in the per-user Run key —
/// no elevation, no service, and it goes away with the user profile rather than
/// outliving it.
///
/// The installer writes the same value under the same name, so a fresh install already
/// starts at sign-in and the checkbox in the window reports that rather than disagreeing
/// with it.
/// </summary>
public static class StartupRegistration
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "PortalRemote";

    /// <summary>
    /// Registered, <i>and</i> the file it names still exists.
    ///
    /// A registration left pointing at a copy that has been deleted or moved is not
    /// "on": it is a value Windows fails to launch at every sign-in. Reporting that as
    /// enabled is exactly how the setting comes to look switched on while the app is
    /// never actually there in the morning.
    /// </summary>
    public static bool IsEnabled() => RegisteredPath() is { } path && File.Exists(path);

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
        if (key is null) return;

        if (!enabled)
        {
            key.DeleteValue(RunValueName, throwOnMissingValue: false);
            return;
        }

        // Null when running from a host that isn't an executable file (single-file
        // publishing and `dotnet run` both give a real path, so this is defensive).
        if (Environment.ProcessPath is not { } exe) return;
        key.SetValue(RunValueName, Quote(exe));
    }

    /// <summary>
    /// Re-point a <i>broken</i> registration at the copy that is running now.
    ///
    /// Ticking the checkbox stores the path of whatever copy was running at the time —
    /// very often one sitting in Downloads. Move or delete that copy and the
    /// registration keeps naming a file that is no longer there: nothing starts at sign
    /// in, and the only symptom the user ever sees is the app being gone after a
    /// restart. The intent was "start Portal Remote", so it follows Portal Remote.
    ///
    /// Only ever repairs a registration whose target has actually gone. One that still
    /// resolves belongs to a working copy — an installed one, most likely — and moving
    /// it to whichever copy the user happened to double-click today would change
    /// autostart behind their back. It never opts anybody in who was not already.
    /// </summary>
    public static void Repair()
    {
        if (RegisteredPath() is not { } path) return;
        if (File.Exists(path)) return;
        SetEnabled(true);
    }

    /// <summary>The stored value is a command line, so the path is quoted.</summary>
    private static string? RegisteredPath()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        return key?.GetValue(RunValueName) is string value ? value.Trim().Trim('"') : null;
    }

    private static string Quote(string path) => $"\"{path}\"";
}
