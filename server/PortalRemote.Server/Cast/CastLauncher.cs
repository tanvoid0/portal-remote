using System.Diagnostics;
using PortalRemote.Input;

namespace PortalRemote.Cast;

/// <summary>
/// Phase 4a of <c>docs/phase4-casting.md</c>: the phone hands over a media URL and
/// this PC plays it. "Plays it" is currently whatever the desktop has registered for
/// the link — a real player with transport control (mpv over an IPC pipe) is 4b.
/// </summary>
public static class CastLauncher
{
    /// <summary>
    /// Normalise a cast URL or reject it. Applied to every route, not just the shell
    /// one: a receiver page would happily load a `javascript:` URL too.
    /// </summary>
    public static string Validate(string url)
    {
        // ShellExecute runs whatever is registered for the scheme, so http(s) is the
        // only thing that may reach it: `file:`, `ms-settings:` or a bare path would
        // quietly turn "cast this video" into "run this program on my PC". The token
        // holder can already type at this machine, so this is not a privilege
        // boundary — it's about a malformed URL not becoming a program launch.
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            throw new UnknownMessageException("cast needs an absolute http(s) url");

        return uri.AbsoluteUri;
    }

    /// <summary>Open <paramref name="url"/> in the desktop's default handler.</summary>
    public static void Open(string url) =>
        Process.Start(new ProcessStartInfo(Validate(url)) { UseShellExecute = true })?.Dispose();
}
