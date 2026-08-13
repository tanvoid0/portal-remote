using System.Diagnostics;
using System.Text;
using PortalRemote.Config;

namespace PortalRemote.Ai;

/// <summary>
/// One assistant, shared by every client — the phone, the desktop window, a second phone.
///
/// <b>There is one Send button now, and this is why.</b> Asking a question and asking for
/// the PC to be touched used to be two buttons because guessing which was meant is a guess
/// that presses keys when it is wrong. It still is — so nothing here guesses. Every message
/// goes to <i>both</i> halves at once: the reply streams from
/// <c>/v1/chat/completions</c> while <c>/api/v1/decide</c> works out whether the same
/// sentence maps onto something this PC can do. Whichever has an answer says so, and the
/// safety property that made the old split defensible is untouched — <b>a decision is not
/// an execution</b>, so a plan is still only ever proposed, and still only runs when
/// somebody approves it (<c>docs/phase7-assistant.md</c> §7).
///
/// The cost of asking both is one extra <c>/decide</c> per turn, which is a call that
/// already had to be safe to retry.
/// </summary>
public sealed class AiAssistant : IDisposable
{
    /// <summary>
    /// How often streamed text is pushed out, rather than once per token.
    ///
    /// The deltas go over the control socket — the one carrying mouse movement — and a
    /// frame per token is a few hundred frames per reply per client. At this interval it
    /// is a few dozen, and no human reads the difference.
    /// </summary>
    private const int FlushMs = 80;

    private readonly ServerConfig config;
    private readonly AiHealth health;
    private readonly AiActions actions;
    private readonly AiChatClient chat = new();

    /// <summary>What is playing right now, so "pause it" has something to refer to. A
    /// callback rather than the media session itself: this class has no other reason to
    /// know that <c>NowPlaying</c> exists.</summary>
    private readonly Func<object?> context;

    private readonly object gate = new();
    private CancellationTokenSource? inFlight;

    public AiAssistant(
        ServerConfig config, AiHealth health, AiActions actions, Func<object?> context, AiConversation? conversation = null)
    {
        this.config = config;
        this.health = health;
        this.actions = actions;
        this.context = context;
        Conversation = conversation ?? new AiConversation();
    }

    public AiConversation Conversation { get; }

    /// <summary>A reply is in flight. One at a time across every client — two answers
    /// growing in one transcript is a transcript nobody can read.</summary>
    public bool Busy
    {
        get { lock (gate) return inFlight is not null; }
    }

    /// <summary>
    /// Someone said something. Returns immediately; everything after this arrives as
    /// pushes, which is what lets the phone and the desktop watch the same reply.
    /// </summary>
    public void Ask(string text)
    {
        var message = text.Trim();
        if (message.Length == 0) return;
        if (message.Length > AiChatClient.MaxContentChars) message = message[..AiChatClient.MaxContentChars];
        if (!Begin(out var ct)) return;

        Conversation.Append(ChatTurn.User, message);
        Reply(message, ct);
    }

    /// <summary>
    /// Ask again for the last reply, dropping the previous one rather than stacking a
    /// second attempt under the same question (§4.4).
    /// </summary>
    public void Regenerate()
    {
        if (!Begin(out var ct)) return;

        var goal = Conversation.DropTrailingAssistant();
        if (goal is null)
        {
            End();
            return;
        }

        Reply(goal, ct);
    }

    /// <summary>Stop the reply where it is. A deliberate stop is not a failure, so what
    /// arrived stands as a whole answer rather than being flagged as cut off.</summary>
    public void Stop()
    {
        lock (gate) inFlight?.Cancel();
    }

    /// <summary>
    /// Run the actions somebody ticked, by index.
    ///
    /// Executed inline: these are the same key presses the buttons send and they are as
    /// fast. Running a plan only once is <see cref="AiActions"/>'s guarantee, not this
    /// one's — a reconnect-and-resend must not do it twice.
    /// </summary>
    public void Confirm(string id, IReadOnlyList<int> approved)
    {
        if (Conversation.Find(id)?.Plan is not { State: PlanState.Pending }) return;

        if (approved.Count == 0)
        {
            Cancel(id);
            return;
        }

        var outcome = actions.Confirm(id, approved);
        Conversation.Update(id, turn =>
        {
            if (turn.Plan is not { } plan) return;
            plan.State = outcome.Error is null ? PlanState.Ran : PlanState.Failed;
            plan.Error = outcome.Error;
            plan.Results = outcome.Results;
        });
    }

    /// <summary>Dismiss without running anything. Recorded rather than erased: a card that
    /// simply vanished reads as one that quietly went ahead.</summary>
    public void Cancel(string id) => Conversation.Update(id, turn =>
    {
        if (turn.Plan is { State: PlanState.Pending } plan) plan.State = PlanState.Cancelled;
    });

    /// <summary>Wipe the conversation on every device at once — §7's "wipeable in one
    /// action", now that there is more than one place it is shown.</summary>
    public void Clear()
    {
        Stop();
        Conversation.Clear();
    }

    private bool Begin(out CancellationToken ct)
    {
        lock (gate)
        {
            if (inFlight is not null)
            {
                ct = default;
                return false;
            }

            inFlight = new CancellationTokenSource();
            ct = inFlight.Token;
            return true;
        }
    }

    private void End()
    {
        lock (gate)
        {
            inFlight?.Dispose();
            inFlight = null;
        }
    }

    /// <summary>
    /// One turn: the prose and the decision, started together.
    ///
    /// The turn is appended empty and grown in place, so tokens appear as they arrive on
    /// every client at once. <c>deciding</c> starts true because a local model takes tens
    /// of seconds to answer the tool prompt, and "still thinking" and "there was nothing to
    /// do" have to look different while that is happening.
    /// </summary>
    private void Reply(string goal, CancellationToken ct)
    {
        var turn = Conversation.Append(ChatTurn.Assistant, string.Empty, streaming: true, deciding: true);

        _ = Task.Run(async () =>
        {
            try
            {
                // Ask before dialling twice. A client should not have been able to get
                // here with the backend down — it is told the state — but "should not" is
                // not "cannot", and the health model's own sentence names the fix.
                await health.CheckAsync(userAsked: false, ct);
                if (health.State != AiHealth.Ready)
                {
                    Conversation.Update(turn.Id, t =>
                    {
                        t.Streaming = false;
                        t.Deciding = false;
                        t.Error = health.Detail ?? "The assistant's backend is not running.";
                    });
                    return;
                }

                await Task.WhenAll(StreamAsync(turn.Id, ct), DecideAsync(turn.Id, goal, ct));
            }
            catch (Exception ex)
            {
                Conversation.Update(turn.Id, t =>
                {
                    t.Streaming = false;
                    t.Deciding = false;
                    t.Error ??= ex.Message;
                });
            }
            finally
            {
                End();
            }
        }, CancellationToken.None);
    }

    private async Task StreamAsync(string id, CancellationToken ct)
    {
        var messages = Conversation.ForUpstream(AiChatClient.MaxMessages, AiChatClient.MaxContentChars);

        var buffer = new StringBuilder();
        var since = Stopwatch.StartNew();

        void Flush()
        {
            if (buffer.Length == 0) return;
            Conversation.AppendDelta(id, buffer.ToString());
            buffer.Clear();
            since.Restart();
        }

        var outcome = await chat.StreamAsync(config.AgentPlatform, messages, text =>
        {
            buffer.Append(text);
            if (since.ElapsedMilliseconds >= FlushMs) Flush();
        }, ct);
        Flush();

        Conversation.Update(id, turn =>
        {
            turn.Streaming = false;
            // Ending without the terminator is the only signal for a cut-off reply: a
            // finished stream and a dropped one both look like lines that stopped arriving.
            turn.Incomplete = !outcome.Done && turn.Text.Length > 0;
            if (outcome.Error is not null) turn.Error = outcome.Error;
        });
    }

    private async Task DecideAsync(string id, string goal, CancellationToken ct)
    {
        var decision = await actions.DecideAsync(id, goal, context(), ct);

        Conversation.Update(id, turn =>
        {
            turn.Deciding = false;

            // A deliberate Stop is not a failed decision, and neither is a decision that
            // simply found nothing on this PC to do — the reply above it is the answer.
            if (ct.IsCancellationRequested) return;

            if (decision.Error is not null)
            {
                turn.Plan = new ChatPlan
                {
                    Thought = decision.Thought,
                    State = PlanState.Failed,
                    Error = decision.Error
                };
                return;
            }

            if (decision.Actions.Count == 0) return;

            turn.Plan = new ChatPlan
            {
                Thought = decision.Thought,
                State = PlanState.Pending,
                Actions = decision.Actions
            };
        });
    }

    public void Dispose()
    {
        lock (gate)
        {
            inFlight?.Cancel();
            inFlight?.Dispose();
            inFlight = null;
        }
    }
}
