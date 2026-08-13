namespace PortalRemote.Input;

/// <summary>
/// One pending power action, scheduled by the phone's power menu timer. Restart and
/// shutdown hand off to the OS's own countdown (<see cref="Power.ApplyDelayed"/>) —
/// the same call <see cref="Power.Apply"/> already made, just with a delay instead of
/// "now" — which keeps counting even if this process exits. Lock, sleep and screen-off
/// have no such native delayed form, so those three are timed in-process instead.
///
/// ponytail: the in-process half dies if the tray app quits before it fires. Upgrade
/// to a scheduled task (schtasks) if that ever needs to survive the app itself
/// restarting — not needed while the phone can't reach the PC without it running anyway.
///
/// One process, one desktop, one user: a static instance, like <c>CastHub</c>.
/// </summary>
public sealed class PowerTimer
{
    public static readonly PowerTimer Instance = new();

    /// <summary>Longest delay accepted — a typo away from "never" is not a feature.</summary>
    private const int MaxSeconds = 24 * 60 * 60;

    private readonly object gate = new();
    private System.Threading.Timer? timer;
    private string? mode;
    private DateTimeOffset? endsAt;

    /// <summary>Raised whenever <see cref="Snapshot"/> would answer differently — set,
    /// cancelled, edited or fired. Wired to the share hub's broadcast in
    /// <c>Program.cs</c>, exactly like <c>CastHub.Changed</c>, so every paired phone
    /// (and a phone that only just reconnected) agrees on what's pending.</summary>
    public event Action<object>? Changed;

    /// <summary>What every connected phone should show: the pending mode and when it
    /// fires, or both null when nothing is scheduled.</summary>
    public object Snapshot()
    {
        lock (gate)
            return new { t = "power_timer", mode, endsAt = endsAt?.ToUnixTimeMilliseconds() };
    }

    /// <summary>Schedule (or reschedule) one power action. Overwriting a pending timer
    /// is how the phone "edits" one — cancel-then-set from the caller's side, one call
    /// from this one.</summary>
    public void Set(string mode, int seconds)
    {
        if (!Power.Modes.Contains(mode))
            throw new UnknownMessageException($"unknown power mode: {mode}");
        if (seconds is <= 0 or > MaxSeconds)
            throw new UnknownMessageException($"power_timer_set needs 'seconds' between 1 and {MaxSeconds}");

        lock (gate)
        {
            // Clear whatever was pending first: leaving an old in-process timer running
            // would double-fire it, and re-issuing "shutdown /t" while one is already
            // in progress fails outright instead of replacing it.
            CancelLocked();

            if (mode is "restart" or "shutdown")
            {
                Power.ApplyDelayed(mode, seconds);
            }
            else
            {
                timer = new System.Threading.Timer(
                    _ => Fire(mode), null, TimeSpan.FromSeconds(seconds), Timeout.InfiniteTimeSpan);
            }

            this.mode = mode;
            endsAt = DateTimeOffset.Now.AddSeconds(seconds);
        }
        Publish();
    }

    /// <summary>Cancel the pending action, if any. A no-op — not an error — when
    /// nothing is scheduled, since the phone only offers this button while something is.</summary>
    public void Cancel()
    {
        lock (gate)
        {
            if (mode is null) return;
            CancelLocked();
            mode = null;
            endsAt = null;
        }
        Publish();
    }

    /// <summary>Caller holds <see cref="gate"/>.</summary>
    private void CancelLocked()
    {
        if (mode is "restart" or "shutdown") Power.CancelDelayed();
        timer?.Dispose();
        timer = null;
    }

    private void Fire(string mode)
    {
        lock (gate)
        {
            timer?.Dispose();
            timer = null;
            this.mode = null;
            endsAt = null;
        }
        try
        {
            Power.Apply(mode);
        }
        catch
        {
            // Best-effort, same as CastHub's fire-and-forget sends: this runs on a
            // thread-pool thread with nobody left to hand the failure to.
        }
        finally
        {
            Publish();
        }
    }

    private void Publish() => Changed?.Invoke(Snapshot());
}
