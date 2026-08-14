using PortalRemote.Config;
using PortalRemote.Devices;
using Xunit;

namespace PortalRemote.Tests;

public class DeviceRegistryTests : IDisposable
{
    private readonly string _configPath =
        Path.Combine(Path.GetTempPath(), $"portal-remote-test-{Guid.NewGuid():n}.json");

    private ServerConfig NewConfig() => new() { ConfigPath = _configPath };

    public void Dispose()
    {
        if (File.Exists(_configPath)) File.Delete(_configPath);
    }

    [Fact]
    public void RemembersAPhoneAfterItDisconnects()
    {
        var config = NewConfig();
        var registry = new DeviceRegistry(config);

        registry.Connected("Galaxy S26 Ultra", "192.168.0.31");
        Assert.True(registry.Snapshot().Single().Connected);

        registry.Disconnected("Galaxy S26 Ultra", "192.168.0.31");

        // The row survives — a phone that is switched off is still a phone this PC is
        // paired with, which is the entire reason the list is persisted.
        var device = registry.Snapshot().Single();
        Assert.Equal("Galaxy S26 Ultra", device.Name);
        Assert.False(device.Connected);
    }

    [Fact]
    public void ReconnectingDoesNotMarkALivePhoneAsGone()
    {
        var registry = new DeviceRegistry(NewConfig());

        // Android opens the new socket before the old one finishes closing, so the
        // counts overlap. A bool here would report the phone as away while it is on.
        registry.Connected("Redmi Note 8 Pro", "192.168.0.155");
        registry.Connected("Redmi Note 8 Pro", "192.168.0.155");
        registry.Disconnected("Redmi Note 8 Pro", "192.168.0.155");

        Assert.True(registry.Snapshot().Single().Connected);

        registry.Disconnected("Redmi Note 8 Pro", "192.168.0.155");
        Assert.False(registry.Snapshot().Single().Connected);
    }

    [Fact]
    public void OneRowPerPhoneEvenWhenTheAddressChanges()
    {
        var registry = new DeviceRegistry(NewConfig());

        registry.Connected("Pixel 9", "192.168.0.40");
        registry.Disconnected("Pixel 9", "192.168.0.40");
        // Same phone, new lease from the router.
        registry.Connected("Pixel 9", "192.168.0.77");

        var device = Assert.Single(registry.Snapshot());
        Assert.Equal("192.168.0.77", device.Address);
    }

    [Fact]
    public void ConnectedPhonesSortAboveSleepingOnes()
    {
        var registry = new DeviceRegistry(NewConfig());

        registry.Connected("Old phone", "192.168.0.10");
        registry.Disconnected("Old phone", "192.168.0.10");
        registry.Connected("Phone in my hand", "192.168.0.11");

        Assert.Equal(["Phone in my hand", "Old phone"], registry.Snapshot().Select(d => d.Name));
    }

    [Fact]
    public void AnUnnamedClientIsNeverListed()
    {
        var registry = new DeviceRegistry(NewConfig());

        // Anything older than the build that added the header, and anything else
        // speaking this protocol. Better no row than a permanent "unknown device".
        registry.Connected(null, "192.168.0.9");
        registry.Connected("   ", "192.168.0.9");

        Assert.Empty(registry.Snapshot());
        Assert.False(registry.AnyConnected);
    }

    [Fact]
    public void KnownPhonesSurviveARestart()
    {
        var first = NewConfig();
        var registry = new DeviceRegistry(first);
        registry.Connected("Galaxy S26 Ultra", "192.168.0.31");
        registry.Disconnected("Galaxy S26 Ultra", "192.168.0.31");

        // What Load() would find on the next launch.
        var reloaded = System.Text.Json.JsonSerializer.Deserialize<ServerConfig>(File.ReadAllText(_configPath))!;
        var after = new DeviceRegistry(reloaded);

        var device = Assert.Single(after.Snapshot());
        Assert.Equal("Galaxy S26 Ultra", device.Name);
        Assert.False(device.Connected);
    }
}
