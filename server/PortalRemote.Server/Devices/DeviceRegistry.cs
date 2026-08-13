using PortalRemote.Config;

namespace PortalRemote.Devices;

/// <summary>A phone this PC has seen. Persisted, so the window can list a phone that
/// is switched off rather than pretending nothing was ever paired.</summary>
public sealed class KnownDevice
{
    /// <summary>What the phone calls itself — the user's device name where Android
    /// exposes one, its model otherwise. Also the identity: a phone that changes
    /// address keeps its row, which is the whole point of not keying on the address.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Where it last connected from. DHCP moves this around, so it is shown
    /// as a detail and never used to decide which device a socket belongs to.</summary>
    public string Address { get; set; } = string.Empty;

    public DateTimeOffset LastSeen { get; set; }
}

/// <summary>Fields the window draws for one row: the persisted device plus whether it
/// happens to be on the other end of a socket right now.</summary>
public sealed record DeviceStatus(string Name, string Address, DateTimeOffset LastSeen, bool Connected);

/// <summary>
/// Which phones this PC knows, and which of them are connected.
///
/// Two things are deliberately separate here. The <em>known</em> list is persisted in
/// the config and only grows — a phone that is asleep in another room is still a phone
/// this PC is paired with, and the window says so instead of showing "not connected"
/// with no clue as to what is not connected. The <em>connected</em> set lives only in
/// memory and is rebuilt by sockets opening and closing.
///
/// Keyed by name rather than address because addresses are handed out by the router:
/// the same phone comes back as a different address often enough that keying on it
/// would fill the list with ghosts of one device.
/// </summary>
public sealed class DeviceRegistry(ServerConfig config)
{
    private readonly object _gate = new();

    /// <summary>Names with a socket open. A count per name, not a flag: the phone
    /// briefly holds two sockets while reconnecting, and the first one closing must
    /// not mark a live phone as gone.</summary>
    private readonly Dictionary<string, int> _connected = new(StringComparer.OrdinalIgnoreCase);

    public event Action? Changed;

    /// <summary>Everything known, connected first, then most recently seen. The window
    /// draws this top to bottom.</summary>
    public IReadOnlyList<DeviceStatus> Snapshot()
    {
        lock (_gate)
        {
            return config.Devices
                .Select(d => new DeviceStatus(d.Name, d.Address, d.LastSeen, _connected.ContainsKey(d.Name)))
                .OrderByDescending(d => d.Connected)
                .ThenByDescending(d => d.LastSeen)
                .ToList();
        }
    }

    public bool AnyConnected
    {
        get { lock (_gate) return _connected.Count > 0; }
    }

    /// <summary>
    /// A phone arrived. Older builds don't send a name on the socket, and neither does
    /// anything else that speaks this protocol, so an unnamed client is tracked as
    /// connected but never written to the known list — a row reading "unknown device"
    /// forever is worse than no row.
    /// </summary>
    public void Connected(string? name, string address)
    {
        var device = Clean(name);
        if (device is null) return;

        lock (_gate)
        {
            _connected[device] = _connected.GetValueOrDefault(device) + 1;
            Remember(device, address);
        }
        Changed?.Invoke();
    }

    public void Disconnected(string? name, string address)
    {
        var device = Clean(name);
        if (device is null) return;

        lock (_gate)
        {
            if (_connected.TryGetValue(device, out var count))
            {
                if (count <= 1) _connected.Remove(device);
                else _connected[device] = count - 1;
            }
            // Stamped on the way out too, so "last seen" means the end of the last
            // session rather than the start of it.
            Remember(device, address);
        }
        Changed?.Invoke();
    }

    /// <summary>Called from pairing, which learns the name before any socket exists —
    /// a phone that pairs and is then put down still belongs in the list.</summary>
    public void Seen(string? name, string address)
    {
        var device = Clean(name);
        if (device is null) return;
        lock (_gate) Remember(device, address);
        Changed?.Invoke();
    }

    public void Forget(string name)
    {
        lock (_gate)
        {
            config.Devices.RemoveAll(d => string.Equals(d.Name, name, StringComparison.OrdinalIgnoreCase));
            _connected.Remove(name);
            config.Save();
        }
        Changed?.Invoke();
    }

    /// <summary>Caller holds the lock. Writes the config only when something actually
    /// changed beyond the clock — a phone reconnecting every few minutes should not
    /// rewrite the file every time.</summary>
    private void Remember(string name, string address)
    {
        var existing = config.Devices.FirstOrDefault(d =>
            string.Equals(d.Name, name, StringComparison.OrdinalIgnoreCase));

        if (existing is null)
        {
            config.Devices.Add(new KnownDevice { Name = name, Address = address, LastSeen = DateTimeOffset.Now });
            config.Save();
            return;
        }

        var moved = !string.Equals(existing.Address, address, StringComparison.Ordinal);
        // An hour's granularity on the persisted timestamp: enough for "last seen
        // yesterday" to survive a restart, cheap enough not to touch the disk on a
        // reconnect loop. Read before the write, or the comparison is always against
        // the value just assigned.
        var stale = DateTimeOffset.Now - existing.LastSeen > TimeSpan.FromHours(1);
        existing.Address = address;
        existing.LastSeen = DateTimeOffset.Now;
        if (moved || stale) config.Save();
    }

    private static string? Clean(string? name)
    {
        var trimmed = name?.Trim();
        if (string.IsNullOrEmpty(trimmed)) return null;
        return trimmed.Length > 64 ? trimmed[..64] : trimmed;
    }
}
