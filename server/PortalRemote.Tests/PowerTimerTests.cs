using System.Text.Json;
using PortalRemote.Input;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// <see cref="PowerTimer"/>'s state machine — set, edit (re-set), cancel, and the
/// validation guarding both. Every test builds its own instance rather than touching
/// <see cref="PowerTimer.Instance"/> — the same reason <c>ShareHubTests</c> builds its
/// own <c>ShareHub</c> — and only ever schedules "lock" or "screen_off" an hour out, so
/// nothing here can reach a real Win32 call: "restart"/"shutdown" are checked only
/// through <see cref="Power.Modes"/> membership, never through <see cref="PowerTimer.Set"/>,
/// which would hand a real countdown to <c>shutdown.exe</c>.
/// </summary>
public class PowerTimerTests
{
    /// <summary>An hour out — nowhere near firing before a test (or its instance) is done.</summary>
    private const int FarFuture = 3600;

    private static JsonElement Snapshot(PowerTimer timer) =>
        JsonDocument.Parse(JsonSerializer.Serialize(timer.Snapshot())).RootElement;

    [Fact]
    public void NothingPendingByDefault()
    {
        var snapshot = Snapshot(new PowerTimer());
        Assert.Equal(JsonValueKind.Null, snapshot.GetProperty("mode").ValueKind);
        Assert.Equal(JsonValueKind.Null, snapshot.GetProperty("endsAt").ValueKind);
    }

    [Fact]
    public void UnknownModeIsRejected()
    {
        Assert.Throws<UnknownMessageException>(() => new PowerTimer().Set("nonsense", FarFuture));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-5)]
    [InlineData(25 * 60 * 60)] // over the 24h cap
    public void OutOfRangeSecondsAreRejected(int seconds)
    {
        Assert.Throws<UnknownMessageException>(() => new PowerTimer().Set("lock", seconds));
    }

    [Fact]
    public void SetReportsAPendingCountdown()
    {
        var timer = new PowerTimer();
        var before = DateTimeOffset.Now;

        timer.Set("lock", FarFuture);

        var snapshot = Snapshot(timer);
        Assert.Equal("lock", snapshot.GetProperty("mode").GetString());
        Assert.True(snapshot.GetProperty("endsAt").GetInt64() >= before.AddSeconds(FarFuture).ToUnixTimeMilliseconds());
    }

    [Fact]
    public void SettingAgainReplacesRatherThanStacks()
    {
        var timer = new PowerTimer();

        timer.Set("lock", FarFuture);
        timer.Set("screen_off", FarFuture); // the phone's "edit" is just a second Set

        Assert.Equal("screen_off", Snapshot(timer).GetProperty("mode").GetString());
    }

    [Fact]
    public void CancelClearsIt()
    {
        var timer = new PowerTimer();
        timer.Set("lock", FarFuture);

        timer.Cancel();

        var snapshot = Snapshot(timer);
        Assert.Equal(JsonValueKind.Null, snapshot.GetProperty("mode").ValueKind);
        Assert.Equal(JsonValueKind.Null, snapshot.GetProperty("endsAt").ValueKind);
    }

    [Fact]
    public void CancelWithNothingPendingIsANoOp()
    {
        new PowerTimer().Cancel(); // must not throw, and must not publish a change
    }

    [Fact]
    public void ChangedFiresOnSetAndOnCancel()
    {
        var timer = new PowerTimer();
        var fired = 0;
        timer.Changed += _ => fired++;

        timer.Set("lock", FarFuture);
        timer.Cancel();

        Assert.Equal(2, fired);
    }
}
