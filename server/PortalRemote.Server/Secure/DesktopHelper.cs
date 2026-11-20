using System.Collections.Concurrent;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Security.Principal;
using System.Text.Json;
using PortalRemote.Input;
using PortalRemote.Mirror;

namespace PortalRemote.Secure;

/// <summary>
/// The half that can actually reach the lock screen. Runs as LocalSystem inside the
/// console session — spawned there by <see cref="SecureDesktopService"/>, because a
/// service's own session 0 has no desktop a person ever sees — and spends its life
/// following whichever desktop Windows currently has input on.
///
/// The load-bearing detail is that both <c>SendInput</c> and a GDI screen grab act on
/// the desktop of the <i>calling thread</i>, not of the process. So the two threads that
/// do real work here each attach themselves with <c>SetThreadDesktop</c> and keep
/// following: one injects, one captures. Neither can borrow the other's attachment, and
/// the pipe reader does neither job — it only sorts arrivals onto the right queue.
/// Injecting from the reader would land the event on whatever desktop that thread last
/// happened to be attached to, which is a bug that looks like flaky hardware.
/// </summary>
public static partial class DesktopHelper
{
    private const uint GenericAll = 0x10000000;
    private const int UoiName = 2;

    /// <summary>How long the injector waits for work before looking at the desktop again.</summary>
    // ponytail: 50ms poll, so a desktop switch is noticed within ~50ms and idle cost is one
    // OpenInputDesktop per tick. A WinSta0 desktop-switch hook would be exact; this is cheaper,
    // and the switch only has to be noticed faster than a person can react to it.
    private const int IdleMs = 50;

    private static readonly BlockingCollection<string> Queue = new(new ConcurrentQueue<string>());

    /// <summary>
    /// Frame requests, deliberately not on the input queue. A capture costs tens of
    /// milliseconds and the injector must never be sitting inside one while a mouse move
    /// waits behind it — on a lock screen that reads as input lag, not as a slow mirror.
    /// </summary>
    private static readonly BlockingCollection<string> Frames = new(new ConcurrentQueue<string>());

    /// <summary>Two worker threads share the one pipe, and two concurrent writes interleave.</summary>
    private static readonly object WriteGate = new();

    /// <summary>
    /// Entry point for the <c>--secure-desktop-helper</c> re-launch. Returns when the
    /// service drops the pipe, which is the signal to die: the service respawns a fresh
    /// helper into whatever session is the console one now, and that is cheaper than
    /// teaching this process to migrate between sessions.
    /// </summary>
    public static int Run()
    {
        SecureLog.Write("helper: starting");

        try
        {
            using var pipe = new NamedPipeClientStream(
                ".", SecureChannel.HelperPipe, PipeDirection.InOut, PipeOptions.Asynchronous,
                TokenImpersonationLevel.Anonymous);
            pipe.Connect(10_000);

            // This process is SYSTEM and does what it is told, so being told by the right
            // thing is the only protection there is. A pipe owned by anyone else is
            // somebody who won the name asking to have keystrokes injected for them.
            if (!SecureChannel.ServerIsSystem(pipe))
            {
                SecureLog.Write("helper: refusing a pipe LocalSystem does not own");
                return 1;
            }

            var writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
            var reader = new StreamReader(pipe, new UTF8Encoding(false));

            // Reader thread enqueues only. See the class remark for why it must not inject.
            var pump = new Thread(() =>
            {
                try
                {
                    while (reader.ReadLine() is { } line)
                    {
                        if (SecureChannel.TypeOf(line) == SecureChannel.FrameRequest) Frames.Add(line);
                        else Queue.Add(line);
                    }
                }
                catch (Exception ex) when (ex is IOException or ObjectDisposedException)
                {
                    // Service gone; Follow notices via the completed queue.
                }
                finally
                {
                    Queue.CompleteAdding();
                    Frames.CompleteAdding();
                }
            }) { IsBackground = true, Name = "secure-desktop-pipe" };
            pump.Start();

            // Its own thread, and its own attachment to the desktop: SetThreadDesktop is
            // per-thread, so the capture has to make the same journey to Winlogon that the
            // injector does, and cannot borrow the injector's.
            var camera = new Thread(() => Capture(writer))
                { IsBackground = true, Name = "secure-desktop-capture" };
            camera.Start();

            Follow(writer);
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException)
        {
            SecureLog.Write("helper: pipe failed — " + ex.Message);
            return 1;
        }
        finally
        {
            // A phone that was holding Shift when the pipe died must not leave the PC
            // shifted forever. Same reasoning as the control socket's close path; this
            // is the only place that knows what this process pressed.
            InputActions.ReleaseHeld();
            SecureLog.Write("helper: exiting");
        }

        return 0;
    }

    /// <summary>
    /// The one thread that is allowed to inject: attach to the live desktop, then drain
    /// whatever the reader has queued onto it.
    /// </summary>
    private static void Follow(TextWriter writer)
    {
        var current = "";
        nint attached = 0;

        try
        {
            while (!Queue.IsCompleted)
            {
                if (Reattach(ref attached, ref current))
                {
                    // Anything still held belongs to the desktop we just left, and the
                    // key-up for it will never arrive there — the phone's release goes to
                    // wherever it is now. Without this, one Ctrl held across a lock leaves
                    // that desktop Ctrl'd for the life of this process.
                    InputActions.ReleaseHeld();
                    SecureLog.Write("helper: attached to " + current);
                    // The tray routes on this, so it is told before any input is drained
                    // rather than after — otherwise the first gesture on a lock screen
                    // races the notice that the lock screen exists.
                    if (!Say(writer, SecureChannel.DesktopLine(current))) return;
                }

                if (!Queue.TryTake(out var line, IdleMs)) continue;
                Inject(line);
                // Drain the rest of the burst before looking at the desktop again: a
                // trackpad flick is dozens of messages and they should not be paced by
                // the poll interval.
                while (Queue.TryTake(out line, 0)) Inject(line);
            }
        }
        catch (InvalidOperationException)
        {
            // CompleteAdding raced a TryTake. Normal shutdown.
        }
        finally
        {
            if (attached != 0) CloseDesktop(attached);
        }
    }

    /// <summary>
    /// Point this thread at the desktop that currently has input, if it isn't already.
    /// True when it changed, which is the caller's cue to report it upward.
    ///
    /// <c>OpenInputDesktop</c> is what makes the whole feature work and what makes it
    /// need SYSTEM: the <c>Winlogon</c> desktop's own ACL grants nobody else, so the
    /// same call from the tray app fails rather than returning a handle that misbehaves.
    /// </summary>
    private static bool Reattach(ref nint attached, ref string current)
    {
        var desktop = OpenInputDesktop(0, false, GenericAll);
        if (desktop == 0) return false; // Mid-switch; the next tick gets it.

        var name = DesktopName(desktop);
        if (name.Length == 0 || name == current)
        {
            CloseDesktop(desktop);
            return false;
        }

        if (!SetThreadDesktop(desktop))
        {
            CloseDesktop(desktop);
            return false;
        }

        // Only now is the old handle safe to drop — it was backing this thread until
        // the line above.
        if (attached != 0) CloseDesktop(attached);
        attached = desktop;
        current = name;
        return true;
    }

    private static void Inject(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var message = document.RootElement;
            if (message.ValueKind != JsonValueKind.Object) return;

            // The second of the two allowlist checks. The tray already filtered; this
            // one is what holds if the tray is ever not the thing on the other end.
            var type = message.TryGetProperty("t", out var t) ? t.GetString() : null;
            if (type is null || !SecureChannel.InputVerbs.Contains(type))
            {
                SecureLog.Write("helper: refused " + SecureChannel.Safe(type));
                return;
            }

            InputActions.Dispatch(message);
        }
        catch (Exception ex) when (ex is JsonException or UnknownMessageException
                                       or ArgumentException or InvalidOperationException
                                       or System.ComponentModel.Win32Exception)
        {
            // One bad message must not take down the only process that can reach the
            // lock screen — the phone would look dead with no way to say why.
            SecureLog.Write("helper: dispatch failed — " + ex.Message);
        }
    }

    /// <summary>
    /// The mirror's end: attach to the live desktop and answer frame requests from it.
    /// Failures are answered rather than swallowed — the mirror needs to tell "the lock
    /// screen refuses to be captured" from "nothing came back", because the first is
    /// worth falling back for and the second is worth waiting through.
    /// </summary>
    private static void Capture(TextWriter writer)
    {
        var current = "";
        nint attached = 0;

        try
        {
            while (!Frames.IsCompleted)
            {
                if (!Frames.TryTake(out var line, IdleMs)) continue;
                // Only the newest request is worth serving: any that piled up behind a
                // running capture are already later than the frame they asked for.
                while (Frames.TryTake(out var newer, 0)) line = newer;

                Reattach(ref attached, ref current);

                var (monitor, width, quality) = ReadFrameRequest(line);
                var id = SecureChannel.ReadId(line) ?? 0;
                try
                {
                    var jpeg = ScreenCapture.Jpeg(monitor, null, width, quality);
                    if (!Say(writer, SecureChannel.FrameLine(jpeg, id))) return;
                }
                catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or ExternalException
                                               or InvalidOperationException or ArgumentException)
                {
                    // GDI can still refuse the secure desktop outright on some machines.
                    // If this is what the log fills up with, Desktop Duplication is the
                    // upgrade — see ScreenCapture's own remark.
                    SecureLog.Write("helper: capture failed — " + ex.Message);
                    if (!Say(writer, SecureChannel.FrameEmptyLine(id))) return;
                }
            }
        }
        catch (InvalidOperationException)
        {
            // CompleteAdding raced a TryTake. Normal shutdown.
        }
        finally
        {
            if (attached != 0) CloseDesktop(attached);
        }
    }

    private static (int? Monitor, int Width, int Quality) ReadFrameRequest(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            var monitor = root.TryGetProperty("mon", out var m) && m.ValueKind == JsonValueKind.Number
                ? m.GetInt32() : (int?)null;
            var width = root.TryGetProperty("w", out var w) && w.ValueKind == JsonValueKind.Number
                ? w.GetInt32() : 1280;
            var quality = root.TryGetProperty("q", out var q) && q.ValueKind == JsonValueKind.Number
                ? q.GetInt32() : 60;
            return (monitor, width, quality);
        }
        catch (JsonException)
        {
            return (null, 1280, 60);
        }
    }

    /// <summary>
    /// Every write to the pipe goes through here: the injector reports desktop changes
    /// and the capture thread answers frames, and two concurrent writes on one
    /// StreamWriter would interleave a 100KB frame with the desktop notice the tray
    /// routes on. False means the pipe is gone and the caller should stop.
    /// </summary>
    private static bool Say(TextWriter writer, string line)
    {
        try
        {
            lock (WriteGate) writer.WriteLine(line);
            return true;
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            return false;
        }
    }

    private static string DesktopName(nint desktop)
    {
        var buffer = new byte[256];
        return GetUserObjectInformation(desktop, UoiName, buffer, (uint)buffer.Length, out var needed)
            ? Encoding.Unicode.GetString(buffer, 0, Math.Max(0, (int)needed - 2))
            : "";
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    private static partial nint OpenInputDesktop(uint flags, [MarshalAs(UnmanagedType.Bool)] bool inherit,
        uint desiredAccess);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetThreadDesktop(nint desktop);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseDesktop(nint desktop);

    // Byte-buffer marshalling with an out length is clearer as DllImport here, same
    // call shape as WinInput's GetWindowText.
    [DllImport("user32.dll", EntryPoint = "GetUserObjectInformationW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetUserObjectInformation(nint handle, int index, byte[] info, uint length,
        out uint lengthNeeded);
}
