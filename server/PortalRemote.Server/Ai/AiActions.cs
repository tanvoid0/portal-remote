using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using PortalRemote.Config;
using PortalRemote.Input;

namespace PortalRemote.Ai;

/// <summary>
/// The assistant that acts — step 7c of <c>docs/phase7-assistant.md</c>.
///
/// agent-platform's action orchestrator is the whole feature: we declare what this PC can
/// do, ask <c>/api/v1/decide</c> to pick from that list, and it hands back a <b>plan</b>.
/// The server never executes anything, which is why <c>/decide</c> is safe to retry — and
/// why keeping it that way matters (§4.4). Execution is entirely ours, behind a
/// confirmation on the phone.
///
/// Every action here maps onto a message <see cref="InputActions.Dispatch"/> already
/// handles. That is deliberate: 7c adds a decision layer, not a second way to press keys.
/// </summary>
public sealed class AiActions : IDisposable
{
    /// <summary>
    /// The registered set's name, looked up rather than stored — the id is agent-platform's
    /// and its database can be wiped between runs.
    /// </summary>
    private const string SetName = "portal-remote-desktop";

    /// <summary>
    /// What this PC can do, as the model sees it. Sent verbatim, so the keys are
    /// agent-platform's snake_case rather than anything a serializer would have to be
    /// talked into.
    ///
    /// The descriptions are the actual interface: they are all the model gets to decide
    /// with, which is why each one says what the action does <i>to this PC</i> rather than
    /// naming the function behind it.
    /// </summary>
    private const string SetBody = """
    {
      "name": "portal-remote-desktop",
      "description": "Control the Windows PC that Portal Remote runs on.",
      "actions": [
        {
          "action_id": "media_control",
          "name": "Media key",
          "description": "Press a global media key on the PC — whatever Windows thinks is playing responds. Use for play/pause, skipping tracks, and system volume.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": ["play_pause", "next", "prev", "stop", "mute", "vol_up", "vol_down"],
                "description": "Which media key to press"
              }
            },
            "required": ["action"]
          },
          "execution_mode": "client"
        },
        {
          "action_id": "press_keys",
          "name": "Press keys",
          "description": "Press a keyboard shortcut on the PC. Modifiers first: [\"ctrl\",\"s\"], [\"alt\",\"f4\"], [\"win\",\"d\"].",
          "parameters": {
            "type": "object",
            "properties": {
              "keys": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Key names in press order, e.g. ctrl, alt, shift, win, esc, enter, tab, f1-f24, a-z, 0-9"
              }
            },
            "required": ["keys"]
          },
          "execution_mode": "client"
        },
        {
          "action_id": "type_text",
          "name": "Type text",
          "description": "Type text into whatever window has focus on the PC.",
          "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "The text to type"}},
            "required": ["text"]
          },
          "execution_mode": "client"
        },
        {
          "action_id": "cast_url",
          "name": "Play a link",
          "description": "Open a video or audio URL on the PC or on a screen it can reach. Use for 'play this on the TV'.",
          "parameters": {
            "type": "object",
            "properties": {
              "url": {"type": "string", "description": "http(s) URL of the media or page"},
              "title": {"type": "string", "description": "What to call it on screen"}
            },
            "required": ["url"]
          },
          "execution_mode": "client"
        },
        {
          "action_id": "player_transport",
          "name": "Control the cast",
          "description": "Control whatever Portal Remote is casting. Only works while something is casting; for ordinary desktop playback use media_control.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": ["play", "pause", "toggle", "stop", "seek", "volume"],
                "description": "Transport command"
              },
              "to": {"type": "number", "description": "Seek to this position, in seconds"},
              "by": {"type": "number", "description": "Seek by this many seconds, negative to go back"},
              "level": {"type": "number", "description": "Volume from 0 to 1"},
              "muted": {"type": "boolean", "description": "Mute the cast"}
            },
            "required": ["action"]
          },
          "execution_mode": "client"
        },
        {
          "action_id": "power",
          "name": "Power",
          "description": "Lock, sleep, shut down or restart the PC. Prefer 'lock' unless the user clearly asked for more.",
          "parameters": {
            "type": "object",
            "properties": {
              "mode": {
                "type": "string",
                "enum": ["lock", "sleep", "hibernate", "shutdown", "restart", "logoff"],
                "description": "What to do"
              }
            },
            "required": ["mode"]
          },
          "execution_mode": "client"
        }
      ]
    }
    """;

    /// <summary>
    /// The parameter each action cannot be run without. A model that answers the tool
    /// prompt in prose gets text-parsed by <c>/decide</c>, so an action arriving without
    /// its parameters is a realistic case rather than a hypothetical (§7) — and it is
    /// cheaper to drop here than to render and then fail on.
    /// </summary>
    private static readonly Dictionary<string, string> Required = new()
    {
        ["media_control"] = "action",
        ["press_keys"] = "keys",
        ["type_text"] = "text",
        ["cast_url"] = "url",
        ["player_transport"] = "action",
        ["power"] = "mode"
    };

    /// <summary>Power modes that want a second confirmation on the phone (§7).</summary>
    private static readonly HashSet<string> Destructive = ["shutdown", "restart", "logoff"];

    /// <summary>
    /// A local model thinking about a tool call is slow — tens of seconds is normal, and
    /// there is no useful shorter answer. Nothing else waits on this: the phone is holding
    /// a confirmation sheet, not a socket that has to stay responsive.
    /// </summary>
    private static readonly TimeSpan DecideTimeout = TimeSpan.FromMinutes(2);

    /// <summary>Plans kept for confirmation. Small on purpose — a plan nobody confirmed
    /// within the last few goals is one the user walked away from.</summary>
    private const int MaxPlans = 8;

    private readonly AgentPlatformConfig config;
    private readonly HttpClient http;
    private readonly SemaphoreSlim registration = new(1, 1);
    private readonly ConcurrentDictionary<string, Plan> plans = new();
    private readonly ConcurrentQueue<string> planOrder = new();

    private int? setId;

    public AiActions(AgentPlatformConfig config)
    {
        this.config = config;
        http = new HttpClient { Timeout = DecideTimeout };
        // Their auth.rs resolves this header, and it also scopes the action sets we
        // create — so "our" set is ours rather than every client's.
        http.DefaultRequestHeaders.Add("X-Agent-Platform-Client", "portal-remote");
        if (!string.IsNullOrWhiteSpace(config.Token))
            http.DefaultRequestHeaders.Add("Authorization", $"Bearer {config.Token}");
    }

    /// <summary>
    /// Ask for a plan. Never throws: the turn this belongs to is already on screen on
    /// every connected client, so a failure comes back as a decision carrying
    /// <see cref="PlanDecision.Error"/> rather than as a dropped message.
    /// </summary>
    public async Task<PlanDecision> DecideAsync(string id, string goal, object? context, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(goal)) return Failed("Nothing was asked.");

        try
        {
            var set = await SetIdAsync(ct);
            var body = JsonSerializer.Serialize(new
            {
                action_set_id = set,
                goal,
                // What is playing right now, so "pause it" has something to refer to.
                context = new { now_playing = context },
                execution_mode = "client"
            });

            using var response = await http.PostAsync(
                Url("/api/v1/decide"), new StringContent(body, Encoding.UTF8, "application/json"), ct);
            var text = await response.Content.ReadAsStringAsync(ct);
            if (!response.IsSuccessStatusCode)
                return Failed(AiChatClient.Describe((int)response.StatusCode, text));

            using var document = JsonDocument.Parse(text);
            var root = document.RootElement;
            var thought = root.TryGetProperty("thought", out var t) && t.ValueKind == JsonValueKind.String
                ? t.GetString() ?? string.Empty
                : string.Empty;

            // A 200 is not a success here. An unreachable model comes back as a thought
            // beginning "Error during decision: " with an empty action list, and that
            // sentence names the real cause far better than we could (§4.4).
            if (thought.StartsWith("Error during decision:", StringComparison.Ordinal))
                return Failed(thought);

            var actions = Validate(root);
            Remember(id, actions);

            return new PlanDecision(
                thought,
                actions.Select((a, index) => new ChatPlanAction
                {
                    Index = index,
                    ActionId = a.ActionId,
                    Summary = a.Summary,
                    Verb = a.Verb,
                    Destructive = a.Destructive
                }).ToList(),
                null);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Failed(ex is TaskCanceledException && !ct.IsCancellationRequested
                ? "The assistant took too long to decide."
                : ex.Message);
        }
    }

    /// <summary>
    /// Run the approved subset of a plan, by index.
    ///
    /// <b>Indices, not action ids</b> — a plan may legitimately contain the same action
    /// twice ("mute it and lock the PC" is one; "type this, then press enter" is two
    /// press_keys), and approving by id would run both halves of a pair the user only
    /// half-agreed to.
    ///
    /// A plan runs <b>once</b>. The phone dropping between confirm and result means the
    /// actions already happened, so a resend on reconnect must not do them again (§4.4).
    /// </summary>
    public ConfirmOutcome Confirm(string id, IReadOnlyList<int> approved)
    {
        if (!plans.TryGetValue(id, out var plan))
            return new ConfirmOutcome([], "That plan is no longer held. Ask again.");

        lock (plan)
        {
            if (plan.Executed)
                return new ConfirmOutcome([], "Already run.");
            plan.Executed = true;
        }

        var results = new List<ChatPlanResult>();
        foreach (var index in approved.Distinct())
        {
            if (index < 0 || index >= plan.Actions.Count) continue;
            var action = plan.Actions[index];
            try
            {
                // Straight back through the same dispatcher the buttons use, which is
                // also the thing that validates the parameters properly — an unknown key
                // name or media action throws here rather than being pressed.
                InputActions.Dispatch(JsonSerializer.SerializeToElement(ToMessage(action)));
                results.Add(new ChatPlanResult { Index = index, Ok = true, Detail = action.Summary });
            }
            catch (Exception ex) when (ex is UnknownMessageException or ArgumentException
                                           or InvalidOperationException
                                           or System.ComponentModel.Win32Exception)
            {
                // One action failing is not the plan failing — the rest were approved
                // separately and are still worth running.
                results.Add(new ChatPlanResult { Index = index, Ok = false, Detail = ex.Message });
            }
        }

        return new ConfirmOutcome(results, null);
    }

    /// <summary>
    /// The set's id, registered on first use rather than at startup.
    ///
    /// Startup is the one moment we know the backend is probably <i>not</i> up — it is a
    /// separate app the user starts independently (§4) — so registering there would be a
    /// guaranteed failed request every launch. Idempotent across restarts by looking the
    /// name up first, because agent-platform keeps its own database.
    /// </summary>
    private async Task<int> SetIdAsync(CancellationToken ct)
    {
        if (setId is { } known) return known;

        await registration.WaitAsync(ct);
        try
        {
            if (setId is { } raced) return raced;

            using var list = await http.GetAsync(Url("/api/v1/action-sets?limit=100"), ct);
            EnsureOk(list, "listing action sets");
            using var listed = JsonDocument.Parse(await list.Content.ReadAsStringAsync(ct));
            if (listed.RootElement.TryGetProperty("action_sets", out var sets))
            {
                foreach (var set in sets.EnumerateArray())
                {
                    if (set.TryGetProperty("name", out var name) && name.GetString() == SetName &&
                        set.TryGetProperty("id", out var existing) && existing.TryGetInt32(out var id))
                    {
                        setId = id;
                        return id;
                    }
                }
            }

            using var created = await http.PostAsync(
                Url("/api/v1/action-sets"), new StringContent(SetBody, Encoding.UTF8, "application/json"), ct);
            EnsureOk(created, "registering what this PC can do");
            using var body = JsonDocument.Parse(await created.Content.ReadAsStringAsync(ct));
            setId = body.RootElement.GetProperty("id").GetInt32();
            return setId.Value;
        }
        finally
        {
            registration.Release();
        }
    }

    /// <summary>
    /// Keep the actions we recognise and can run, drop the rest.
    ///
    /// <b>The model's output is untrusted input</b> (§7). It is validated before it is
    /// rendered, let alone executed, so a hallucinated action never reaches the
    /// confirmation sheet and cannot be approved by someone reading it too quickly.
    /// </summary>
    public static List<PlanAction> Validate(JsonElement decision)
    {
        var kept = new List<PlanAction>();
        if (!decision.TryGetProperty("actions", out var actions) || actions.ValueKind != JsonValueKind.Array)
            return kept;

        foreach (var action in actions.EnumerateArray())
        {
            if (action.ValueKind != JsonValueKind.Object) continue;
            var id = action.TryGetProperty("action_id", out var a) && a.ValueKind == JsonValueKind.String
                ? a.GetString()
                : null;
            if (id is null || !Required.TryGetValue(id, out var required)) continue;

            var parameters = action.TryGetProperty("parameters", out var p) && p.ValueKind == JsonValueKind.Object
                ? p
                : default;
            if (parameters.ValueKind != JsonValueKind.Object) continue;
            if (!parameters.TryGetProperty(required, out var value) || value.ValueKind is JsonValueKind.Null) continue;

            kept.Add(new PlanAction(
                id, Describe(id, parameters), Verb(id, parameters), IsDestructive(id, parameters), parameters.Clone()));
        }

        return kept;
    }

    /// <summary>
    /// One line of plain language per action, written here rather than on the phone: the
    /// PC is the side that knows what these do, and two implementations of "what will this
    /// button actually press" is one too many.
    /// </summary>
    private static string Describe(string id, JsonElement p) => id switch
    {
        "media_control" => $"Media key: {Str(p, "action")}",
        "press_keys" => $"Press {string.Join(" + ", Keys(p))}",
        "type_text" => $"Type “{Trim(Str(p, "text"), 60)}”",
        "cast_url" => $"Play {Trim(Str(p, "url"), 60)} on the PC",
        "player_transport" => $"Cast: {Str(p, "action")}",
        "power" => $"Power: {Str(p, "mode")}",
        _ => id
    };

    /// <summary>
    /// The same thing in two words, for a button face.
    ///
    /// A plan with one action in it should be approved by pressing the thing it does —
    /// "Mute", "Shut down", "Play" — not by pressing a generic Run beside a sentence. It is
    /// written here for the same reason <see cref="Describe"/> is: the PC is the side that
    /// knows what these press, and a button labelled by the client is a second opinion
    /// about what is about to happen to this machine.
    /// </summary>
    private static string Verb(string id, JsonElement p) => id switch
    {
        "media_control" => Str(p, "action") switch
        {
            "play_pause" => "Play/pause",
            "next" => "Next track",
            "prev" => "Previous",
            "stop" => "Stop",
            "mute" => "Mute",
            "vol_up" => "Volume up",
            "vol_down" => "Volume down",
            _ => "Press"
        },
        "press_keys" => "Press",
        "type_text" => "Type",
        "cast_url" => "Play",
        "player_transport" => Str(p, "action") switch
        {
            "play" => "Play",
            "pause" => "Pause",
            "toggle" => "Play/pause",
            "stop" => "Stop",
            "seek" => "Seek",
            "volume" => "Set volume",
            _ => "Control"
        },
        "power" => Str(p, "mode") switch
        {
            "lock" => "Lock",
            "sleep" => "Sleep",
            "hibernate" => "Hibernate",
            "shutdown" => "Shut down",
            "restart" => "Restart",
            "logoff" => "Sign out",
            _ => "Power"
        },
        _ => "Run"
    };

    private static bool IsDestructive(string id, JsonElement p) =>
        id == "power" && Destructive.Contains(Str(p, "mode").ToLowerInvariant());

    /// <summary>Onto the message the phone's own buttons send. Nothing new executes.</summary>
    public static object ToMessage(PlanAction action)
    {
        var p = action.Parameters;
        return action.ActionId switch
        {
            "media_control" => new { t = "media", action = Str(p, "action") },
            "press_keys" => (object)new { t = "combo", keys = Keys(p) },
            "type_text" => new { t = "text", s = Str(p, "text") },
            "cast_url" => new { t = "cast", url = Str(p, "url"), title = Str(p, "title") },
            "player_transport" => new
            {
                t = "player",
                action = Str(p, "action"),
                to = Num(p, "to"),
                by = Num(p, "by"),
                level = Num(p, "level"),
                muted = Bool(p, "muted")
            },
            "power" => new { t = "power", mode = Str(p, "mode") },
            _ => throw new UnknownMessageException($"unknown action: {action.ActionId}")
        };
    }

    private void Remember(string id, List<PlanAction> actions)
    {
        plans[id] = new Plan(actions);
        planOrder.Enqueue(id);
        while (planOrder.Count > MaxPlans && planOrder.TryDequeue(out var old)) plans.TryRemove(old, out _);
    }

    /// <summary>
    /// Fail with the sentence that names the fix.
    ///
    /// <c>EnsureSuccessStatusCode</c>'s wording ends up on a phone screen here, and "Response
    /// status code does not indicate success" tells the person holding it nothing. A 401 has
    /// exactly one cause — <c>agent-platformd</c> has a master key set and we have no token —
    /// and exactly one fix, so it says so.
    /// </summary>
    private static void EnsureOk(HttpResponseMessage response, string what)
    {
        if (response.IsSuccessStatusCode) return;
        var code = (int)response.StatusCode;
        throw new HttpRequestException(code is 401 or 403
            ? $"agent-platform refused Portal Remote ({code}). Mint a token on this machine and "
              + $"put it in AgentPlatform.Token in {ServerConfig.DefaultConfigPath}."
            : $"agent-platform answered {code} while {what}.");
    }

    private string Url(string path) => $"{config.BaseUrl.TrimEnd('/')}{path}";

    private static PlanDecision Failed(string error) => new(string.Empty, [], error);

    private static string Str(JsonElement p, string name) =>
        p.ValueKind == JsonValueKind.Object && p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? string.Empty
            : string.Empty;

    private static double? Num(JsonElement p, string name) =>
        p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d)
            ? d : null;

    private static bool? Bool(JsonElement p, string name) =>
        p.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? v.GetBoolean() : null;

    /// <summary>Key names as strings. A model that answers with one key rather than a
    /// list is being reasonable about English, so it is read that way too.</summary>
    private static List<string> Keys(JsonElement p)
    {
        if (!p.TryGetProperty("keys", out var keys)) return [];
        if (keys.ValueKind == JsonValueKind.String) return [keys.GetString() ?? string.Empty];
        if (keys.ValueKind != JsonValueKind.Array) return [];
        return keys.EnumerateArray()
            .Where(k => k.ValueKind == JsonValueKind.String)
            .Select(k => k.GetString() ?? string.Empty)
            .ToList();
    }

    private static string Trim(string text, int max = 200) =>
        text.Length <= max ? text : text[..max] + "…";

    public void Dispose()
    {
        http.Dispose();
        registration.Dispose();
    }

    /// <summary>One action of a plan, after validation.</summary>
    public sealed record PlanAction(
        string ActionId, string Summary, string Verb, bool Destructive, JsonElement Parameters);

    private sealed class Plan(List<PlanAction> actions)
    {
        public List<PlanAction> Actions { get; } = actions;

        /// <summary>Guarded by locking the plan itself — the one thing that must not
        /// happen twice.</summary>
        public bool Executed { get; set; }
    }
}

/// <summary>
/// What <c>/decide</c> came back with, already validated and already worded for a person.
///
/// An empty action list with no <paramref name="Error"/> is the assistant saying there is
/// nothing on this PC to do about the question — an answer, not a failure, and the
/// difference is what stops a confirmation card appearing over a plain conversation.
/// </summary>
public sealed record PlanDecision(string Thought, List<ChatPlanAction> Actions, string? Error);

/// <summary>What running the approved subset did. <paramref name="Error"/> is the plan
/// never running at all — expired, or already run once.</summary>
public sealed record ConfirmOutcome(List<ChatPlanResult> Results, string? Error);
