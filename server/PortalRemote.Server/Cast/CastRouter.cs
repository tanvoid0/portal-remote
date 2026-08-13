namespace PortalRemote.Cast;

/// <summary>
/// Where a cast goes, in one place. Best control first: an attached receiver page,
/// because opening it was a deliberate choice of *screen* and quite possibly not this
/// one; then mpv, which plays formats a browser can't and takes the same commands; then
/// <c>ShellExecute</c>, which throws the link at whatever is registered and forgets it.
///
/// Extracted when the DLNA renderer became a second caller. The routing is the part
/// that must not drift between them — a phone and a copy of VLC pointing at this PC
/// should land in the same player.
/// </summary>
public static class CastRouter
{
    /// <summary>Play <paramref name="url"/>. Returns the validated URL and which of the
    /// three routes took it.</summary>
    public static (string Url, string Via) Cast(string url, string? title)
    {
        var checkedUrl = CastLauncher.Validate(url);

        if (CastHub.Instance.HasReceivers)
        {
            CastHub.Instance.Load(checkedUrl, title);
            return (checkedUrl, "receiver");
        }

        if (MpvPlayer.Instance.Available)
        {
            MpvPlayer.Instance.Load(checkedUrl, title);
            return (checkedUrl, "mpv");
        }

        CastLauncher.Open(checkedUrl);
        return (checkedUrl, "shell");
    }

    /// <summary>
    /// Drive whatever the cast actually landed on. False when nothing is attached —
    /// which is an error worth reporting rather than a silent no-op, since there is
    /// nothing on the other end to have obeyed.
    /// </summary>
    public static bool Transport(string action, double? to, double? by, double? level, bool? muted)
    {
        if (CastHub.Instance.HasReceivers)
        {
            CastHub.Instance.Command(new { t = action, to, by, level, muted });
            return true;
        }

        if (MpvPlayer.Instance.Running)
        {
            MpvPlayer.Instance.Command(action, to, by, level, muted);
            return true;
        }

        return false;
    }
}
