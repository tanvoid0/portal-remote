using System.Threading;

namespace PortalRemote.Control;

/// <summary>
/// Tracks how many `/control` clients are connected and whether a pairing attempt
/// was just rejected, so the tray icon can reflect connection state. See
/// docs/design-system.md §7 (TrayIcon states). Raised from Kestrel worker threads —
/// subscribers must marshal back to the UI thread themselves.
/// </summary>
public sealed class ConnectionState
{
    private int _connectedCount;

    public bool IsConnected => Volatile.Read(ref _connectedCount) > 0;

    public event Action? Changed;
    public event Action? AuthRejected;

    public void OnConnected()
    {
        Interlocked.Increment(ref _connectedCount);
        Changed?.Invoke();
    }

    public void OnDisconnected()
    {
        Interlocked.Decrement(ref _connectedCount);
        Changed?.Invoke();
    }

    public void OnAuthRejected() => AuthRejected?.Invoke();
}
