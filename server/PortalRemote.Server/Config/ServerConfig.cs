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
    /// Some phone has connected with a valid token at least once — set by
    /// <c>ControlEndpoint</c> on the first authorized socket, not by handing the token
    /// out, since a phone that scans the QR never asks this PC for anything first.
    ///
    /// The window leads with the share thread once this is true and with the QR code
    /// while it is false: a paired PC whose main view is still "scan to pair" is
    /// answering a question nobody has any more.
    /// </summary>
    public bool Paired { get; set; }

    /// <summary>
    /// Where mpv lives, if it isn't next to our own exe or on PATH. Empty is the
    /// normal case — detect, do not bundle (<c>docs/phase4-casting.md</c> §6) — and a
    /// cast falls back to the desktop's default handler when nothing is found.
    /// </summary>
    public string MpvPath { get; set; } = string.Empty;

    /// <summary>
    /// Where agent-platform lives — <c>docs/phase7-assistant.md</c> §9. The phone never
    /// talks to it directly: this PC already terminates the phone's connection and can
    /// reach the daemon over loopback, where its wide-open local-dev auth is not a
    /// problem. <b>No token belongs in the repo</b>; mint one on the machine that runs it.
    /// </summary>
    public AgentPlatformConfig AgentPlatform { get; set; } = new();

    /// <summary>
    /// Announce this PC as a DLNA <c>MediaRenderer</c> so VLC, Web Video Caster and
    /// gallery apps can cast to it — <c>docs/phase4-casting.md</c> §4l.
    ///
    /// <b>Off by default, and it has to be.</b> A DLNA controller cannot present our
    /// pairing token — speaking someone else's protocol is the entire point — so those
    /// endpoints are open to the LAN, and "anyone on this Wi-Fi can put a video
    /// fullscreen on my PC" is not a default anybody chose.
    /// </summary>
    public bool EnableDlnaRenderer { get; set; }

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

/// <summary>The assistant's backend — <c>docs/phase7-assistant.md</c> §9.</summary>
public sealed class AgentPlatformConfig
{
    /// <summary>Its loopback address by default. Empty means the assistant is
    /// "unconfigured", which the phone shows as a setup step rather than a failure.</summary>
    public string BaseUrl { get; set; } = "http://127.0.0.1:18410";

    /// <summary>Empty is legal and is the zero-setup path: with no master key set on
    /// their side, auth is open on loopback.</summary>
    public string Token { get; set; } = string.Empty;

    /// <summary>Optional, and unused until 7g — the only step that starts a process.</summary>
    public string ExePath { get; set; } = string.Empty;

    /// <summary>
    /// Passed through to <c>/v1/chat/completions</c> untouched, so it is whatever model
    /// id the configured provider uses. There is no sensible default we could pick for
    /// someone else's provider catalogue, so the assistant says which one it is asking
    /// for rather than guessing on their behalf.
    /// </summary>
    public string Model { get; set; } = "gpt-4o-mini";

    /// <summary>
    /// Which provider <see cref="Model"/> belongs to — passed through as agent-platform's
    /// own optional <c>provider</c> hint. Empty means "let agent-platform resolve it from
    /// <see cref="Model"/> alone" (its alias table, or its own default), which is what
    /// every install did before the phone could switch either one. Settable from
    /// <c>/ai/model</c>, not from a phone-supplied chat request, for the same reason the
    /// system prompt isn't (<see cref="AiChatEndpoint"/>): it changes which backend a
    /// goal is sent to, not what's asked, and belongs behind the pairing token only.
    /// </summary>
    public string Provider { get; set; } = string.Empty;

    /// <summary>
    /// Prepended to every conversation. Kept in config rather than in code because the
    /// useful version of it names this PC and what the phone can ask for, and that is a
    /// per-install fact.
    ///
    /// It tells the model to answer in one line when it is asked to <i>do</i> something,
    /// because it is no longer the only thing answering: every message also goes to
    /// <c>/decide</c>, and the buttons that come back are the real reply. A paragraph
    /// explaining how it would pause the music, printed above a Pause button, is the one
    /// way this reads badly (<see cref="Ai.AiAssistant"/>).
    /// </summary>
    public string SystemPrompt { get; set; } =
        "You are the assistant inside Portal Remote, an app that remote-controls a Windows PC "
        + "from an Android phone. Be brief: answers are read on a phone screen. You can act on "
        + "the PC — media keys, keyboard shortcuts, typing, playing a link, and power — and the "
        + "app asks the user to approve each action separately, so you never need to ask for "
        + "permission yourself. When the user asks you to do something on the PC, acknowledge it "
        + "in one short line and stop; the app is already offering them the buttons. Answer "
        + "questions normally.";
}
