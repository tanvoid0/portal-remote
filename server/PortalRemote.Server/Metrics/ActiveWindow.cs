using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text;

namespace PortalRemote.Metrics;

/// <summary>
/// The foreground window's process — what Deck's touch-bar-style context row keys off.
/// Windows has no per-app extensibility API for this the way a Touch Bar app declares its
/// own buttons; the closest equivalent is asking the shell which window has focus and
/// reading its owning process, which is what this does.
///
/// Two user32 calls and a <see cref="Process"/> lookup, not a full enumeration, so it's
/// cheap enough to poll — but still refcounted and only pushed on an actual change
/// (<see cref="Tick"/>), the same shape as <see cref="SystemStats"/>: nothing runs, and
/// nothing goes out, for a phone that doesn't have the tab open.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed partial class ActiveWindow : IDisposable
{
    /// <summary>Fast enough that switching apps on the PC reads as immediate on the
    /// phone; slow enough that it's nowhere near worth a hook.</summary>
    private static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(500);

    private readonly object _gate = new();
    private readonly System.Threading.Timer _timer;
    private int _subscribers;
    private bool _disposed;
    private object? _latest;
    private string? _lastProcess;

    public ActiveWindow()
    {
        _timer = new System.Threading.Timer(
            _ => Tick(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
    }

    /// <summary>Raised whenever the foreground process changes, while anyone is
    /// subscribed. Fires on a thread-pool thread.</summary>
    public event Action<object>? Changed;

    public void Subscribe()
    {
        lock (_gate)
        {
            if (_disposed) return;
            if (_subscribers++ > 0) return;
            // Forces the first tick to count as a change even if the foreground app
            // happens to match whatever the last subscriber saw — a fresh watcher gets
            // told what's up front rather than waiting for the next actual switch.
            _lastProcess = null;
            _timer.Change(TimeSpan.Zero, Interval);
        }
    }

    public void Unsubscribe()
    {
        lock (_gate)
        {
            if (_disposed || _subscribers == 0) return;
            if (--_subscribers > 0) return;
            _timer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            _latest = null;
        }
    }

    /// <summary>The most recent push, or null if nothing has been read since the last
    /// subscriber left — what a phone that opens Deck mid-session gets immediately.</summary>
    public object? Latest
    {
        get { lock (_gate) return _latest; }
    }

    private void Tick()
    {
        var (process, title) = ReadForeground();
        // A title changing under the same process — a new tab, a new document — isn't a
        // context switch; only a different process is, since that's what the action
        // catalog on the phone keys off.
        if (process == _lastProcess) return;
        _lastProcess = process;

        object payload = new { t = "active_win", process, title };
        lock (_gate)
        {
            if (_subscribers == 0) return;
            _latest = payload;
        }
        Changed?.Invoke(payload);
    }

    private static (string Process, string Title) ReadForeground()
    {
        var handle = GetForegroundWindow();
        if (handle == nint.Zero) return ("", "");

        var title = ReadTitle(handle);
        GetWindowThreadProcessId(handle, out var pid);
        if (pid == 0) return ("", title);

        try
        {
            using var process = Process.GetProcessById((int)pid);
            return (process.ProcessName, title);
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or Win32Exception)
        {
            // Exited between the handle lookup and the process read — the next tick
            // will have whatever took its place.
            return ("", title);
        }
    }

    private static string ReadTitle(nint handle)
    {
        var length = GetWindowTextLength(handle);
        if (length <= 0) return "";
        var buffer = new StringBuilder(length + 1);
        GetWindowText(handle, buffer, buffer.Capacity);
        return buffer.ToString();
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _subscribers = 0;
        }
        _timer.Dispose();
    }

    [LibraryImport("user32.dll")]
    private static partial nint GetForegroundWindow();

    [LibraryImport("user32.dll")]
    private static partial uint GetWindowThreadProcessId(nint hWnd, out uint processId);

    // StringBuilder marshalling isn't something the LibraryImport source generator
    // supports — DllImport for these two, LibraryImport above for everything else.
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(nint hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(nint hWnd, StringBuilder text, int count);
}

/// <summary>One client's interest in the active-window stream. Same shape as
/// <see cref="StatsSubscription"/> — held by the socket rather than trusted to a phone
/// that always sends "off" before it goes away, since it never will if it walks out of
/// Wi-Fi with Deck open.</summary>
public sealed class ActiveWindowSubscription(ActiveWindow watcher) : IDisposable
{
    private bool _on;

    public void Set(bool on)
    {
        if (on == _on) return;
        _on = on;
        if (on) watcher.Subscribe();
        else watcher.Unsubscribe();
    }

    public void Dispose() => Set(false);
}
