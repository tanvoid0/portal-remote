using System.ComponentModel;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Microsoft.Win32;

namespace PortalRemote.Metrics;

/// <summary>
/// What this PC is doing with itself — CPU per core, memory, disks, network
/// throughput and the processes at the top of the list — sampled once a second and
/// pushed down the control socket for the phone's Stats screen.
///
/// Everything here comes from Windows' own primitives rather than a performance
/// counter: <c>NtQuerySystemInformation</c> for processor time, <c>GlobalMemoryStatusEx</c>
/// for memory, <see cref="DriveInfo"/> and <see cref="NetworkInterface"/> for the rest.
/// The alternative — the <c>System.Diagnostics.PerformanceCounter</c> package — is a
/// dependency, takes hundreds of milliseconds to bind a category, and would be the only
/// thing in this app that needs one.
///
/// Sampling is refcounted (<see cref="Subscribe"/>): a monitor that costs 1% of the CPU
/// it is reporting on, permanently, for a screen nobody has open, is the one bug this
/// feature must not ship with. Nothing runs until a phone opens the tab.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed partial class SystemStats : IDisposable
{
    /// <summary>One sample a second. Fast enough that a spike is visible while it is
    /// happening, slow enough that the phone's graph is a minute wide at 60 points.</summary>
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(1);

    /// <summary>How many processes the phone is told about. The list is a glance at
    /// what is eating the machine, not Task Manager.</summary>
    private const int TopProcesses = 5;

    private readonly object _gate = new();
    // Fully qualified: WinForms is in the implicit usings for this project and brings
    // its own Timer, which is a message-loop one and would never tick out here.
    private readonly System.Threading.Timer _timer;

    /// <summary>Per-core processor times from the previous sample; a rate needs two
    /// readings and this is the older one.</summary>
    private ProcessorTimes[] _lastCpu = [];

    private long _lastBytesReceived;
    private long _lastBytesSent;

    /// <summary>What each pid looked like at the previous sample, so a process's share
    /// of the last second can be worked out the same way the CPU's is.</summary>
    private Dictionary<int, ProcessSample> _lastProcesses = new();

    /// <summary>Monotonic reading (not wall clock — an NTP correction mid-sample would
    /// otherwise divide a real delta by a negative second).</summary>
    private long _lastSampleTicks;

    private int _subscribers;
    private bool _disposed;

    /// <summary>The last computed sample, so a phone that subscribes mid-second has
    /// something to draw immediately instead of a blank screen for a second.</summary>
    private object? _latest;

    public SystemStats()
    {
        // Created stopped: Change() starts it when the first subscriber arrives.
        _timer = new System.Threading.Timer(
            _ => Tick(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
    }

    /// <summary>Raised once a second, with a ready-to-serialize payload, while anyone
    /// is subscribed. Fires on a thread-pool thread.</summary>
    public event Action<object>? Changed;

    /// <summary>
    /// Start sampling for one client. Takes the first reading straight away so the
    /// *next* one, a second later, is a real rate rather than a delta against zero —
    /// which would otherwise show every core pinned at 100% for the first frame.
    /// </summary>
    public void Subscribe()
    {
        lock (_gate)
        {
            if (_disposed) return;
            if (_subscribers++ > 0) return;
            Baseline();
            _timer.Change(Interval, Interval);
        }
    }

    /// <summary>Stop sampling for one client. The machine goes quiet again once the
    /// last one leaves.</summary>
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

    /// <summary>The most recent sample, or null if nothing has been sampled since the
    /// last subscriber left.</summary>
    public object? Latest
    {
        get { lock (_gate) return _latest; }
    }

    /// <summary>Read every counter once without publishing, so the next read has
    /// something to subtract from.</summary>
    private void Baseline()
    {
        _lastCpu = ReadProcessorTimes();
        (_lastBytesReceived, _lastBytesSent) = ReadNetworkTotals();
        _lastProcesses = ReadProcesses();
        _lastSampleTicks = Stopwatch.GetTimestamp();
    }

    private void Tick()
    {
        object payload;
        try
        {
            payload = Sample();
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException or IOException)
        {
            // A drive that went away mid-enumeration, a process that exited between
            // being listed and being read. The next second will be fine; a monitor
            // is not worth taking the server down for.
            return;
        }

        lock (_gate)
        {
            if (_subscribers == 0) return;
            _latest = payload;
        }

        Changed?.Invoke(payload);
    }

    /// <summary>Everything, as one message. Called on the timer thread only.</summary>
    private object Sample()
    {
        var now = Stopwatch.GetTimestamp();
        var seconds = (now - _lastSampleTicks) / (double)Stopwatch.Frequency;
        _lastSampleTicks = now;

        var cpu = ReadProcessorTimes();
        var cores = StatMath.CorePercents(_lastCpu, cpu);
        _lastCpu = cpu;

        var (received, sent) = ReadNetworkTotals();
        var down = StatMath.Rate(_lastBytesReceived, received, seconds);
        var up = StatMath.Rate(_lastBytesSent, sent, seconds);
        _lastBytesReceived = received;
        _lastBytesSent = sent;

        var processes = ReadProcesses();
        var top = StatMath.TopByCpu(_lastProcesses, processes, seconds, Environment.ProcessorCount, TopProcesses);
        _lastProcesses = processes;

        MemoryStatus memory = default;
        memory.Length = (uint)Marshal.SizeOf<MemoryStatus>();
        var haveMemory = GlobalMemoryStatusEx(ref memory);

        return new
        {
            t = "sys",
            // The average of the cores rather than a separate whole-machine reading:
            // two calls to the same counter set a second apart would disagree with the
            // bars underneath them, and the bars are the ones being looked at.
            cpu = Math.Round(cores.Length == 0 ? 0 : cores.Average(), 1),
            cores = cores.Select(c => Math.Round(c, 1)).ToArray(),
            cpuName = ProcessorName,
            mem = new
            {
                used = haveMemory ? (long)(memory.TotalPhys - memory.AvailPhys) : 0L,
                total = haveMemory ? (long)memory.TotalPhys : 0L
            },
            net = new { up = Math.Round(up), down = Math.Round(down) },
            disks = ReadDisks(),
            top = top.Select(p => new { name = p.Name, cpu = Math.Round(p.Cpu, 1), mem = p.Memory }).ToArray(),
            uptimeMs = Environment.TickCount64,
            os = OperatingSystemName
        };
    }

    /// <summary>Fixed, ready drives only — a card reader with no card in it is not a
    /// disk this machine is using, and a network drive's free space is another
    /// machine's business.</summary>
    private static object[] ReadDisks()
    {
        var disks = new List<object>();
        foreach (var drive in DriveInfo.GetDrives())
        {
            try
            {
                if (!drive.IsReady || drive.DriveType != DriveType.Fixed) continue;
                disks.Add(new
                {
                    name = drive.Name.TrimEnd('\\'),
                    label = string.IsNullOrWhiteSpace(drive.VolumeLabel) ? null : drive.VolumeLabel,
                    used = drive.TotalSize - drive.TotalFreeSpace,
                    total = drive.TotalSize
                });
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Ejected between the enumeration and the read.
            }
        }
        return disks.ToArray();
    }

    /// <summary>
    /// Bytes in and out across every real adapter. Loopback is excluded (traffic to
    /// this PC's own server would otherwise show up as network activity caused by
    /// watching the network activity), and so are tunnels, which double-count the
    /// physical adapter carrying them.
    ///
    /// The important exclusion is the rest: Windows lists every *filter driver* bound to
    /// an adapter as an adapter of its own — QoS Packet Scheduler, WFP MAC layer, the
    /// VirtualBox lightweight filter — and each one reports the underlying adapter's
    /// counters verbatim. On this machine one Wi-Fi card enumerates six times, so a
    /// naive sum reported six times the real throughput. They are distinguished by
    /// sharing a MAC with the adapter they are bound to, which is what this deduplicates
    /// on; two genuinely separate NICs have separate MACs and are both counted.
    /// </summary>
    private static (long Received, long Sent) ReadNetworkTotals()
    {
        long received = 0, sent = 0;
        var counted = new HashSet<string>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            try
            {
                // An adapter with no hardware address of its own is a software construct
                // we have no way to tell apart from its siblings; the id is the fallback,
                // which at worst counts it once rather than not at all.
                var address = nic.GetPhysicalAddress().ToString();
                if (!counted.Add(string.IsNullOrEmpty(address) ? nic.Id : address)) continue;

                var stats = nic.GetIPStatistics();
                received += stats.BytesReceived;
                sent += stats.BytesSent;
            }
            catch (NetworkInformationException)
            {
                // Adapter disabled mid-enumeration.
            }
        }
        return (received, sent);
    }

    /// <summary>
    /// Every process this account can read, with the two numbers the screen shows.
    ///
    /// ponytail: enumerates every process each second (~5ms on a normal desktop).
    /// If that ever shows up in the numbers this screen is reporting, cache the
    /// handles and refresh the list every few seconds instead of every sample.
    /// </summary>
    private static Dictionary<int, ProcessSample> ReadProcesses()
    {
        var samples = new Dictionary<int, ProcessSample>();
        foreach (var process in Process.GetProcesses())
        {
            try
            {
                samples[process.Id] =
                    new ProcessSample(process.ProcessName, process.TotalProcessorTime, process.WorkingSet64);
            }
            catch (Exception ex) when (ex is Win32Exception or InvalidOperationException or NotSupportedException)
            {
                // Protected (System, Registry, Secure System) or already exited. Both
                // are normal; a list of what is busy doesn't need the ones it can't read.
            }
            finally
            {
                process.Dispose();
            }
        }
        return samples;
    }

    /// <summary>Idle/kernel/user ticks for every logical processor.</summary>
    private static ProcessorTimes[] ReadProcessorTimes()
    {
        var count = Environment.ProcessorCount;
        var size = Marshal.SizeOf<ProcessorPerformanceInformation>();
        var buffer = Marshal.AllocHGlobal(size * count);
        try
        {
            // 8 = SystemProcessorPerformanceInformation. Undocumented in the sense that
            // it is not in the Win32 headers, and unchanged since NT 4 — it is what
            // Task Manager and every "CPU %" library on Windows reads.
            if (NtQuerySystemInformation(8, buffer, size * count, out var written) != 0) return [];

            var read = written / size;
            var times = new ProcessorTimes[read];
            for (var i = 0; i < read; i++)
            {
                var info = Marshal.PtrToStructure<ProcessorPerformanceInformation>(buffer + i * size);
                // KernelTime includes IdleTime, which is why "busy" is not kernel+user.
                times[i] = new ProcessorTimes(info.IdleTime, info.KernelTime + info.UserTime);
            }
            return times;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /// <summary>The CPU's marketing name, which nothing in .NET exposes. Read once —
    /// this machine is not going to swap its processor while the server is up.</summary>
    private static readonly string ProcessorName = ReadProcessorName();

    private static string ReadProcessorName()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(
                @"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            var name = key?.GetValue("ProcessorNameString") as string;
            return string.IsNullOrWhiteSpace(name) ? "Processor" : name.Trim();
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException)
        {
            return "Processor";
        }
    }

    /// <summary>"Windows 11 Pro" rather than the version number, which is what the
    /// phone has room for. Falls back to whatever .NET says.</summary>
    private static readonly string OperatingSystemName = ReadOperatingSystemName();

    private static string ReadOperatingSystemName()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
            var name = key?.GetValue("ProductName") as string;
            // The registry still says "Windows 10" on 11; the build number is how
            // Windows itself tells them apart.
            if (!string.IsNullOrWhiteSpace(name) &&
                int.TryParse(key?.GetValue("CurrentBuildNumber") as string, out var build) && build >= 22000)
                name = name.Replace("Windows 10", "Windows 11", StringComparison.Ordinal);
            return string.IsNullOrWhiteSpace(name) ? RuntimeInformation.OSDescription : name.Trim();
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException)
        {
            return RuntimeInformation.OSDescription;
        }
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

    [LibraryImport("ntdll.dll")]
    private static partial int NtQuerySystemInformation(
        int infoClass, nint buffer, int length, out int returnLength);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GlobalMemoryStatusEx(ref MemoryStatus buffer);

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatus
    {
        public uint Length;
        public uint MemoryLoad;
        public ulong TotalPhys;
        public ulong AvailPhys;
        public ulong TotalPageFile;
        public ulong AvailPageFile;
        public ulong TotalVirtual;
        public ulong AvailVirtual;
        public ulong AvailExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessorPerformanceInformation
    {
        public long IdleTime;
        public long KernelTime;
        public long UserTime;
        public long DpcTime;
        public long InterruptTime;
        public uint InterruptCount;
    }
}

/// <summary>
/// One client's interest in the stats stream.
///
/// The refcount in <see cref="SystemStats"/> is only safe if every increment has a
/// matching decrement, and a phone that walks out of Wi-Fi with the Stats tab open never
/// sends one — so the socket's own lifetime holds this, and dropping the connection
/// releases the sampler whatever the client did or didn't say.
/// </summary>
public sealed class StatsSubscription(SystemStats stats) : IDisposable
{
    private bool _on;

    /// <summary>Follow or stop following. Idempotent — the phone re-sends "on" when the
    /// tab is reopened, and that must not count twice.</summary>
    public void Set(bool on)
    {
        if (on == _on) return;
        _on = on;
        if (on) stats.Subscribe();
        else stats.Unsubscribe();
    }

    public void Dispose() => Set(false);
}

/// <summary>Idle and total 100ns ticks for one logical processor, at one instant.</summary>
public readonly record struct ProcessorTimes(long Idle, long Total);

/// <summary>One process as of one sample: what it is called, how much processor time it
/// had used in its life so far, and what it is holding in RAM.</summary>
public readonly record struct ProcessSample(string Name, TimeSpan Cpu, long Memory);

/// <summary>One process's share of the last second.</summary>
public readonly record struct ProcessLoad(string Name, double Cpu, long Memory);

/// <summary>
/// The arithmetic that turns two readings into a rate, separated from the Windows calls
/// that produce them so it can be tested without a machine in a known state. Every
/// method here is defensive about the same two things: a counter that went backwards
/// (adapter reset, pid reuse) and an elapsed time of zero.
/// </summary>
internal static class StatMath
{
    /// <summary>Busy percentage per core between two readings. Cores that appeared or
    /// vanished between samples (a CPU is not hot-plugged, but a VM's can be) are
    /// dropped rather than reported as a spike.</summary>
    public static double[] CorePercents(ProcessorTimes[] previous, ProcessorTimes[] current)
    {
        var count = Math.Min(previous.Length, current.Length);
        var percents = new double[count];
        for (var i = 0; i < count; i++)
        {
            var total = current[i].Total - previous[i].Total;
            var idle = current[i].Idle - previous[i].Idle;
            percents[i] = total <= 0 ? 0 : Math.Clamp(100.0 * (total - idle) / total, 0, 100);
        }
        return percents;
    }

    /// <summary>Bytes per second between two cumulative readings.</summary>
    public static double Rate(long previous, long current, double seconds)
    {
        if (seconds <= 0) return 0;
        var delta = current - previous;
        // Negative means the counter reset — an adapter came back up, or the machine
        // woke. Zero is the honest answer for that second; a negative bandwidth is not.
        return delta <= 0 ? 0 : delta / seconds;
    }

    /// <summary>
    /// The busiest processes over the interval. A process's percentage is against one
    /// core, capped at 100 × <paramref name="processorCount"/>, matching what Task
    /// Manager's Details tab shows rather than its Processes tab.
    /// </summary>
    public static List<ProcessLoad> TopByCpu(
        Dictionary<int, ProcessSample> previous, Dictionary<int, ProcessSample> current,
        double seconds, int processorCount, int take)
    {
        if (seconds <= 0 || take <= 0) return [];

        var loads = new List<ProcessLoad>(current.Count);
        foreach (var (pid, now) in current)
        {
            // A process that started during this interval has nothing to subtract; it
            // gets its first reading next second rather than crediting it with every
            // cycle it has used since launch. Pids are reused, so a name that changed
            // under one is a different program and is treated as new for the same reason.
            if (!previous.TryGetValue(pid, out var before) || before.Name != now.Name) continue;
            var used = (now.Cpu - before.Cpu).TotalSeconds;
            if (used <= 0) continue;
            var percent = Math.Clamp(100.0 * used / seconds, 0, 100.0 * Math.Max(1, processorCount));
            loads.Add(new ProcessLoad(now.Name, percent, now.Memory));
        }

        return loads.OrderByDescending(p => p.Cpu).Take(take).ToList();
    }
}
