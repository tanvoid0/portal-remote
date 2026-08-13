namespace PortalRemote.Cast;

/// <summary>
/// What a receiver can actually be asked to do — step 4c of
/// <c>docs/phase4-casting.md</c>. Sent to the phone with the target list so the UI can
/// hide a scrub bar the device would ignore, rather than offering control that silently
/// does nothing.
/// </summary>
/// <param name="Seek">Absolute seek. False for anything that only has "skip forward".</param>
/// <param name="Volume">Absolute volume level, 0..1. False for up/down/mute-only remotes.</param>
/// <param name="Status">Reports position and play state back, so a scrub bar can move.</param>
public readonly record struct Caps(bool Seek, bool Volume, bool Status);

/// <summary>
/// One cast target, whatever protocol it speaks — step 4c of
/// <c>docs/phase4-casting.md</c>, written before the second and third backends rather
/// than after, which is what stops the <c>switch (deviceType)</c> swamp the doc warns
/// about. The receiver page, mpv, the desktop's default handler, a Roku and a DLNA
/// renderer are all just implementations of this.
///
/// Adapters report their playback state by publishing into
/// <see cref="CastHub.OnStatus"/> in the receiver page's shape. That is deliberate: the
/// phone's scrub bar, play/pause toggle and "nothing is attached" handling then work
/// against a new protocol with no new wire message and no new parsing on the phone.
/// </summary>
public interface IRemotePlayer
{
    /// <summary>Stable enough to be sent back as <c>target</c> on the next cast. For a
    /// LAN device that means it contains the address, not a scan-order index.</summary>
    string Id { get; }

    /// <summary>What the phone shows in the picker.</summary>
    string Name { get; }

    /// <summary>Protocol family: <c>receiver</c>, <c>mpv</c>, <c>shell</c>, <c>roku</c>, <c>dlna</c>.</summary>
    string Kind { get; }

    Caps Caps { get; }

    /// <summary>Worth routing a cast to at all.</summary>
    bool Available { get; }

    /// <summary>Holding a cast right now, so there is something for the transport
    /// buttons to drive. Distinct from <see cref="Available"/>: an installed mpv that
    /// isn't running is available but not live.</summary>
    bool Live { get; }

    /// <summary>Play <paramref name="url"/>. Throws
    /// <see cref="Input.UnknownMessageException"/> if the target refuses it.</summary>
    void Load(string url, string? title);

    /// <summary>One of <c>InputActions.PlayerActions</c>. False when this target has no
    /// way to obey — reported to the phone rather than swallowed, since nothing
    /// happened.</summary>
    bool Command(string action, double? to, double? by, double? level, bool? muted);

    /// <summary>
    /// Ask the device where it is and publish it through <see cref="CastHub.OnStatus"/>.
    /// Called about once a second by <see cref="CastRouter"/> while this is the active
    /// target. The default is nothing: the receiver page and mpv push their own status
    /// unprompted, and only the LAN protocols have to be asked.
    /// </summary>
    Task PollAsync() => Task.CompletedTask;
}

/// <summary>The receiver page (4g) — a browser tab holding a socket open.</summary>
internal sealed class ReceiverPlayer : IRemotePlayer
{
    public string Id => "receiver";
    public string Name => "Receiver page";
    public string Kind => "receiver";
    public Caps Caps => new(Seek: true, Volume: true, Status: true);
    public bool Available => CastHub.Instance.HasReceivers;
    public bool Live => CastHub.Instance.HasReceivers;

    public void Load(string url, string? title) => CastHub.Instance.Load(url, title);

    public bool Command(string action, double? to, double? by, double? level, bool? muted)
    {
        CastHub.Instance.Command(new { t = action, to, by, level, muted });
        return true;
    }
}

/// <summary>The PC's own mpv window (4b).</summary>
internal sealed class MpvRemotePlayer : IRemotePlayer
{
    public string Id => "mpv";
    public string Name => $"{Environment.MachineName} (mpv)";
    public string Kind => "mpv";
    public Caps Caps => new(Seek: true, Volume: true, Status: true);
    public bool Available => MpvPlayer.Instance.Available;
    public bool Live => MpvPlayer.Instance.Running;

    public void Load(string url, string? title) => MpvPlayer.Instance.Load(url, title);

    public bool Command(string action, double? to, double? by, double? level, bool? muted)
    {
        if (!MpvPlayer.Instance.Running) return false;
        MpvPlayer.Instance.Command(action, to, by, level, muted);
        return true;
    }
}

/// <summary>
/// <c>ShellExecute</c> — the last resort (4a). Throws the link at whatever the desktop
/// has registered and forgets it, so there is nothing to drive afterwards and
/// <see cref="Caps"/> says so.
/// </summary>
internal sealed class ShellPlayer : IRemotePlayer
{
    public string Id => "shell";
    public string Name => $"{Environment.MachineName} (default player)";
    public string Kind => "shell";
    public Caps Caps => new(Seek: false, Volume: false, Status: false);
    public bool Available => true;
    public bool Live => false;

    public void Load(string url, string? title) => CastLauncher.Open(url);

    public bool Command(string action, double? to, double? by, double? level, bool? muted) => false;
}
