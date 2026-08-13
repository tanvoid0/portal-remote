using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>One line of agent-platform's server-sent stream, classified.</summary>
public abstract record ChatFrame
{
    /// <summary>More text for the reply being assembled.</summary>
    public sealed record Delta(string Text) : ChatFrame;

    /// <summary>Upstream said <c>[DONE]</c>. The reply is whole.</summary>
    public sealed record Done : ChatFrame;

    /// <summary>Carries nothing to show — a keep-alive, a role-only first chunk, a
    /// comment, a frame we could not parse. Skipped rather than treated as empty text.</summary>
    public sealed record Ignore : ChatFrame;
}

/// <summary>How a reply ended, which is the difference between an answer and a fragment.</summary>
/// <param name="Done">Upstream sent its terminator. Anything else is a stream that stopped.</param>
/// <param name="Error">The request never produced a stream at all.</param>
public sealed record ChatOutcome(bool Done, string? Error);

/// <summary>
/// Talks to agent-platform's <c>/v1/chat/completions</c> — the streaming half of
/// <c>docs/phase7-assistant.md</c> §7b, moved off the HTTP surface it used to live on.
///
/// <b>Why this is no longer an endpoint the phone calls.</b> It used to pipe the upstream
/// SSE straight through to whichever phone asked, unparsed. That worked while the
/// conversation lived in that phone's memory — and stopped working the moment the PC
/// became the thing that owns the transcript and two clients render it. The reply now has
/// to land in <see cref="AiConversation"/> as it arrives, and go out to <i>everyone</i>
/// watching, so it is parsed here and pushed rather than proxied.
///
/// The credential story is unchanged and is the reason this class exists at all (§3): the
/// PC holds the token and reaches the daemon over loopback; the phone never sees either.
/// </summary>
public sealed class AiChatClient
{
    /// <summary>
    /// No timeout on the whole operation: a long answer legitimately takes minutes, and
    /// <see cref="HttpClient.Timeout"/> covers the body being read as well as the headers.
    /// Failure is detected by the stream ending, not by a clock. Deliberately a different
    /// client from <see cref="AiHealth"/>'s, whose one-second timeout is the point of it.
    /// </summary>
    private static readonly HttpClient Http = CreateClient();

    /// <summary>Cap on the conversation sent upstream on our token. Generous for a chat
    /// read on a phone screen.</summary>
    public const int MaxMessages = 100;

    public const int MaxContentChars = 16 * 1024;

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.Add("X-Agent-Platform-Client", "portal-remote");
        return client;
    }

    /// <summary>
    /// Stream one reply, handing text to <paramref name="onDelta"/> as it arrives.
    ///
    /// Never throws for an upstream failure: the caller is a turn already on screen on two
    /// machines, and it needs an ending either way.
    /// </summary>
    public async Task<ChatOutcome> StreamAsync(
        AgentPlatformConfig config,
        IReadOnlyList<(string Role, string Content)> messages,
        Action<string> onDelta,
        CancellationToken ct)
    {
        var wire = new JsonArray();
        // The system prompt is added here rather than trusted from a client: it is the one
        // message the user does not write, and anything that could replace it could ask
        // this PC's assistant to be something else entirely.
        if (!string.IsNullOrWhiteSpace(config.SystemPrompt))
            wire.Add(new JsonObject { ["role"] = "system", ["content"] = config.SystemPrompt });
        foreach (var (role, content) in messages)
            wire.Add(new JsonObject { ["role"] = role, ["content"] = content });

        var payload = new JsonObject
        {
            ["model"] = config.Model,
            ["messages"] = wire,
            ["stream"] = true,
        };
        // A plain object literal cannot omit `provider` conditionally, and sending it
        // empty would pin every request to "" instead of letting agent-platform resolve
        // the model on its own.
        if (!string.IsNullOrWhiteSpace(config.Provider))
            payload["provider"] = config.Provider;

        var request = new HttpRequestMessage(
            HttpMethod.Post, $"{config.BaseUrl.TrimEnd('/')}/v1/chat/completions")
        {
            Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json")
        };

        // Empty is legal and is the zero-setup path — no master key on their side means
        // auth is open on loopback, and sending `Bearer ` would be worse than nothing.
        if (!string.IsNullOrWhiteSpace(config.Token))
            request.Headers.Add("Authorization", $"Bearer {config.Token}");

        try
        {
            using var upstream = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            if (!upstream.IsSuccessStatusCode)
            {
                // Their body names the real cause — an unconfigured provider, an unknown
                // model id — far better than a status code does.
                var body = await upstream.Content.ReadAsStringAsync(ct);
                return new ChatOutcome(false, Describe((int)upstream.StatusCode, body));
            }

            await using var stream = await upstream.Content.ReadAsStreamAsync(ct);
            using var reader = new StreamReader(stream, Encoding.UTF8);

            while (await reader.ReadLineAsync(ct) is { } line)
            {
                switch (Parse(line))
                {
                    case ChatFrame.Delta delta:
                        onDelta(delta.Text);
                        break;
                    case ChatFrame.Done:
                        return new ChatOutcome(true, null);
                }
            }

            // Ended without the terminator. The caller keeps whatever arrived and flags it.
            return new ChatOutcome(false, null);
        }
        catch (OperationCanceledException)
        {
            // A deliberate Stop, or the server shutting down. What arrived stands.
            return new ChatOutcome(true, null);
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException)
        {
            return new ChatOutcome(false, ex.Message);
        }
    }

    /// <summary>
    /// One line of the stream, classified. Pure, so the parsing every reply depends on is
    /// testable without a socket.
    ///
    /// Only <c>data:</c> lines carry anything. Blank lines separate events, <c>:</c> lines
    /// are comments used as keep-alives, and the first chunk of an OpenAI stream usually
    /// carries <c>{"role":"assistant"}</c> with no content at all — none of which are text
    /// and none of which are errors.
    /// </summary>
    public static ChatFrame Parse(string line)
    {
        if (!line.StartsWith("data:", StringComparison.Ordinal)) return new ChatFrame.Ignore();
        var payload = line[5..].Trim();
        if (payload.Length == 0) return new ChatFrame.Ignore();
        if (payload == "[DONE]") return new ChatFrame.Done();

        try
        {
            using var document = JsonDocument.Parse(payload);
            if (!document.RootElement.TryGetProperty("choices", out var choices) ||
                choices.ValueKind != JsonValueKind.Array || choices.GetArrayLength() == 0)
                return new ChatFrame.Ignore();

            var first = choices[0];
            if (!first.TryGetProperty("delta", out var delta) || delta.ValueKind != JsonValueKind.Object)
                return new ChatFrame.Ignore();
            if (!delta.TryGetProperty("content", out var content) || content.ValueKind != JsonValueKind.String)
                return new ChatFrame.Ignore();

            var text = content.GetString();
            return string.IsNullOrEmpty(text) ? new ChatFrame.Ignore() : new ChatFrame.Delta(text);
        }
        catch (JsonException)
        {
            // A frame we cannot parse is not worth killing a reply over — the next one is
            // usually fine, and the stream ending is what actually reports failure.
            return new ChatFrame.Ignore();
        }
    }

    /// <summary>Human-readable failure, preferring whatever agent-platform said over the
    /// status code. A 401 has one cause and one fix, so it says so rather than leaving
    /// somebody to read a number.</summary>
    public static string Describe(int code, string? body)
    {
        if (code is 401 or 403)
            return $"agent-platform refused Portal Remote ({code}). Mint a token on this machine and "
                 + $"put it in AgentPlatform.Token in {ServerConfig.DefaultConfigPath}.";

        var trimmed = (body ?? string.Empty).Trim();
        if (trimmed.StartsWith('{'))
        {
            try
            {
                using var document = JsonDocument.Parse(trimmed);
                foreach (var name in new[] { "detail", "error", "message" })
                {
                    if (!document.RootElement.TryGetProperty(name, out var value)) continue;
                    if (value.ValueKind == JsonValueKind.String && value.GetString() is { Length: > 0 } said)
                        return said;
                }
            }
            catch (JsonException)
            {
                // Fall through to the status code.
            }
        }

        return $"agent-platform answered {code}.";
    }
}
