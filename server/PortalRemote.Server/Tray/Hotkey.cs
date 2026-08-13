using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace PortalRemote.Tray;

/// <summary>
/// A system-wide hotkey, delivered to an invisible message-only window of our own
/// rather than to <c>MainForm</c> — the tray app usually has no window open at all,
/// and the whole point of the hotkey is to work while you're in some other app.
///
/// <see cref="Registered"/> is false if another program already owns the
/// combination; that's reported, never retried with a different one, since a
/// shortcut that silently moves is worse than one that doesn't work.
/// </summary>
internal sealed partial class Hotkey : NativeWindow, IDisposable
{
    public const uint ModAlt = 0x0001;
    public const uint ModControl = 0x0002;
    public const uint ModShift = 0x0004;
    /// <summary>Don't repeat while the keys are held — one press, one share.</summary>
    public const uint ModNoRepeat = 0x4000;

    private const int WmHotkey = 0x0312;
    private const int HotkeyId = 1;

    private readonly Action _onPressed;

    public bool Registered { get; }

    public Hotkey(uint modifiers, uint virtualKey, Action onPressed)
    {
        _onPressed = onPressed;
        CreateHandle(new CreateParams());
        Registered = RegisterHotKey(Handle, HotkeyId, modifiers | ModNoRepeat, virtualKey);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmHotkey && (int)m.WParam == HotkeyId) _onPressed();
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        if (Registered) UnregisterHotKey(Handle, HotkeyId);
        DestroyHandle();
    }

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool UnregisterHotKey(IntPtr hWnd, int id);
}
