using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Text.Json;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>
/// Get agent-platform onto this PC and running, in one click.
///
/// The assistant is the one feature with a dependency the user has to install
/// themselves: <c>agent-platformd</c> is a separate app (<see cref="RepoUrl"/>) and
/// without it every assistant surface says "not running" and stops there. Telling
/// somebody to go and find a release, unzip it and remember where they put it is the
/// step that never happens, so the window does it: newest Windows server build off the
/// project's GitHub releases, unzipped beside our own config, started, and its path
/// written back to <see cref="AgentPlatformConfig.ExePath"/> so the next start is
/// instant.
///
/// This is the "install it for you" path only, not the "manage it forever" one — no
/// update loop, no supervision, no bundling. Whoever wants a specific build still
/// points <c>AgentPlatform.ExePath</c> wherever they like and this never overrides it.
/// </summary>
public static class AgentPlatformSetup
{
    public const string RepoUrl = "https://github.com/tanvoid0/agent-platform";

    /// <summary>Unauthenticated, same reasoning as <see cref="Update.UpdateCheck"/>:
    /// a public repo's releases are public, and a token in a downloadable .exe is a
    /// token everybody has.</summary>
    private const string LatestUrl = "https://api.github.com/repos/tanvoid0/agent-platform/releases/latest";

    /// <summary>The server daemon, not the desktop app: that one is a whole second UI
    /// with its own tray presence, and all Portal Remote needs is something answering
    /// on <see cref="AgentPlatformConfig.BaseUrl"/>.</summary>
    private const string AssetPrefix = "agent-platform-server-";
    private const string AssetSuffix = "x86_64-pc-windows-msvc.zip";

    private static readonly HttpClient Http = new()
    {
        DefaultRequestHeaders = { UserAgent = { new ProductInfoHeaderValue("PortalRemote", ServerInfo.Version) } },
        Timeout = TimeSpan.FromMinutes(10),
    };

    /// <summary>Next to our own config rather than in Program Files: no elevation, and
    /// it is ours to delete.</summary>
    public static string InstallDirectory => Path.Combine(ServerConfig.ConfigDirectory, "agent-platform");

    /// <summary>The exe to launch, or null when nothing is installed yet. A configured
    /// <see cref="AgentPlatformConfig.ExePath"/> wins — it is the user saying which build
    /// they meant.</summary>
    public static string? InstalledExe(ServerConfig config)
    {
        var configured = config.AgentPlatform.ExePath;
        if (!string.IsNullOrWhiteSpace(configured) && File.Exists(configured)) return configured;
        return FindExe(InstallDirectory);
    }

    /// <summary>Download the newest Windows server build and unzip it. Returns the exe.</summary>
    public static async Task<string> InstallAsync(ServerConfig config, CancellationToken cancel = default)
    {
        var url = await LatestAssetUrlAsync(cancel)
                  ?? throw new InvalidOperationException(
                      "The newest agent-platform release has no Windows server build attached.");

        Directory.CreateDirectory(InstallDirectory);
        var zip = Path.Combine(InstallDirectory, "download.zip");
        await using (var source = await Http.GetStreamAsync(url, cancel))
        await using (var target = File.Create(zip))
        {
            await source.CopyToAsync(target, cancel);
        }

        // Overwrite: re-running this is how somebody upgrades, and a half-extracted
        // previous attempt must not be what decides the outcome.
        ZipFile.ExtractToDirectory(zip, InstallDirectory, overwriteFiles: true);
        File.Delete(zip);

        var exe = FindExe(InstallDirectory)
                  ?? throw new InvalidOperationException("The download contained no .exe.");

        config.AgentPlatform.ExePath = exe;
        config.Save();
        return exe;
    }

    /// <summary>Start it detached and windowless — it is a daemon, and a console box
    /// appearing on top of whatever the user was doing is not what they clicked.</summary>
    public static void Start(string exePath) =>
        Process.Start(new ProcessStartInfo
        {
            FileName = exePath,
            WorkingDirectory = Path.GetDirectoryName(exePath) ?? InstallDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
        });

    private static async Task<string?> LatestAssetUrlAsync(CancellationToken cancel)
    {
        using var response = await Http.GetAsync(LatestUrl, cancel);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"GitHub answered {(int)response.StatusCode} for the latest release.");

        return AssetUrl(await response.Content.ReadAsStringAsync(cancel));
    }

    /// <summary>Pick the Windows server zip out of a release's assets. Pure, so the
    /// matching is testable without a network — their release carries a dozen assets
    /// across three platforms plus checksums, and picking the wrong one installs macOS
    /// binaries or a text file.</summary>
    public static string? AssetUrl(string json)
    {
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("assets", out var assets) ||
            assets.ValueKind != JsonValueKind.Array) return null;

        foreach (var asset in assets.EnumerateArray())
        {
            var name = asset.TryGetProperty("name", out var n) ? n.GetString() : null;
            if (name is null) continue;
            if (!name.StartsWith(AssetPrefix, StringComparison.OrdinalIgnoreCase)) continue;
            if (!name.EndsWith(AssetSuffix, StringComparison.OrdinalIgnoreCase)) continue;
            if (asset.TryGetProperty("browser_download_url", out var u) && u.GetString() is { Length: > 0 } url)
                return url;
        }

        return null;
    }

    /// <summary>The daemon inside an extracted release. Their zip has one exe today;
    /// the name filter is so a future one shipping a helper beside it still starts the
    /// right half.</summary>
    private static string? FindExe(string directory)
    {
        if (!Directory.Exists(directory)) return null;
        var exes = Directory.GetFiles(directory, "*.exe", SearchOption.AllDirectories);
        return exes.FirstOrDefault(e => Path.GetFileName(e).Contains("agent-platform", StringComparison.OrdinalIgnoreCase))
               ?? exes.FirstOrDefault();
    }
}
