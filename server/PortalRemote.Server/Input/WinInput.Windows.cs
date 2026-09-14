using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;

namespace PortalRemote.Input;

/// <summary>
/// Top-level windows: the list the phone picks a mirror target from, the rectangle a
/// picked window covers, and the shove that puts it in front. The mirror captures a
/// window by grabbing its on-screen rectangle, not by <c>PrintWindow</c>: the phone
/// then sees exactly what a tap at that spot hits, which matters more than seeing
/// through whatever is stacked on top. The cost is that the window has to actually
/// be in front, which is what <see cref="Focus"/> is for.
/// </summary>
public static partial class WinInput
{
    /// <summary><see cref="Handle"/> is what clients pass back as <c>win</c>/<c>window</c>.</summary>
    public sealed record WindowInfo(long Handle, string Title, string Process);

    private const int GwlExStyle = -20;
    private const int WsExToolWindow = 0x00000080;
    private const int DwmwaExtendedFrameBounds = 9;
    private const int DwmwaCloaked = 14;
    private const int SwRestore = 9;
    private const ushort VkControl = 0x11;

    private delegate bool EnumWindowsProc(nint hwnd, nint lParam);

    /// <summary>
    /// The windows a person would recognise from the taskbar: visible, titled, not a
    /// tool window, and not cloaked — a window on another virtual desktop, or a Store
    /// app suspended in the background, is "visible" to user32 but draws nothing.
    /// This server's own windows are left out; mirroring the mirror helps nobody.
    /// </summary>
    public static IReadOnlyList<WindowInfo> Windows()
    {
        var list = new List<WindowInfo>();
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd)) return true;
            if ((GetWindowLong(hwnd, GwlExStyle) & WsExToolWindow) != 0) return true;
            if (DwmGetWindowAttribute(hwnd, DwmwaCloaked, out int cloaked, sizeof(int)) == 0 && cloaked != 0) return true;
            var title = WindowTitle(hwnd);
            if (title.Length == 0) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == Environment.ProcessId) return true;
            list.Add(new WindowInfo(hwnd, title, ProcessName(pid)));
            return true;
        }, 0);
        return list;
    }

    /// <summary>The foreground window's process and title — what Deck's context row keys off.</summary>
    public static (string Process, string Title) Foreground()
    {
        var handle = GetForegroundWindow();
        if (handle == nint.Zero) return ("", "");
        var title = WindowTitle(handle);
        GetWindowThreadProcessId(handle, out var pid);
        return (pid == 0 ? "" : ProcessName(pid), title);
    }

    /// <summary>
    /// Bring a window to the front so the region capture actually shows it and taps
    /// land in it. A tray app isn't normally allowed to steal foreground; Windows
    /// grants it to whichever process sent the last input event, so a Ctrl tap — the
    /// one modifier that does nothing on its own in almost every app — goes first.
    /// Best effort: if Windows still refuses, the taskbar button flashes instead.
    /// </summary>
    public static void Focus(long window)
    {
        var hwnd = (nint)window;
        if (!IsWindow(hwnd)) return;
        if (IsIconic(hwnd)) ShowWindow(hwnd, SwRestore);
        if (GetForegroundWindow() == hwnd) return;
        Tap(VkControl);
        SetForegroundWindow(hwnd);
    }

    /// <summary>
    /// Where a window is on screen, or empty if it's minimised or gone. Read fresh
    /// every frame so a window that gets dragged or resized keeps being followed.
    /// <c>DWMWA_EXTENDED_FRAME_BOUNDS</c>, not <c>GetWindowRect</c>: the latter includes
    /// the invisible resize border Windows 10+ keeps around every window — ~7px of
    /// whatever is behind it on each side.
    /// </summary>
    private static Rectangle WindowBounds(nint hwnd)
    {
        if (IsIconic(hwnd)) return Rectangle.Empty;
        if (DwmGetWindowAttribute(hwnd, DwmwaExtendedFrameBounds, out RECT r, Marshal.SizeOf<RECT>()) != 0)
            return Rectangle.Empty;
        return Rectangle.FromLTRB(r.Left, r.Top, r.Right, r.Bottom);
    }

    private static string WindowTitle(nint hwnd)
    {
        var length = GetWindowTextLength(hwnd);
        if (length <= 0) return "";
        var buffer = new StringBuilder(length + 1);
        GetWindowText(hwnd, buffer, buffer.Capacity);
        return buffer.ToString();
    }

    private static string ProcessName(uint pid)
    {
        try
        {
            using var process = Process.GetProcessById((int)pid);
            return process.ProcessName;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or Win32Exception)
        {
            // Exited between the handle lookup and the process read.
            return "";
        }
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsProc callback, nint lParam);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool IsWindowVisible(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool IsWindow(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool IsIconic(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool ShowWindow(nint hwnd, int cmd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetForegroundWindow(nint hwnd);

    [LibraryImport("user32.dll")]
    private static partial nint GetForegroundWindow();

    [LibraryImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static partial int GetWindowLong(nint hwnd, int index);

    [LibraryImport("user32.dll")]
    private static partial uint GetWindowThreadProcessId(nint hwnd, out uint processId);

    [LibraryImport("dwmapi.dll")]
    private static partial int DwmGetWindowAttribute(nint hwnd, int attribute, out int value, int size);

    [LibraryImport("dwmapi.dll")]
    private static partial int DwmGetWindowAttribute(nint hwnd, int attribute, out RECT value, int size);

    // StringBuilder marshalling isn't something the LibraryImport source generator
    // supports — DllImport for these two, LibraryImport for everything else.
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(nint hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(nint hWnd, StringBuilder text, int count);
}
