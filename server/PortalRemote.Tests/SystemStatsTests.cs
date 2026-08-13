using System.Text.Json;
using PortalRemote.Metrics;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The arithmetic behind the phone's Stats screen, plus one end-to-end sample of the
/// real machine.
///
/// The delta math is here because it is the kind of mistake that looks right: a rate
/// computed against a counter that reset, or a core percentage taken from
/// kernel+user without subtracting idle, produces a plausible number that is simply
/// wrong, and no screenshot review catches "23% should have been 4%".
///
/// <see cref="SamplesThisMachine"/> is the other half: <c>NtQuerySystemInformation</c>
/// is called with a hand-written struct, and a field in the wrong place there is not a
/// compile error — it is silently garbage percentages forever.
/// </summary>
public class SystemStatsTests
{
    private static Dictionary<int, ProcessSample> Processes(params (int Pid, string Name, double Seconds)[] entries) =>
        entries.ToDictionary(e => e.Pid, e => new ProcessSample(e.Name, TimeSpan.FromSeconds(e.Seconds), 1024));

    [Fact]
    public void CorePercentIsBusyTimeNotTotalTime()
    {
        // One second of wall clock on two cores: the first spent 750ms idle, the
        // second was pinned.
        ProcessorTimes[] before = [new(Idle: 0, Total: 0), new(Idle: 0, Total: 0)];
        ProcessorTimes[] after = [new(Idle: 7_500_000, Total: 10_000_000), new(Idle: 0, Total: 10_000_000)];

        var percents = StatMath.CorePercents(before, after);

        Assert.Equal(25, percents[0], 3);
        Assert.Equal(100, percents[1], 3);
    }

    [Fact]
    public void AnIdleCoreReadsZeroRatherThanNothing()
    {
        ProcessorTimes[] before = [new(Idle: 1_000, Total: 1_000)];
        ProcessorTimes[] after = [new(Idle: 11_000, Total: 11_000)];

        Assert.Equal(0, StatMath.CorePercents(before, after)[0], 3);
    }

    [Fact]
    public void CoresThatVanishBetweenSamplesAreDropped()
    {
        ProcessorTimes[] before = [new(0, 0), new(0, 0)];
        ProcessorTimes[] after = [new(0, 10_000_000)];

        Assert.Single(StatMath.CorePercents(before, after));
    }

    [Fact]
    public void ThroughputIsBytesPerSecond()
    {
        Assert.Equal(2_000, StatMath.Rate(previous: 1_000, current: 5_000, seconds: 2), 3);
    }

    [Fact]
    public void ACounterThatResetReadsZeroNotNegative()
    {
        // An adapter that came back up starts its byte count again; the honest answer
        // for that second is "no idea", and a negative bandwidth is not it.
        Assert.Equal(0, StatMath.Rate(previous: 9_000, current: 12, seconds: 1), 3);
        Assert.Equal(0, StatMath.Rate(previous: 0, current: 500, seconds: 0), 3);
    }

    [Fact]
    public void TopProcessesAreRankedByTimeUsedInTheInterval()
    {
        var before = Processes((10, "busy", 100), (11, "quiet", 100));
        var after = Processes((10, "busy", 100.5), (11, "quiet", 100.01));

        var top = StatMath.TopByCpu(before, after, seconds: 1, processorCount: 8, take: 5);

        Assert.Equal(["busy", "quiet"], top.Select(p => p.Name));
        Assert.Equal(50, top[0].Cpu, 3);
    }

    [Fact]
    public void AProcessSeenForTheFirstTimeIsNotCreditedWithItsWholeLife()
    {
        // Firefox has been up for an hour when the tab is opened. Subtracting nothing
        // from an hour of processor time would put it at 100% of every core, forever,
        // on its first appearance in the list.
        var before = Processes((10, "old", 100));
        var after = Processes((10, "old", 100.1), (99, "firefox", 3_600));

        var top = StatMath.TopByCpu(before, after, seconds: 1, processorCount: 8, take: 5);

        Assert.Equal(["old"], top.Select(p => p.Name));
    }

    [Fact]
    public void APidReusedByADifferentProgramStartsAgain()
    {
        var before = Processes((10, "gone", 500));
        var after = Processes((10, "new", 2));

        Assert.Empty(StatMath.TopByCpu(before, after, seconds: 1, processorCount: 8, take: 5));
    }

    [Fact]
    public async Task SamplesThisMachine()
    {
        using var stats = new SystemStats();
        var arrived = new TaskCompletionSource<object>(TaskCreationOptions.RunContinuationsAsynchronously);
        stats.Changed += payload => arrived.TrySetResult(payload);

        stats.Subscribe();
        var message = await arrived.Task.WaitAsync(TimeSpan.FromSeconds(10));
        stats.Unsubscribe();

        using var json = JsonDocument.Parse(JsonSerializer.Serialize(message));
        var root = json.RootElement;

        Assert.Equal("sys", root.GetProperty("t").GetString());

        // The core count is the check that matters: it is read back out of the buffer
        // NtQuerySystemInformation filled, so a struct whose size is wrong reports the
        // wrong number of processors (or none at all) long before anyone questions a
        // percentage.
        var cores = root.GetProperty("cores");
        Assert.Equal(Environment.ProcessorCount, cores.GetArrayLength());
        Assert.All(cores.EnumerateArray(), core => Assert.InRange(core.GetDouble(), 0, 100));
        Assert.InRange(root.GetProperty("cpu").GetDouble(), 0, 100);

        var memory = root.GetProperty("mem");
        var total = memory.GetProperty("total").GetInt64();
        Assert.True(total > 0, "GlobalMemoryStatusEx reported no physical memory");
        Assert.InRange(memory.GetProperty("used").GetInt64(), 1, total);

        Assert.NotEmpty(root.GetProperty("cpuName").GetString()!);
        Assert.True(root.GetProperty("uptimeMs").GetInt64() > 0);
        // A Windows machine has at least one fixed drive.
        Assert.True(root.GetProperty("disks").GetArrayLength() > 0);
    }
}
