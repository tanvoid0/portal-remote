using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json;

namespace PortalRemote.Update;

/// <summary>A published build on GitHub: the tag's version, and the .exe hanging off it.</summary>
public sealed record ReleaseInfo(string Version, string ExeUrl);

/// <summary>
/// "Is there a newer Portal Remote?", answered by the project's own GitHub releases —
/// the ones CI publishes on a <c>v*</c> tag. The shipped app is a single .exe someone
/// downloaded once; without this the only upgrade path is remembering to go look.
///
/// Unauthenticated on purpose: a public repo's releases are public, and a token baked
/// into a downloadable .exe is a token everybody has.
/// </summary>
public static class UpdateCheck
{
    private const string LatestUrl = "https://api.github.com/repos/tanvoid0/portal-remote/releases/latest";

    // GitHub rejects requests without one.
    private static readonly HttpClient Http = new()
    {
        DefaultRequestHeaders = { UserAgent = { new ProductInfoHeaderValue("PortalRemote", ServerInfo.Version) } },
        Timeout = TimeSpan.FromMinutes(10)
    };

    /// <summary>Null when the newest release carries no .exe — a phone-only release, or
    /// one whose assets are still uploading.</summary>
    public static ReleaseInfo? Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (!root.TryGetProperty("tag_name", out var tag)) return null;

        var version = (tag.GetString() ?? string.Empty).TrimStart('v');
        if (version.Length == 0) return null;
        if (!root.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array) return null;

        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.TryGetProperty("name", out var n) ? n.GetString() : null;
            if (name is null || !name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) continue;
            var url = asset.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
            if (url is not null) return new ReleaseInfo(version, url);
        }

        return null;
    }

    /// <summary>
    /// Numeric-segment compare, so 0.10.0 beats 0.9.0 where a string compare would not.
    /// A non-numeric segment (an <c>-rc1</c> suffix) counts as 0, so a pre-release never
    /// reads as newer than the release it precedes.
    /// </summary>
    public static bool IsNewer(string candidate, string current)
    {
        var a = Segments(candidate);
        var b = Segments(current);
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;

        static int[] Segments(string version) => version.Trim().TrimStart('v')
            .Split('.', '-')
            .Select(part => int.TryParse(new string(part.TakeWhile(char.IsDigit).ToArray()), out var value) ? value : 0)
            .ToArray();
    }

    public static async Task<ReleaseInfo?> LatestAsync(CancellationToken cancel = default)
    {
        using var response = await Http.GetAsync(LatestUrl, cancel);
        if (!response.IsSuccessStatusCode) return null;
        return Parse(await response.Content.ReadAsStringAsync(cancel));
    }

    /// <summary>
    /// Download the new .exe and put it where this one is. Windows lets a running .exe
    /// be renamed but not overwritten, so the current one is moved aside to
    /// <c>.old</c> — <see cref="CleanUp"/> deletes it on the next start, once nothing
    /// has it open — and the download takes its place. Returns the path to relaunch.
    /// </summary>
    public static async Task<string> DownloadAndSwapAsync(ReleaseInfo release, CancellationToken cancel = default)
    {
        var current = Environment.ProcessPath
                      ?? throw new InvalidOperationException("Cannot locate the running executable.");
        var staged = current + ".new";
        var previous = current + ".old";

        await using (var source = await Http.GetStreamAsync(release.ExeUrl, cancel))
        await using (var target = File.Create(staged))
        {
            await source.CopyToAsync(target, cancel);
        }

        if (File.Exists(previous)) File.Delete(previous);
        File.Move(current, previous);
        try
        {
            File.Move(staged, current);
        }
        catch
        {
            // Never leave the user without an app: put the old one back and let the
            // caller report the failure.
            File.Move(previous, current);
            throw;
        }

        return current;
    }

    /// <summary>Relaunch the (now replaced) .exe and let this process go. The new one
    /// takes the port as soon as this one releases it, so the caller must exit right
    /// after — see the tray's Check for updates.</summary>
    public static void Relaunch(string exePath) =>
        Process.Start(new ProcessStartInfo { FileName = exePath, UseShellExecute = true });

    /// <summary>Delete the previous version left behind by an update. Best effort: if
    /// it is somehow still locked, the next start tries again.</summary>
    public static void CleanUp()
    {
        var previous = Environment.ProcessPath + ".old";
        try
        {
            if (File.Exists(previous)) File.Delete(previous);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
