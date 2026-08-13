using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace PortalRemote.Input;

/// <summary>
/// The remote's power button. "Power" on a PC isn't one thing, so it's four modes
/// rather than one guess; the two that can lose unsaved work are confirmed on the
/// phone before the message is ever sent.
/// </summary>
public static partial class Power
{
    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool LockWorkStation();

    // Posted, not sent: SendMessage to HWND_BROADCAST blocks on every top-level
    // window in the session, and one hung app would take the control socket with it.
    [LibraryImport("user32.dll", EntryPoint = "PostMessageW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool PostMessage(nint hWnd, uint msg, nint wParam, nint lParam);

    private const nint HwndBroadcast = 0xFFFF;
    private const uint WmSysCommand = 0x0112;
    private const nint ScMonitorPower = 0xF170;
    private const nint MonitorOff = 2;

    // powrprof's BOOLEAN is a byte, not a 4-byte BOOL — U1, or the arguments land
    // wrong and the call suspends with flags nobody asked for.
    [LibraryImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    private static partial bool SetSuspendState(
        [MarshalAs(UnmanagedType.U1)] bool hibernate,
        [MarshalAs(UnmanagedType.U1)] bool force,
        [MarshalAs(UnmanagedType.U1)] bool wakeupEventsDisabled);

    /// <summary>Apply one power mode. Names match the phone's <c>PowerMode</c> wire values.</summary>
    public static void Apply(string mode)
    {
        switch (mode)
        {
            // The cheap one: the PC keeps running and the monitor stops lighting the
            // room. Windows wakes it again on the first real input, including the
            // mouse move this very server sends — which is the behaviour you want,
            // since the way back is whatever you were already going to press.
            case "screen_off":
                if (!PostMessage(HwndBroadcast, WmSysCommand, ScMonitorPower, MonitorOff))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                break;

            case "lock":
                if (!LockWorkStation()) throw new Win32Exception(Marshal.GetLastWin32Error());
                break;

            // force:false, so an app that wants to block the suspend still can — the
            // phone is a convenience, not an override of the machine's own veto.
            case "sleep":
                if (!SetSuspendState(false, false, false))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                break;

            // shutdown.exe rather than ExitWindowsEx: the API needs SE_SHUTDOWN_NAME
            // enabled on the token by hand, and this server has no reason to hold it.
            case "restart": Shutdown("/r /t 0"); break;
            case "shutdown": Shutdown("/s /t 0"); break;

            default: throw new UnknownMessageException($"unknown power mode: {mode}");
        }
    }

    private static void Shutdown(string arguments) =>
        Process.Start(new ProcessStartInfo("shutdown", arguments)
        {
            CreateNoWindow = true,
            UseShellExecute = false,
        })?.Dispose();
}
