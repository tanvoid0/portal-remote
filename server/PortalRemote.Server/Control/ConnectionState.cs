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
    private string? _peer;

    public bool IsConnected => Volatile.Read(ref _connectedCount) > 0;

    /// <summary>Address of the most recent phone to connect. The window names it under
    /// "Phone connected", which used to show <c>Environment.MachineName</c> — this PC's
    /// own name, under a headline about the phone.</summary>
    public string? Peer => Volatile.Read(ref _peer);

    public event Action? Changed;
    public event Action<string>? AuthRejected;

    public void OnConnected(string peer)
    {
        Interlocked.Increment(ref _connectedCount);
        Volatile.Write(ref _peer, peer);
        Changed?.Invoke();
    }

    public void OnDisconnected()
    {
        Interlocked.Decrement(ref _connectedCount);
        Changed?.Invoke();
    }

    public void OnAuthRejected(string peer) => AuthRejected?.Invoke(peer);
}
