using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace PortalRemote.Tray;

/// <summary>
/// One Portal Remote per signed-in user, and a second launch that surfaces the first
/// rather than failing in front of it.
///
/// Kestrel binds a fixed port, so without this a second copy dies on the bind and says
/// "another program is probably already using it" — true, and useless, because the
/// other program is this one. The installer turns that from a rare accident into the
/// normal case: it registers the app to start at sign-in, and still leaves a Start-menu
/// shortcut sitting there to be clicked afterwards.
///
/// Scoped to the session (<c>Local\</c>) rather than the machine. Two users signed in
/// at once do still collide — on the port, not here — but that collision is over a
/// genuinely separate process running as somebody else, which is what the bind error
/// already describes, and a window message cannot cross a session boundary to raise
/// the other user's window anyway.
/// </summary>
internal sealed partial class SingleInstance : NativeWindow, IDisposable
{
    private const string MutexName = @"Local\PortalRemote.SingleInstance";

    /// <summary>
    /// Registered rather than picked out of the WM_USER range. This arrives by
    /// broadcast, so it has to be a number no unrelated program could mistake for one
    /// of its own; RegisterWindowMessage is what makes it unique system-wide, and both
    /// processes get the same number back for the same string.
    /// </summary>
    private static readonly uint ShowWindowMessage = RegisterWindowMessage("PortalRemote.ShowWindow");

    private static readonly IntPtr HwndBroadcast = new(0xFFFF);

    private readonly Mutex _held;

    /// <summary>
    /// Raised when another copy was started and handed its turn back to this one.
    /// Settable after construction because the claim has to happen before the port is
    /// bound, while the tray that knows how to show a window does not exist until
    /// after it.
    /// </summary>
    public Action? ShowRequested { get; set; }

    private SingleInstance(Mutex held)
    {
        _held = held;
        // Deliberately not a message-only window: HWND_BROADCAST reaches top-level
        // windows only, and parenting this to HWND_MESSAGE would make it invisible to
        // exactly the message it exists to receive.
        CreateHandle(new CreateParams());
    }

    /// <summary>
    /// Claim the right to be the running copy. Null means another one already holds it
    /// — that copy has been asked to show its window, and this process should exit
    /// without touching the port, the tray or the config.
    /// </summary>
    public static SingleInstance? Claim()
    {
        var mutex = new Mutex(initiallyOwned: true, MutexName, out var isFirst);
        if (isFirst) return new SingleInstance(mutex);

        mutex.Dispose();
        PostMessage(HwndBroadcast, ShowWindowMessage, IntPtr.Zero, IntPtr.Zero);
        return null;
    }

    protected override void WndProc(ref Message m)
    {
        // The zero check is not paranoia about the compare: RegisterWindowMessage
        // returns 0 on failure, and 0 is WM_NULL — which every window receives.
        if (ShowWindowMessage != 0 && (uint)m.Msg == ShowWindowMessage) ShowRequested?.Invoke();
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        DestroyHandle();
        _held.Dispose();
    }

    [LibraryImport("user32.dll", EntryPoint = "RegisterWindowMessageW", StringMarshalling = StringMarshalling.Utf16)]
    private static partial uint RegisterWindowMessage(string lpString);

    [LibraryImport("user32.dll", EntryPoint = "PostMessageW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
}
