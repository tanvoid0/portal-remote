using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using PortalRemote.Auth;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>
/// <c>POST /ai/chat</c> — step 7b of <c>docs/phase7-assistant.md</c>. Forwards a
/// conversation to agent-platform's <c>/v1/chat/completions</c> and streams the reply
/// back to the phone.
///
/// <b>Why the PC proxies instead of the phone calling directly</b> (§3): agent-platformd
/// binds loopback, its auth is fully open when no master key is set, and it speaks plain
/// HTTP. One credential, held here, never on the wire the phone is on.
///
/// <b>Why HTTP and not the control socket:</b> that socket carries mouse movement. A
/// token stream of a few hundred tiny frames has no business sharing it, and the server
/// already streams over HTTP for the screen mirror — same shape, same token in the query
/// string for the same reason.
/// </summary>
public static class AiChatEndpoint
{
    /// <summary>
    /// No timeout on the whole operation: a long answer legitimately takes minutes, and
    /// <see cref="HttpClient.Timeout"/> applies to the entire response including the body
    /// being read. Failure is detected by the stream ending, not by a clock. Deliberately
    /// a different client from <see cref="AiHealth"/>'s, whose one-second timeout is the
    /// point of it.
    /// </summary>
    private static readonly HttpClient Http = CreateClient();

    /// <summary>Cap on the conversation a phone may send. Generous for a chat on a phone
    /// screen, and it stops an unbounded body being forwarded upstream on our token.</summary>
    private const int MaxMessages = 100;

    private const int MaxContentChars = 16 * 1024;

    /// <summary>
    /// Both directions, because both are OpenAI's wire shape: `role`/`content` lowercase.
    /// Case-insensitive on the way in as well, so a client that sends `Role` is not
    /// silently parsed into a message with no role at all.
    /// </summary>
    private static readonly JsonSerializerOptions Wire = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.Add("X-Agent-Platform-Client", "portal-remote");
        return client;
    }

    public static void MapAiEndpoints(this WebApplication app, ServerConfig config, AiHealth ai)
    {
        app.MapPost("/ai/chat", async (HttpContext http, CancellationToken ct) =>
        {
            if (!TokenAuth.IsAuthorized(http, config))
            {
                http.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return;
            }

            // Ask before dialling. The phone should not have been able to get here with
            // the backend down — it is told the state — but "should not" is not "cannot",
            // and a 503 naming the state is a better answer than a connection error.
            var health = await ai.CheckAsync(userAsked: false, ct);
            if (!IsReady(health))
            {
                http.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                await http.Response.WriteAsJsonAsync(health, ct);
                return;
            }

            List<ChatMessage> messages;
            try
            {
                messages = await ReadMessagesAsync(http, config, ct);
            }
            catch (ArgumentException ex)
            {
                http.Response.StatusCode = StatusCodes.Status400BadRequest;
                await http.Response.WriteAsJsonAsync(new { error = ex.Message }, ct);
                return;
            }

            await StreamAsync(http, config, messages, ct);
        });
    }

    private static bool IsReady(object health) =>
        JsonSerializer.SerializeToElement(health).TryGetProperty("state", out var s) &&
        s.GetString() == "ready";

    /// <summary>
    /// Read the phone's conversation and put the configured system prompt in front of it.
    ///
    /// The system prompt is added here rather than trusted from the phone: it is the one
    /// message the user does not write, and a client that could replace it could ask this
    /// PC's assistant to be something else entirely.
    /// </summary>
    private static async Task<List<ChatMessage>> ReadMessagesAsync(
        HttpContext http, ServerConfig config, CancellationToken ct)
    {
        var body = await JsonSerializer.DeserializeAsync<ChatRequest>(http.Request.Body, Wire, ct)
                   ?? throw new ArgumentException("expected a json object");

        var sent = body.Messages ?? [];
        if (sent.Count == 0) throw new ArgumentException("no messages");
        if (sent.Count > MaxMessages) throw new ArgumentException($"at most {MaxMessages} messages");

        var messages = new List<ChatMessage>(sent.Count + 1);
        if (!string.IsNullOrWhiteSpace(config.AgentPlatform.SystemPrompt))
            messages.Add(new ChatMessage("system", config.AgentPlatform.SystemPrompt));

        foreach (var message in sent)
        {
            // Only the two roles a conversation on this screen can contain. `system` is
            // ours (above) and `tool` belongs to a path that does not exist yet (7c).
            if (message.Role is not ("user" or "assistant"))
                throw new ArgumentException($"unexpected role: {message.Role}");
            if (string.IsNullOrEmpty(message.Content)) continue;
            if (message.Content.Length > MaxContentChars)
                throw new ArgumentException($"a message may be at most {MaxContentChars} characters");
            messages.Add(message);
        }

        if (messages.Count == 0 || messages[^1].Role != "user")
            throw new ArgumentException("the last message must be from the user");

        return messages;
    }

    /// <summary>
    /// Pipe the upstream SSE straight through, unparsed.
    ///
    /// The phone already has to understand OpenAI's <c>data:</c> frames to render tokens
    /// as they arrive, so re-serialising them here would be work that could only lose
    /// information. What this does add is a terminator the phone can trust: upstream ends
    /// with <c>data: [DONE]</c>, and its <i>absence</i> is how the phone tells a reply
    /// that finished from one that was cut off — which is the difference between showing
    /// an answer and offering Regenerate (§4.4).
    /// </summary>
    private static async Task StreamAsync(
        HttpContext http, ServerConfig config, List<ChatMessage> messages, CancellationToken ct)
    {
        // A plain object literal can't omit `provider` conditionally, and sending it
        // empty would pin every request to "" instead of letting agent-platform resolve
        // the model on its own — the behaviour every install had before §ai-models let
        // the phone set a provider at all.
        var payload = new JsonObject
        {
            ["model"] = config.AgentPlatform.Model,
            ["messages"] = JsonSerializer.SerializeToNode(messages, Wire),
            ["stream"] = true,
        };
        if (!string.IsNullOrWhiteSpace(config.AgentPlatform.Provider))
            payload["provider"] = config.AgentPlatform.Provider;

        var request = new HttpRequestMessage(HttpMethod.Post, $"{config.AgentPlatform.BaseUrl.TrimEnd('/')}/v1/chat/completions")
        {
            Content = new StringContent(payload.ToJsonString(Wire), Encoding.UTF8, "application/json")
        };

        // Empty is legal and is the zero-setup path — no master key on their side means
        // auth is open on loopback, and sending `Bearer ` would be worse than nothing.
        if (!string.IsNullOrWhiteSpace(config.AgentPlatform.Token))
            request.Headers.Add("Authorization", $"Bearer {config.AgentPlatform.Token}");

        try
        {
            using var upstream = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);

            if (!upstream.IsSuccessStatusCode)
            {
                // Their body says why far better than a status code does — an unconfigured
                // provider, an unknown model id — so pass it on instead of flattening it.
                var detail = await upstream.Content.ReadAsStringAsync(ct);
                http.Response.StatusCode = (int)upstream.StatusCode;
                await http.Response.WriteAsJsonAsync(
                    new { error = $"agent-platform answered {(int)upstream.StatusCode}", detail }, ct);
                return;
            }

            http.Response.ContentType = "text/event-stream";
            http.Response.Headers.CacheControl = "no-cache";
            // Nothing between us and the phone should hold a token back waiting for a
            // buffer to fill; the whole point is that it arrives as it is generated.
            await http.Response.Body.FlushAsync(ct);

            await using var body = await upstream.Content.ReadAsStreamAsync(ct);
            await body.CopyToAsync(http.Response.Body, ct);
            await http.Response.Body.FlushAsync(ct);
        }
        catch (OperationCanceledException)
        {
            // The phone left, or the app is shutting down. Nothing to report to a socket
            // that is already gone.
        }
        catch (HttpRequestException ex)
        {
            // The backend was ready a moment ago and is not now. The headers may already
            // have gone out, in which case the only honest signal left is the stream
            // ending without a [DONE] — which is exactly what the phone watches for.
            if (http.Response.HasStarted) return;
            http.Response.StatusCode = StatusCodes.Status502BadGateway;
            await http.Response.WriteAsJsonAsync(new { error = ex.Message }, CancellationToken.None);
        }
    }

    private sealed record ChatRequest(List<ChatMessage>? Messages);

    private sealed record ChatMessage(string Role, string Content);
}
