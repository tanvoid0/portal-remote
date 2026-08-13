using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PortalRemote.Config;

/// <summary>
/// Persisted server settings. Written to %APPDATA%\portal-remote\config.json and
/// created with fresh defaults (including a new pairing token) on first run.
/// </summary>
public sealed class ServerConfig
{
    public const int DefaultPort = 8765;

    public int Port { get; set; } = DefaultPort;

    /// <summary>
    /// Stable identity for this install, handed out in the discovery reply and the
    /// hello. The phone stores an IP address for a paired PC and DHCP reassigns
    /// those, so this is the only part of a pairing that still names the same
    /// machine next month — it's what lets the phone follow this PC to a new
    /// address instead of asking the user to pair again. Carries nothing secret.
    /// </summary>
    public string Id { get; set; } = NewId();

    /// <summary>Shared secret the phone must present on every request.</summary>
    public string Token { get; set; } = NewToken();

    /// <summary>
    /// Where mpv lives, if it isn't next to our own exe or on PATH. Empty is the
    /// normal case — detect, do not bundle (<c>docs/phase4-casting.md</c> §6) — and a
    /// cast falls back to the desktop's default handler when nothing is found.
    /// </summary>
    public string MpvPath { get; set; } = string.Empty;

    /// <summary>Root directory exposed to the file browser. Nothing outside it is reachable.</summary>
    public string ShareRoot { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "PortalRemoteShare");

    /// <summary>
    /// The port the server actually bound to at startup. <see cref="Port"/> is
    /// editable from the settings window but only takes effect on the next launch,
    /// so anything the phone has to dial — the pairing URL, the QR, the tray
    /// tooltip — must be built from this instead.
    /// </summary>
    [JsonIgnore]
    public int RunningPort { get; private set; } = DefaultPort;

    /// <summary>No config file existed when this was loaded — nobody has ever paired
    /// with this PC, so the window is worth opening unprompted.</summary>
    [JsonIgnore]
    public bool IsFirstRun { get; private set; }

    [JsonIgnore]
    public string ConfigPath => DefaultConfigPath;

    public static string ConfigDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "portal-remote");

    public static string DefaultConfigPath => Path.Combine(ConfigDirectory, "config.json");

    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string NewToken() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(24))
        .Replace('+', '-').Replace('/', '_').TrimEnd('=');

    /// <summary>Not a secret and never used as one — it is published to anything
    /// that probes for servers, so a plain GUID is the right shape.</summary>
    public static string NewId() => Guid.NewGuid().ToString("n");

    public static ServerConfig Load()
    {
        ServerConfig config;
        var existed = File.Exists(DefaultConfigPath);
        try
        {
            config = File.Exists(DefaultConfigPath)
                ? JsonSerializer.Deserialize<ServerConfig>(File.ReadAllText(DefaultConfigPath)) ?? new ServerConfig()
                : new ServerConfig();
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // A corrupt or unreadable config must not stop the server from starting.
            config = new ServerConfig();
        }

        if (string.IsNullOrWhiteSpace(config.Token))
            config.Token = NewToken();

        // Also fills in for configs written before this field existed, so an
        // upgrade earns an id without the user re-pairing.
        if (string.IsNullOrWhiteSpace(config.Id))
            config.Id = NewId();

        // Pinned here rather than at the call site in Program: this is the only
        // place a config is created, so it can't be forgotten later.
        config.RunningPort = config.Port;
        config.IsFirstRun = !existed;

        config.Save();
        return config;
    }

    public void Save()
    {
        Directory.CreateDirectory(ConfigDirectory);
        File.WriteAllText(DefaultConfigPath, JsonSerializer.Serialize(this, JsonOptions));
    }

    public string RotateToken()
    {
        Token = NewToken();
        Save();
        return Token;
    }

    /// <summary>Constant-time comparison, so a wrong token leaks nothing by timing.</summary>
    public bool CheckToken(string? candidate)
    {
        if (string.IsNullOrEmpty(candidate)) return false;
        var a = System.Text.Encoding.UTF8.GetBytes(candidate);
        var b = System.Text.Encoding.UTF8.GetBytes(Token);
        return CryptographicOperations.FixedTimeEquals(a, b);
    }

    /// <summary>Fully-resolved share root, created if missing.</summary>
    public string ResolvedShareRoot()
    {
        var path = Path.GetFullPath(Environment.ExpandEnvironmentVariables(ShareRoot));
        Directory.CreateDirectory(path);
        return path;
    }
}
