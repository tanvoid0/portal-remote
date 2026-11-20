using System.IO.Pipes;
using System.Text;
using System.Security.Principal;
using System.Text.Json;

namespace PortalRemote.Secure;

/// <summary>
/// The tray app's end of the secure-desktop hand-off: it watches which desktop the PC
/// is actually on, and forwards input to the SYSTEM service only while that desktop is
/// one the tray cannot reach itself.
///
/// The routing rule is what contains the feature. Unlocked, this says no to everything
/// and <c>InputActions.Dispatch</c> runs in-process exactly as it always has, so a PC
/// with no service installed — the default — behaves byte for byte like the build
/// before this existed. The SYSTEM path only carries traffic during the seconds a lock
/// screen or an elevation prompt is up.
///
/// A singleton for the same reason <c>CastHub</c> and <c>PowerTimer</c> are: one PC has
/// one desktop state, and threading it through <c>MapControlEndpoint</c> would touch
/// every call site to say something none of them vary.
/// </summary>
public sealed class SecureDesktopClient
{
    public static SecureDesktopClient Instance { get; } = new();

    private readonly object _writeGate = new();

    /// <summary>
    /// One frame in flight at a time. The mirror's timer is serial, so this only ever
    /// contends when a second phone opens the mirror — and serialising two of them is
    /// both correct and cheaper than correlating replies with request ids.
    /// </summary>
    private readonly SemaphoreSlim _oneFrame = new(1, 1);

    private volatile TaskCompletionSource<byte[]?>? _pending;
    private long _pendingId;
    private long _nextFrameId;
    private volatile bool _active;
    private volatile StreamWriter? _writer;
    private int _started;

    private SecureDesktopClient() { }

    /// <summary>
    /// True while the PC is on a desktop the tray cannot inject into and the service is
    /// there to do it for us. False whenever anything at all is wrong — no service, a
    /// dropped pipe, an ordinary unlocked desktop — which is what makes local injection
    /// the fallback rather than a thing that has to be chosen.
    /// </summary>
    public bool Active => _active;

    /// <summary>
    /// Begin watching. Safe to call more than once; only the first call starts the loop.
    /// Costs nothing on a PC that never installs the service beyond a connect attempt
    /// every few seconds, which is why it needs no config flag to sit behind yet.
    /// </summary>
    public void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0) return;
        _ = Task.Run(WatchAsync);
    }

    /// <summary>
    /// Hand one message to the service, or report that it could not be handed over so
    /// the caller injects locally instead. Returns false rather than throwing for every
    /// failure: a hand-off that did not happen is not an error the phone should see,
    /// it is a reason to do the normal thing.
    /// </summary>
    public bool TryForward(JsonElement message)
    {
        if (!_active) return false;
        var writer = _writer;
        if (writer is null) return false;

        try
        {
            lock (_writeGate) writer.WriteLine(SecureChannel.Line(message));
            return true;
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            // The service went away mid-gesture. Stop claiming the secure desktop is
            // covered; the reconnect loop will put it back if it comes home.
            _active = false;
            return false;
        }
    }

    /// <summary>
    /// One frame of the desktop the helper is attached to, or null when there isn't one to
    /// be had — no service, an ordinary desktop, a capture the lock screen refused, or an
    /// answer that did not arrive. Null always means "use the local capture path", so the
    /// mirror never has to know this exists to keep working.
    /// </summary>
    public async Task<byte[]?> GrabFrameAsync(int? monitor, int width, int quality, CancellationToken ct)
    {
        if (!_active) return null;
        if (!await _oneFrame.WaitAsync(TimeSpan.FromSeconds(2), ct)) return null;

        try
        {
            var writer = _writer;
            if (writer is null || !_active) return null;

            var pending = new TaskCompletionSource<byte[]?>(TaskCreationOptions.RunContinuationsAsynchronously);
            var id = Interlocked.Increment(ref _nextFrameId);
            Interlocked.Exchange(ref _pendingId, id);
            _pending = pending;

            try
            {
                lock (_writeGate) writer.WriteLine(SecureChannel.FrameRequestLine(monitor, width, quality, id));
            }
            catch (Exception ex) when (ex is IOException or ObjectDisposedException)
            {
                _active = false;
                return null;
            }

            // A capture that has not come back within two seconds is not going to be the
            // frame this tick wanted. Give up on it rather than stalling the stream.
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
            deadline.CancelAfter(TimeSpan.FromSeconds(2));
            await using (deadline.Token.Register(() => pending.TrySetResult(null)))
            {
                return await pending.Task;
            }
        }
        finally
        {
            _pending = null;
            _oneFrame.Release();
        }
    }

    private async Task WatchAsync()
    {
        while (true)
        {
            try
            {
                await ReadUntilClosedAsync();
            }
            catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException
                                           or ObjectDisposedException)
            {
                // No service installed is the normal case and looks identical to one
                // that just died, so neither is worth a log line every five seconds.
            }
            finally
            {
                _active = false;
                _writer = null;
                // Nothing is coming back now; a mirror tick waiting on a frame must not
                // sit here until its own timeout.
                _pending?.TrySetResult(null);
            }

            await Task.Delay(TimeSpan.FromSeconds(5));
        }
    }

    private async Task ReadUntilClosedAsync()
    {
        await using var pipe = new NamedPipeClientStream(
            ".", SecureChannel.ControlPipe, PipeDirection.InOut, PipeOptions.Asynchronous,
            // Anonymous: whoever is on the far end must not be able to impersonate this
            // user, and nothing here needs them to.
            TokenImpersonationLevel.Anonymous);

        // Short: this runs on a loop, and a long connect just delays noticing that the
        // service is back.
        await pipe.ConnectAsync(1000);

        // Before a single keystroke crosses: this pipe has to have been made by SYSTEM.
        // Everything forwarded from here is input the user believes is going to their own
        // locked PC, and that includes the password they type to unlock it.
        if (!SecureChannel.ServerIsSystem(pipe)) return;

        var writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
        using var reader = new StreamReader(pipe, new UTF8Encoding(false));
        _writer = writer;

        // Nothing is forwarded until the service says which desktop is live — assuming
        // "Default" here would be assuming the safe answer, but assuming the other one
        // would send the first gesture after connect into a pipe that may not be ready.
        while (await reader.ReadLineAsync() is { } line)
        {
            if (SecureChannel.IsFrame(line))
            {
                // Only if it answers the request actually being waited on. A capture that
                // overran its deadline arrives after the next request has installed its own
                // completion, and handing it over there would show every tick the previous
                // tick's picture from then on.
                if (SecureChannel.ReadId(line) == Interlocked.Read(ref _pendingId))
                {
                    // Null for a frame the helper could not take — the mirror treats that
                    // the same as a local capture failure, which it already holds open for.
                    _pending?.TrySetResult(SecureChannel.ReadFrame(line));
                }

                continue;
            }

            if (SecureChannel.ReadDesktop(line) is { } desktop)
                _active = desktop != SecureChannel.DefaultDesktop;
        }
    }
}
