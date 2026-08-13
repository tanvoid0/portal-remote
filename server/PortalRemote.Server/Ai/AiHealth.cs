using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>
/// Whether agent-platform is answering — step 7a of <c>docs/phase7-assistant.md</c>, and
/// deliberately the first thing built, because everything else in that phase assumes a
/// correct answer to "is it up?".
///
/// <b>Unavailable is the normal case, not the error case.</b> <c>agent-platformd</c> is a
/// separate app the user starts independently, so the phone is told the state rather than
/// left to discover it by getting a failure out of a request it shouldn't have sent.
/// </summary>
public sealed class AiHealth : IDisposable
{
    /// <summary>Configured, not answering yet. Carries the reason.</summary>
    public const string Unavailable = "unavailable";

    /// <summary>No base URL. A setup problem rather than a failure — say where to fix it.</summary>
    public const string Unconfigured = "unconfigured";

    public const string Ready = "ready";

    /// <summary>A refused connection is instant. Slower than this isn't "down", it's
    /// "busy", and those need different words on screen.</summary>
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(1);

    /// <summary>How long a success is trusted, so a chat turn and the action call
    /// behind it don't probe twice.</summary>
    private static readonly TimeSpan Freshness = TimeSpan.FromSeconds(5);

    /// <summary>Consecutive failures before the circuit opens.</summary>
    private const int FailuresBeforeBackoff = 3;

    private static readonly TimeSpan FirstBackoff = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MaxBackoff = TimeSpan.FromMinutes(5);

    private readonly AgentPlatformConfig config;
    private readonly HttpClient http;
    private readonly SemaphoreSlim gate = new(1, 1);

    private string state;
    private string? detail;
    private int failures;

    /// <summary>Earliest next probe — freshness after a success, backoff after failures.</summary>
    private DateTime nextProbe = DateTime.MinValue;

    public AiHealth(AgentPlatformConfig config)
    {
        this.config = config;
        http = new HttpClient { Timeout = ProbeTimeout };
        // Their auth.rs resolves this header, and their other client already sends it.
        http.DefaultRequestHeaders.Add("X-Agent-Platform-Client", "portal-remote");

        state = Configured ? Unavailable : Unconfigured;
        detail = Configured ? "Not probed yet" : $"Set AgentPlatform.BaseUrl in {ServerConfig.DefaultConfigPath}";
    }

    /// <summary>Pushed to every connected phone when the answer changes.</summary>
    public event Action<object>? Changed;

    private bool Configured => !string.IsNullOrWhiteSpace(config.BaseUrl);

    /// <summary>The last answer, for callers that act on it or render it rather than
    /// forward it — <see cref="AiAssistant"/> checks this before dialling
    /// agent-platform twice, and the desktop window draws it as a row.</summary>
    public string State => state;

    /// <summary>Why, in words meant for whoever has to fix it. Null when ready.</summary>
    public string? Detail => detail;

    /// <summary>
    /// The current answer. <c>canStart</c> is always false for now: launching
    /// <c>agent-platformd</c> ourselves is 7g, deliberately the last step, and claiming
    /// a Start button that does nothing is worse than not offering one.
    /// </summary>
    public object Snapshot() => new { t = "ai_state", state, detail, canStart = false };

    /// <summary>
    /// Probe if it's worth probing, and answer. <paramref name="userAsked"/> is a
    /// person pressing Retry, which is always allowed to skip the backoff.
    /// </summary>
    public async Task<object> CheckAsync(bool userAsked, CancellationToken ct = default)
    {
        if (!Configured)
        {
            Update(Unconfigured, $"Set AgentPlatform.BaseUrl in {ServerConfig.DefaultConfigPath}");
            return Snapshot();
        }

        if (userAsked)
        {
            failures = 0;
            nextProbe = DateTime.MinValue;
        }

        // On demand and on transition, never on a timer — but not twice in a second
        // either, and not at all while the circuit is open.
        if (DateTime.UtcNow < nextProbe) return Snapshot();

        // Single-flight: two phones opening the tab at once is one probe.
        await gate.WaitAsync(ct);
        try
        {
            if (DateTime.UtcNow < nextProbe) return Snapshot();
            await ProbeAsync(ct);
        }
        finally
        {
            gate.Release();
        }

        return Snapshot();
    }

    private async Task ProbeAsync(CancellationToken ct)
    {
        var url = $"{config.BaseUrl.TrimEnd('/')}/health";
        try
        {
            using var response = await http.GetAsync(url, ct);
            if (!response.IsSuccessStatusCode)
            {
                Fail($"{url} answered {(int)response.StatusCode}");
                return;
            }

            failures = 0;
            nextProbe = DateTime.UtcNow + Freshness;
            Update(Ready, null);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            // A refused connection and a timeout are both "not answering", but they read
            // differently to whoever has to fix it, so the message is kept.
            Fail(ex is TaskCanceledException ? $"{url} did not answer within a second" : ex.Message);
        }
    }

    private void Fail(string reason)
    {
        failures++;
        // The circuit: three refusals in a row means nobody is going to answer soon, and
        // probing a dead port every time a screen opens is just noise on both machines.
        nextProbe = failures >= FailuresBeforeBackoff
            ? DateTime.UtcNow + Backoff(failures)
            : DateTime.MinValue;
        Update(Unavailable, reason);
    }

    private static TimeSpan Backoff(int failures)
    {
        var doublings = Math.Min(failures - FailuresBeforeBackoff, 10);
        var delay = FirstBackoff * Math.Pow(2, doublings);
        return delay > MaxBackoff ? MaxBackoff : delay;
    }

    private void Update(string newState, string? newDetail)
    {
        if (state == newState && detail == newDetail) return;
        state = newState;
        detail = newDetail;
        Changed?.Invoke(Snapshot());
    }

    public void Dispose()
    {
        http.Dispose();
        gate.Dispose();
    }
}
