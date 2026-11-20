using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Text.Json;

namespace PortalRemote.Secure;

/// <summary>
/// The LocalSystem half. It exists for two reasons that between them explain its whole
/// shape: only SYSTEM may open the <c>Winlogon</c> desktop, and a service cannot inject
/// input itself because services live in session 0, which has no desktop anybody is
/// looking at. So this process holds the privilege and spawns
/// <see cref="DesktopHelper"/> into the console session to spend it, relaying between
/// the tray app and that helper.
///
/// It never touches the network. All LAN listening, pairing and token checking stays in
/// the tray app running as the user — moving any of it here would turn the pairing token
/// into a credential that reaches SYSTEM directly, which is the one shape this feature
/// must not have.
/// </summary>
public sealed partial class SecureDesktopService : ServiceBase
{
    private readonly CancellationTokenSource _stopping = new();
    private readonly object _trayGate = new();

    private volatile StreamWriter? _tray;
    private volatile StreamWriter? _helper;
    private volatile string _desktop = SecureChannel.DefaultDesktop;
    private volatile Process? _helperProcess;
    private uint _helperPid;

    private SecureDesktopService() => ServiceName = SecureChannel.ServiceName;

    /// <summary>Entry point for the <c>--secure-desktop-service</c> re-launch.</summary>
    public static int Run()
    {
        ServiceBase.Run(new SecureDesktopService());
        return 0;
    }

    protected override void OnStart(string[] args)
    {
        SecureLog.Write("service: starting");
        var ct = _stopping.Token;
        _ = Task.Run(() => AcceptLoopAsync(SecureChannel.ControlPipe, ServeTrayAsync, ct), ct);
        _ = Task.Run(() => AcceptLoopAsync(SecureChannel.HelperPipe, ServeHelperAsync, ct), ct);
        _ = Task.Run(() => SuperviseAsync(ct), ct);
    }

    protected override void OnStop()
    {
        SecureLog.Write("service: stopping");
        _stopping.Cancel();
        KillHelper();
    }

    /// <summary>
    /// One pipe, one client at a time, rebuilt from scratch for every connection. The
    /// rebuild is deliberate: the DACL names the user currently at the physical console,
    /// and after a fast user switch that is somebody else. Recreating the pipe is a
    /// simpler way to keep that true than mutating the security descriptor of a live one.
    /// </summary>
    private async Task AcceptLoopAsync(string name, Func<NamedPipeServerStream, CancellationToken, Task> serve,
        CancellationToken ct)
    {
        var forConsoleUser = name == SecureChannel.ControlPipe;
        NamedPipeServerStream? pipe = null;
        SecurityIdentifier? granted = null;

        try
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    // The helper pipe deliberately gets no user ACE — both ends of it are
                    // LocalSystem, and a pipe the console user can open is a pipe the
                    // console user can pretend to be the helper on.
                    var user = forConsoleUser ? ConsoleUserSid(ConsoleSession()) : null;

                    // Rebuilt only when the account at the console actually changes — never
                    // just because a client came and went. Disposing between connections
                    // would leave the name unowned for an instant, and a name this process
                    // does not own is one another process can take: the next helper to
                    // connect would then be connecting to them.
                    if (pipe is null || !Equals(user, granted))
                    {
                        pipe?.Dispose();
                        pipe = NamedPipeServerStreamAcl.Create(
                            name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                            PipeOptions.Asynchronous, 0, 0, SecureChannel.Dacl(user));
                        granted = user;
                    }

                    await pipe.WaitForConnectionAsync(ct);
                    await serve(pipe, ct);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    // Losing the name here means somebody else holds it. Both clients
                    // refuse a pipe LocalSystem does not own, so this is noisy rather than
                    // dangerous — but it is worth saying out loud every time.
                    SecureLog.Write($"service: {name} — {ex.Message}");
                    await Task.Delay(TimeSpan.FromSeconds(1), CancellationToken.None);
                }
                finally
                {
                    try
                    {
                        if (pipe is { IsConnected: true }) pipe.Disconnect();
                    }
                    catch (Exception ex) when (ex is IOException or InvalidOperationException
                                                   or ObjectDisposedException)
                    {
                        // Already down; the next loop rebuilds if it has to.
                    }
                }
            }
        }
        finally
        {
            pipe?.Dispose();
        }
    }

    /// <summary>
    /// The tray app: input events up, desktop state down. Every message is checked
    /// against the allowlist here even though the tray checks before sending — this is
    /// the boundary, and a boundary that trusts its caller is not one.
    /// </summary>
    private async Task ServeTrayAsync(NamedPipeServerStream pipe, CancellationToken ct)
    {
        SecureLog.Write("service: tray connected");
        var writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
        var reader = new StreamReader(pipe, new UTF8Encoding(false));
        _tray = writer;

        try
        {
            // Tell it where we are before it sends anything, so its very first decision
            // about where to route is made on fact rather than on a default.
            WriteTray(SecureChannel.DesktopLine(_desktop));

            while (await reader.ReadLineAsync(ct) is { } line)
            {
                if (!Allowed(line)) continue;
                var helper = _helper;
                if (helper is null) continue;
                try
                {
                    await helper.WriteLineAsync(line);
                }
                catch (Exception ex) when (ex is IOException or ObjectDisposedException)
                {
                    _helper = null; // Supervisor will notice the helper is gone.
                }
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or OperationCanceledException)
        {
            // Tray exited or the socket died. Normal.
        }
        finally
        {
            _tray = null;
            SecureLog.Write("service: tray gone");
        }
    }

    /// <summary>The helper: desktop state up, and nothing else is read from it.</summary>
    private async Task ServeHelperAsync(NamedPipeServerStream pipe, CancellationToken ct)
    {
        // The helper pipe's DACL already excludes everyone but SYSTEM, so this check is
        // belt and braces against another SYSTEM process racing our own helper to it —
        // and it is one call, against a pid we already know because we spawned it.
        if (!GetNamedPipeClientProcessId(pipe.SafePipeHandle.DangerousGetHandle(), out var pid) ||
            pid != _helperPid)
        {
            SecureLog.Write($"service: rejected helper pid {pid}, expected {_helperPid}");
            return;
        }

        SecureLog.Write("service: helper connected");
        var writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
        var reader = new StreamReader(pipe, new UTF8Encoding(false));
        _helper = writer;

        try
        {
            while (await reader.ReadLineAsync(ct) is { } line)
            {
                // Frames are relayed verbatim and never inspected — the service has no
                // business decoding a JPEG, and the tray asked for this one.
                if (SecureChannel.IsFrame(line))
                {
                    WriteTray(line);
                    continue;
                }

                if (SecureChannel.ReadDesktop(line) is not { } desktop) continue;
                _desktop = desktop;
                SecureLog.Write($"service: desktop is {desktop}");

                WriteTray(SecureChannel.DesktopLine(desktop));
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or OperationCanceledException)
        {
            // Helper died; the supervisor respawns it.
        }
        finally
        {
            _helper = null;
            // With no helper there is nothing that can reach the secure desktop, so the
            // tray must stop routing to us and go back to injecting locally.
            _desktop = SecureChannel.DefaultDesktop;
            WriteTray(SecureChannel.DesktopLine(_desktop));
            SecureLog.Write("service: helper gone");
        }
    }

    /// <summary>
    /// Every write to the tray goes through here, because two threads reach it: the tray's
    /// own read loop sends the state on connect, and the helper's read loop sends it on
    /// every desktop switch. Two concurrent writes on one StreamWriter interleave, and the
    /// frame they would corrupt is the one the tray routes on — it would start sending
    /// input to SYSTEM when the PC is unlocked, or stop when it is locked. Synchronous
    /// under a lock rather than async: these are one short line into a 64KB pipe buffer.
    /// </summary>
    private void WriteTray(string line)
    {
        var tray = _tray;
        if (tray is null) return;

        try
        {
            lock (_trayGate) tray.WriteLine(line);
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            _tray = null;
        }
    }

    private static bool Allowed(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return false;
            var type = root.TryGetProperty("t", out var t) ? t.GetString() : null;
            // The allowlist plus the mirror's request for a frame. A frame request makes
            // SYSTEM read the screen it is already attached to and hand the pixels back;
            // it injects nothing and names no path, so it widens what crosses the boundary
            // by exactly one verb that cannot act.
            if (type is not null &&
                (SecureChannel.InputVerbs.Contains(type) || type == SecureChannel.FrameRequest))
            {
                return true;
            }
            SecureLog.Write($"service: refused {SecureChannel.Safe(type)}");
            return false;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    /// <summary>
    /// Keep exactly one helper alive in whatever session is currently the physical
    /// console. Polled rather than driven off <c>OnSessionChange</c>: a poll covers the
    /// helper crashing, the session changing and there being no session at all with one
    /// piece of logic, where the event would only cover the middle one.
    /// </summary>
    // ponytail: 2s poll; wire OnSessionChange if a lock/unlock needs to be covered faster
    // than a person can pick the phone up.
    private async Task SuperviseAsync(CancellationToken ct)
    {
        var session = uint.MaxValue;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                var current = ConsoleSession();
                var process = _helperProcess;
                var dead = process is null || process.HasExited;

                if (current != uint.MaxValue && (dead || current != session))
                {
                    if (!dead) KillHelper();
                    session = current;
                    Spawn(current);
                }
                else if (current == uint.MaxValue && !dead)
                {
                    // Nobody at the console — RDP, or between logons. A helper in a
                    // session that is no longer the console one injects into nothing.
                    KillHelper();
                    session = uint.MaxValue;
                }
            }
            catch (Exception ex) when (ex is InvalidOperationException or ArgumentException
                                           or System.ComponentModel.Win32Exception)
            {
                // ArgumentException included deliberately: Process.GetProcessById throws it
                // for a pid that has already exited. This loop is fire-and-forget, so an
                // escape here is silent and permanent — no helper is ever spawned again and
                // the only symptom is a lock screen that stopped answering.
                SecureLog.Write($"service: supervise — {ex.Message}");
            }

            await Task.Delay(TimeSpan.FromSeconds(2), ct).ContinueWith(_ => { }, TaskScheduler.Default);
        }
    }

    /// <summary>
    /// Start the helper as LocalSystem inside the console session. The token is
    /// borrowed from <c>winlogon.exe</c> because that process is, by definition, already
    /// SYSTEM in exactly the session we want to land in — which saves constructing a
    /// token and getting its session, privileges and integrity level right by hand.
    /// </summary>
    private void Spawn(uint session)
    {
        var exe = Environment.ProcessPath;
        if (exe is null) return;

        using var winlogon = Process.GetProcessesByName("winlogon").FirstOrDefault(p => p.SessionId == (int)session);
        if (winlogon is null)
        {
            SecureLog.Write($"service: no winlogon in session {session}");
            return;
        }

        nint token = 0, duplicate = 0, environment = 0;
        try
        {
            if (!OpenProcessToken(winlogon.Handle, TokenDuplicate | TokenQuery, out token)) return;
            if (!DuplicateTokenEx(token, MaximumAllowed, 0, SecurityIdentification, TokenPrimary, out duplicate))
                return;

            // Without an environment block the helper gets none at all, and .NET wants
            // at least a usable TEMP and SystemRoot to start.
            CreateEnvironmentBlock(out environment, duplicate, false);

            var startup = new StartupInfo
            {
                cb = Marshal.SizeOf<StartupInfo>(),
                // The desktop the process is *created* on. It has to be a real one in
                // WinSta0 for the process to start at all; the helper immediately
                // re-attaches its own thread to whichever desktop has input.
                lpDesktop = @"winsta0\default"
            };

            var command = new StringBuilder($"\"{exe}\" {SecureChannel.HelperArg}");

            if (!CreateProcessAsUser(duplicate, null, command, 0, 0, false,
                    CreateUnicodeEnvironment | CreateNoWindow, environment, null, ref startup, out var info))
            {
                SecureLog.Write($"service: CreateProcessAsUser failed ({Marshal.GetLastWin32Error()})");
                return;
            }

            CloseHandle(info.hThread);
            CloseHandle(info.hProcess);

            // Set before the helper can possibly have connected, which is what makes the
            // pid check on the helper pipe meaningful.
            _helperPid = info.dwProcessId;
            try
            {
                _helperProcess = Process.GetProcessById((int)info.dwProcessId);
                SecureLog.Write($"service: helper {info.dwProcessId} in session {session}");
            }
            catch (ArgumentException)
            {
                // Started and died before we could take a handle — a bad image, or an
                // antivirus that took exception to a SYSTEM process injecting input. Leave
                // it null so the next supervisor tick treats it as dead and tries again.
                _helperProcess = null;
                SecureLog.Write("service: helper exited immediately");
            }
        }
        finally
        {
            if (environment != 0) DestroyEnvironmentBlock(environment);
            if (duplicate != 0) CloseHandle(duplicate);
            if (token != 0) CloseHandle(token);
        }
    }

    private void KillHelper()
    {
        var process = _helperProcess;
        _helperProcess = null;
        _helperPid = 0;
        if (process is null) return;

        try
        {
            if (!process.HasExited) process.Kill();
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception
                                       or NotSupportedException)
        {
            // Already gone.
        }
        finally
        {
            process.Dispose();
        }
    }

    /// <summary>The session physically at the keyboard, or <c>uint.MaxValue</c> when there isn't one.</summary>
    private static uint ConsoleSession() => WTSGetActiveConsoleSessionId();

    /// <summary>
    /// Who is logged in at the console, as a SID for the pipe's DACL. Resolved through
    /// the account name rather than by opening the session's token: it is two calls
    /// instead of five and needs no handle to leak.
    /// </summary>
    private static SecurityIdentifier? ConsoleUserSid(uint session)
    {
        if (session == uint.MaxValue) return null;

        var user = SessionString(session, WtsUserName);
        if (string.IsNullOrEmpty(user)) return null; // Logon screen with nobody signed in.
        var domain = SessionString(session, WtsDomainName);

        try
        {
            var account = string.IsNullOrEmpty(domain) ? new NTAccount(user) : new NTAccount(domain, user);
            return (SecurityIdentifier)account.Translate(typeof(SecurityIdentifier));
        }
        catch (Exception ex) when (ex is IdentityNotMappedException or SystemException)
        {
            SecureLog.Write($"service: cannot map {domain}\\{user}");
            return null;
        }
    }

    private static string SessionString(uint session, int info)
    {
        if (!WTSQuerySessionInformation(0, session, info, out var buffer, out _)) return "";
        try
        {
            return Marshal.PtrToStringUni(buffer) ?? "";
        }
        finally
        {
            WTSFreeMemory(buffer);
        }
    }

    private const int WtsUserName = 5;
    private const int WtsDomainName = 7;
    private const uint TokenDuplicate = 0x0002;
    private const uint TokenQuery = 0x0008;
    private const uint MaximumAllowed = 0x02000000;
    private const int SecurityIdentification = 2;
    private const int TokenPrimary = 1;
    private const uint CreateUnicodeEnvironment = 0x00000400;
    private const uint CreateNoWindow = 0x08000000;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int cb;
        public string? lpReserved;
        public string? lpDesktop;
        public string? lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public nint lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        public nint hProcess;
        public nint hThread;
        public uint dwProcessId;
        public uint dwThreadId;
    }

    [LibraryImport("kernel32.dll")]
    private static partial uint WTSGetActiveConsoleSessionId();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(nint handle);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetNamedPipeClientProcessId(nint pipe, out uint processId);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool OpenProcessToken(nint process, uint access, out nint token);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DuplicateTokenEx(nint token, uint access, nint attributes,
        int impersonationLevel, int tokenType, out nint duplicate);

    [LibraryImport("userenv.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CreateEnvironmentBlock(out nint environment, nint token,
        [MarshalAs(UnmanagedType.Bool)] bool inherit);

    [LibraryImport("userenv.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyEnvironmentBlock(nint environment);

    [LibraryImport("wtsapi32.dll")]
    private static partial void WTSFreeMemory(nint memory);

    // StringBuilder and struct-by-ref marshalling: DllImport, same reason as WinInput's
    // GetWindowText. CreateProcessAsUser is documented as being allowed to modify the
    // command line buffer it is handed, so it must not be given an interned string.
    [DllImport("wtsapi32.dll", EntryPoint = "WTSQuerySessionInformationW", CharSet = CharSet.Unicode,
        SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WTSQuerySessionInformation(nint server, uint session, int infoClass,
        out nint buffer, out uint bytes);

    [DllImport("advapi32.dll", EntryPoint = "CreateProcessAsUserW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcessAsUser(nint token, string? applicationName,
        StringBuilder commandLine, nint processAttributes, nint threadAttributes,
        [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint creationFlags, nint environment,
        string? currentDirectory, ref StartupInfo startupInfo, out ProcessInformation processInformation);
}
