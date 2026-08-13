using System.Text.Json;
using PortalRemote.Auth;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>
/// <c>GET /ai/models</c> and <c>POST /ai/model</c> — lets the phone see and change which
/// provider/model answers <see cref="AiChatEndpoint"/>, instead of the two being fixed at
/// whatever was hand-edited into <c>config.json</c> (<c>docs/phase7-assistant.md</c> §11.2).
///
/// Both proxy agent-platform's own catalogue (<c>GET /v1/capabilities</c>,
/// <c>GET /v1/models</c>) rather than mirroring it in our own config — that catalogue is
/// the one place that actually knows which providers are configured on this machine and
/// which models each one answers to, and it can change without this app knowing (a new
/// Ollama pull, a BYOK key added to agent-platform's own settings).
/// </summary>
public static class AiModelsEndpoint
{
    /// <summary>agent-platform's own catalog read is the slow one of the two calls this
    /// makes (it can probe live provider APIs); 8s matches what their own admin UI waits.</summary>
    private static readonly TimeSpan CatalogTimeout = TimeSpan.FromSeconds(8);

    private static readonly JsonSerializerOptions Wire = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = CatalogTimeout };
        client.DefaultRequestHeaders.Add("X-Agent-Platform-Client", "portal-remote");
        return client;
    }

    public static void MapAiModelEndpoints(this WebApplication app, ServerConfig config, AiHealth ai)
    {
        app.MapGet("/ai/models", async (HttpContext http, CancellationToken ct) =>
        {
            if (!TokenAuth.IsAuthorized(http, config))
            {
                http.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return;
            }

            // Same rule as /ai/chat: the phone shouldn't have been able to get here with
            // the backend down, but "should not" is not "cannot".
            var health = await ai.CheckAsync(userAsked: false, ct);
            if (!IsReady(health))
            {
                http.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                await http.Response.WriteAsJsonAsync(health, ct);
                return;
            }

            try
            {
                using var capabilities = await GetJsonAsync(config, "/v1/capabilities", ct);
                using var models = await GetJsonAsync(config, "/v1/models", ct);
                var catalog = BuildCatalog(
                    capabilities.RootElement, models.RootElement,
                    config.AgentPlatform.Provider, config.AgentPlatform.Model);
                await http.Response.WriteAsJsonAsync(catalog, Wire, ct);
            }
            catch (HttpRequestException ex)
            {
                http.Response.StatusCode = StatusCodes.Status502BadGateway;
                await http.Response.WriteAsJsonAsync(new { error = ex.Message }, ct);
            }
        });

        app.MapPost("/ai/model", async (HttpContext http, CancellationToken ct) =>
        {
            if (!TokenAuth.IsAuthorized(http, config))
            {
                http.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return;
            }

            ModelChoice? body;
            try
            {
                body = await JsonSerializer.DeserializeAsync<ModelChoice>(http.Request.Body, Wire, ct);
            }
            catch (JsonException)
            {
                http.Response.StatusCode = StatusCodes.Status400BadRequest;
                await http.Response.WriteAsJsonAsync(new { error = "expected a json object" }, ct);
                return;
            }

            var model = body?.Model?.Trim();
            if (string.IsNullOrEmpty(model))
            {
                http.Response.StatusCode = StatusCodes.Status400BadRequest;
                await http.Response.WriteAsJsonAsync(new { error = "model is required" }, ct);
                return;
            }

            // Not validated against the catalogue: agent-platform is the one place that
            // knows whether this pairing actually resolves, and the next chat turn will
            // say so (§7) exactly as it would for a value hand-edited into config.json.
            config.AgentPlatform.Model = model;
            config.AgentPlatform.Provider = body?.Provider?.Trim() ?? string.Empty;
            config.Save();

            await http.Response.WriteAsJsonAsync(
                new CurrentSelection(config.AgentPlatform.Provider, config.AgentPlatform.Model), Wire, ct);
        });
    }

    private static bool IsReady(object health) =>
        JsonSerializer.SerializeToElement(health).TryGetProperty("state", out var s) &&
        s.GetString() == "ready";

    private static async Task<JsonDocument> GetJsonAsync(ServerConfig config, string path, CancellationToken ct)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, $"{config.AgentPlatform.BaseUrl.TrimEnd('/')}{path}");
        if (!string.IsNullOrWhiteSpace(config.AgentPlatform.Token))
            request.Headers.Add("Authorization", $"Bearer {config.AgentPlatform.Token}");

        using var response = await Http.SendAsync(request, ct);
        response.EnsureSuccessStatusCode();
        var stream = await response.Content.ReadAsStreamAsync(ct);
        return await JsonDocument.ParseAsync(stream, cancellationToken: ct);
    }

    /// <summary>
    /// The pure half: agent-platform's two responses in, our picker's shape out. No HTTP
    /// in here, which is what makes it testable without a live backend.
    ///
    /// Only providers <c>/v1/capabilities</c> marks <c>chat</c>-capable are kept — a
    /// provider that only does embeddings or image generation has nothing to offer a
    /// chat model picker. Each model and provider row carries whether it's
    /// <c>configured</c> rather than being dropped when it isn't: the phone shows it
    /// disabled with the reason, per this app's "never hide, say why" rule
    /// (<c>docs/design-system.md</c> §4.5), instead of a list that silently shrinks.
    /// </summary>
    internal static ModelCatalog BuildCatalog(
        JsonElement capabilities, JsonElement models, string? currentProvider, string? currentModel)
    {
        var configured = new Dictionary<string, bool>();
        var chatCapable = new HashSet<string>();
        if (capabilities.TryGetProperty("providers", out var providersEl) &&
            providersEl.ValueKind == JsonValueKind.Object)
        {
            foreach (var provider in providersEl.EnumerateObject())
            {
                var isConfigured = provider.Value.TryGetProperty("configured", out var c) &&
                                    c.ValueKind == JsonValueKind.True;
                configured[provider.Name] = isConfigured;
                if (provider.Value.TryGetProperty("chat", out var chat) && chat.ValueKind == JsonValueKind.True)
                    chatCapable.Add(provider.Name);
            }
        }

        var providerRows = chatCapable
            .OrderBy(id => id, StringComparer.Ordinal)
            .Select(id => new ProviderRow(id, configured.GetValueOrDefault(id)))
            .ToList();

        var modelRows = new List<ModelRow>();
        if (models.TryGetProperty("data", out var dataEl) && dataEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var row in dataEl.EnumerateArray())
            {
                var id = row.TryGetProperty("id", out var idEl) ? idEl.GetString() : null;
                var provider = row.TryGetProperty("owned_by", out var ownerEl) ? ownerEl.GetString() : null;
                if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(provider) || !chatCapable.Contains(provider))
                    continue;
                modelRows.Add(new ModelRow(id, provider, configured.GetValueOrDefault(provider)));
            }
        }

        return new ModelCatalog(new CurrentSelection(currentProvider, currentModel), providerRows, modelRows);
    }

    private sealed record ModelChoice(string? Model, string? Provider);
}

/// <summary>The provider/model a chat turn is currently sent to. Either half may be
/// empty, meaning "let agent-platform resolve it" — the zero-setup state §9 describes.</summary>
public sealed record CurrentSelection(string? Provider, string? Model);

public sealed record ProviderRow(string Id, bool Configured);

public sealed record ModelRow(string Id, string Provider, bool Configured);

public sealed record ModelCatalog(CurrentSelection Current, List<ProviderRow> Providers, List<ModelRow> Models);
