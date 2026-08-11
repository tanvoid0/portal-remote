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

    /// <summary>Shared secret the phone must present on every request.</summary>
    public string Token { get; set; } = NewToken();

    /// <summary>Root directory exposed to the file browser. Nothing outside it is reachable.</summary>
    public string ShareRoot { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "PortalRemoteShare");

    [JsonIgnore]
    public string ConfigPath => DefaultConfigPath;

    public static string ConfigDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "portal-remote");

    public static string DefaultConfigPath => Path.Combine(ConfigDirectory, "config.json");

    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string NewToken() => Convert.ToBase64String(RandomNumberGenerator.GetBytes(24))
        .Replace('+', '-').Replace('/', '_').TrimEnd('=');

    public static ServerConfig Load()
    {
        ServerConfig config;
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
