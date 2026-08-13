using System.Text.Json;
using System.Text.Json.Nodes;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>What has happened to a proposed plan. The state is on the turn rather than
/// held in a dialog, because the transcript is now the record of it on both machines.</summary>
public static class PlanState
{
    /// <summary>Proposed, nobody has answered yet. The only state that shows buttons.</summary>
    public const string Pending = "pending";

    /// <summary>The approved subset was executed; <see cref="ChatPlan.Results"/> says what happened.</summary>
    public const string Ran = "ran";

    /// <summary>Dismissed without running anything.</summary>
    public const string Cancelled = "cancelled";

    /// <summary>Loaded from disk after a restart. The actions were never held across it,
    /// so it cannot be run — and saying so is better than a Run button that fails.</summary>
    public const string Expired = "expired";

    /// <summary>The decision itself failed. <see cref="ChatPlan.Error"/> names the cause.</summary>
    public const string Failed = "failed";
}

/// <summary>One action of a plan, as both clients render it. The wording is the PC's:
/// it is the side that knows what these actually press.</summary>
public sealed class ChatPlanAction
{
    public int Index { get; set; }

    public string ActionId { get; set; } = string.Empty;

    /// <summary>The full sentence — "Press ctrl + s", "Power: shutdown".</summary>
    public string Summary { get; set; } = string.Empty;

    /// <summary>Two words at most, for a button face: "Mute", "Shut down", "Type".
    /// A one-action plan is approved by pressing the thing it does, not by pressing "Run".</summary>
    public string Verb { get; set; } = string.Empty;

    /// <summary>Loses unsaved work. Takes a second confirmation on both clients.</summary>
    public bool Destructive { get; set; }
}

public sealed class ChatPlanResult
{
    public int Index { get; set; }

    public bool Ok { get; set; }

    public string Detail { get; set; } = string.Empty;
}

/// <summary>What the assistant proposed doing to the PC, attached to the reply it came with.</summary>
public sealed class ChatPlan
{
    public string Thought { get; set; } = string.Empty;

    public string State { get; set; } = PlanState.Pending;

    public string? Error { get; set; }

    public List<ChatPlanAction> Actions { get; set; } = [];

    public List<ChatPlanResult> Results { get; set; } = [];
}

/// <summary>
/// One line of the conversation.
///
/// An assistant turn carries <b>both halves of an answer</b>: the prose it streamed, and —
/// if the same question also mapped onto something this PC can do — the plan it proposed.
/// They are one turn rather than two because they answer one question, and because a card
/// that arrives detached from the sentence it belongs to reads as a second, unrelated event.
/// </summary>
public sealed class ChatTurn
{
    public string Id { get; set; } = string.Empty;

    /// <summary>"user" or "assistant". The two roles a transcript can contain; the system
    /// prompt is added on the way upstream and never stored.</summary>
    public string Role { get; set; } = string.Empty;

    public string Text { get; set; } = string.Empty;

    /// <summary>Unix milliseconds, so a client can group by day without a parser.</summary>
    public long At { get; set; }

    /// <summary>Tokens are still arriving.</summary>
    public bool Streaming { get; set; }

    /// <summary>The stream ended without saying it was finished, so this is however much
    /// arrived. Kept rather than discarded — half an answer is usually still worth
    /// reading — with Regenerate offered beside it (<c>docs/phase7-assistant.md</c> §4.4).</summary>
    public bool Incomplete { get; set; }

    /// <summary>A plan decision is in flight for this turn. A local model takes tens of
    /// seconds, so the difference between this and "no actions" has to be on screen.</summary>
    public bool Deciding { get; set; }

    /// <summary>Why nothing came back. Distinct from an incomplete reply, which has text.</summary>
    public string? Error { get; set; }

    public ChatPlan? Plan { get; set; }

    public const string User = "user";
    public const string Assistant = "assistant";
}

/// <summary>
/// The conversation, owned by the PC and persisted beside the pairing token.
///
/// <b>This is the change that makes the phone and the desktop one assistant rather than
/// two.</b> Before, the transcript lived in the phone's memory and nothing else could see
/// it; now every client renders the same list, every mutation is pushed to all of them,
/// and closing the app does not lose it.
///
/// <c>docs/phase7-assistant.md</c> §7 said history "stays on the PC, not synced anywhere,
/// and wipeable in one action". Two of those three are unchanged — it is still only on
/// this machine, and <see cref="Clear"/> is still one action. What changed is that it now
/// survives a restart, which was §11.5's open question, answered.
/// </summary>
public sealed class AiConversation
{
    /// <summary>Turns kept. A chat on a phone screen is scrolled, not searched, and the
    /// whole list is sent to a client on connect — so it has to stay a thing that can be
    /// sent on connect.</summary>
    private const int MaxTurns = 200;

    /// <summary>Wire and file both. camelCase because the rest of the control socket is
    /// lowercase-first, and case-insensitive on the way in so a file written by an older
    /// build still loads.</summary>
    private static readonly JsonSerializerOptions Wire = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    private static readonly JsonSerializerOptions Pretty = new(Wire) { WriteIndented = true };

    private readonly string path;
    private readonly List<ChatTurn> turns = [];
    private readonly object gate = new();

    private long counter;

    public AiConversation(string? path = null)
    {
        this.path = path ?? DefaultPath;
        Load();
    }

    public static string DefaultPath => Path.Combine(ServerConfig.ConfigDirectory, "chat.json");

    /// <summary>A turn was added or changed. Raised on whatever thread mutated it —
    /// subscribers on a UI thread must marshal themselves.</summary>
    public event Action<ChatTurn>? TurnChanged;

    /// <summary>More text for a turn already on screen. Separate from
    /// <see cref="TurnChanged"/> because a reply is one turn growing a few hundred times,
    /// and re-sending the whole turn for each token would put the transcript on the wire
    /// once per word.</summary>
    public event Action<string, string>? Delta;

    /// <summary>The whole list changed — cleared, or trimmed. Clients re-render.</summary>
    public event Action? Reset;

    /// <summary>Everything, for a client that just connected.</summary>
    public object Snapshot()
    {
        lock (gate) return new { t = "ai_chat", turns = ToNode(turns) };
    }

    public static object TurnMessage(ChatTurn turn) =>
        new { t = "ai_turn", turn = JsonSerializer.SerializeToNode(turn, Wire) };

    public static object DeltaMessage(string id, string text) => new { t = "ai_delta", id, text };

    /// <summary>A copy, safe to read off the lock.</summary>
    public IReadOnlyList<ChatTurn> Turns()
    {
        lock (gate) return turns.ToList();
    }

    public ChatTurn? Find(string id)
    {
        lock (gate) return turns.FirstOrDefault(t => t.Id == id);
    }

    /// <summary>True while a reply is still arriving — one ask at a time, on either client.</summary>
    public bool Busy
    {
        get { lock (gate) return turns.Any(t => t.Streaming || t.Deciding); }
    }

    public ChatTurn Append(string role, string text, bool streaming = false, bool deciding = false)
    {
        ChatTurn turn;
        bool trimmed;
        lock (gate)
        {
            turn = new ChatTurn
            {
                Id = $"t{++counter}",
                Role = role,
                Text = text,
                At = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Streaming = streaming,
                Deciding = deciding
            };
            turns.Add(turn);
            trimmed = Trim();
            Save();
        }

        // Raised off the lock: a subscriber is a WinForms window or a broadcast to every
        // open socket, and neither should be able to hold the transcript while it works.
        if (trimmed) Reset?.Invoke(); else TurnChanged?.Invoke(turn);
        return turn;
    }

    /// <summary>Change a turn in place and tell everyone. No-op for an id that has been
    /// trimmed away, which is the honest answer to a decision landing for a conversation
    /// somebody already scrolled past.</summary>
    public void Update(string id, Action<ChatTurn> change)
    {
        ChatTurn? turn;
        lock (gate)
        {
            turn = turns.FirstOrDefault(t => t.Id == id);
            if (turn is null) return;
            change(turn);
            Save();
        }

        TurnChanged?.Invoke(turn);
    }

    /// <summary>
    /// Append streamed text to a turn already on the list. Deliberately does <b>not</b>
    /// save: a reply is hundreds of these, and the file is written once when the stream ends.
    ///
    /// Named apart from <see cref="Append(string,string,bool,bool)"/> on purpose — as an
    /// overload it took the two-argument calls that meant "add a turn", because C# prefers
    /// the signature with no optional parameters to fill. Every user message went into a
    /// turn id that did not exist and vanished.
    /// </summary>
    public void AppendDelta(string id, string text)
    {
        lock (gate)
        {
            var turn = turns.FirstOrDefault(t => t.Id == id);
            if (turn is null) return;
            turn.Text += text;
        }

        Delta?.Invoke(id, text);
    }

    /// <summary>
    /// Drop everything after the last thing the user said, and hand that back.
    ///
    /// What Regenerate is: the user is saying <i>that answer</i> was wrong or cut off, and
    /// two attempts at one question stacked on top of each other is a transcript nobody
    /// wants to read. Returns null when there is nothing to re-ask.
    /// </summary>
    public string? DropTrailingAssistant()
    {
        string? goal;
        lock (gate)
        {
            while (turns.Count > 0 && turns[^1].Role != ChatTurn.User) turns.RemoveAt(turns.Count - 1);
            goal = turns.Count > 0 ? turns[^1].Text : null;
            Save();
        }

        Reset?.Invoke();
        return goal;
    }

    public void Clear()
    {
        lock (gate)
        {
            turns.Clear();
            Save();
        }

        Reset?.Invoke();
    }

    /// <summary>
    /// The conversation as agent-platform wants it, newest last.
    ///
    /// A plan that ran is folded in as a line of its own so a follow-up ("did that work?",
    /// "do it again") has something to refer to — the model never saw the buttons, only
    /// what it said, and without this the most consequential thing in the conversation is
    /// the one thing missing from it.
    /// </summary>
    public List<(string Role, string Content)> ForUpstream(int maxMessages, int maxChars)
    {
        var messages = new List<(string, string)>();
        foreach (var turn in Turns())
        {
            if (turn.Role is not (ChatTurn.User or ChatTurn.Assistant)) continue;

            var text = turn.Text;
            if (turn.Plan is { State: PlanState.Ran, Results.Count: > 0 } plan)
            {
                var ran = string.Join("; ", plan.Results.Select(r => $"{r.Detail}{(r.Ok ? string.Empty : " (failed)")}"));
                text = string.IsNullOrWhiteSpace(text) ? $"[ran on the PC: {ran}]" : $"{text}\n\n[ran on the PC: {ran}]";
            }

            if (string.IsNullOrWhiteSpace(text)) continue;
            if (text.Length > maxChars) text = text[..maxChars];
            messages.Add((turn.Role, text));
        }

        // Oldest first is what gets dropped: the newest turns are the ones the answer
        // depends on, and the system prompt is added above this, not stored in it.
        if (messages.Count > maxMessages) messages.RemoveRange(0, messages.Count - maxMessages);
        return messages;
    }

    /// <summary>True if anything was dropped, which the caller answers with a full
    /// re-render — an upsert cannot express "and these are gone".</summary>
    private bool Trim()
    {
        if (turns.Count <= MaxTurns) return false;
        turns.RemoveRange(0, turns.Count - MaxTurns);
        return true;
    }

    private JsonNode? ToNode(List<ChatTurn> list) => JsonSerializer.SerializeToNode(list, Wire);

    private void Load()
    {
        try
        {
            if (!System.IO.File.Exists(path)) return;
            var stored = JsonSerializer.Deserialize<List<ChatTurn>>(System.IO.File.ReadAllText(path), Wire);
            if (stored is null) return;
            turns.AddRange(stored);
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // A corrupt transcript must not stop the server starting, and the worst case
            // is a conversation nobody can scroll back through.
            turns.Clear();
        }

        foreach (var turn in turns)
        {
            // Nothing survives a restart mid-flight: the SSE socket is gone and the
            // plan's parameters were only ever in AiActions' memory. Saying so is better
            // than a spinner that never resolves or a Run button that fails.
            if (turn.Streaming) { turn.Streaming = false; turn.Incomplete = turn.Text.Length > 0; }
            turn.Deciding = false;
            if (turn.Plan is { State: PlanState.Pending } plan) plan.State = PlanState.Expired;
        }

        counter = turns.Count;
        // Ids must not collide with the ones just loaded, and `t<n>` restarting at 1
        // after a restart would upsert onto an old turn on a client that never dropped.
        foreach (var turn in turns)
            if (turn.Id.StartsWith('t') && long.TryParse(turn.Id[1..], out var n) && n > counter)
                counter = n;
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            System.IO.File.WriteAllText(path, JsonSerializer.Serialize(turns, Pretty));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Losing the log is not worth failing the turn the user is having.
        }
    }
}
