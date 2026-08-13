using PortalRemote.Ai;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The SSE parsing that fills the transcript, moved onto this machine when the
/// conversation stopped being the phone's. Being wrong here is silent: a reply that looks
/// finished when it was cut off, or one killed at its first frame.
/// </summary>
public class AiChatClientTests
{
    // Three '$' so the two closing braces this JSON ends with stay literal.
    private static string Delta(string content) =>
        $$$"""data: {"choices":[{"delta":{"content":"{{{content}}}"}}]}""";

    [Fact]
    public void ReadsTheTextOutOfADeltaFrame()
    {
        Assert.Equal(new ChatFrame.Delta("Hello"), AiChatClient.Parse(Delta("Hello")));
    }

    [Fact]
    public void TheTerminatorIsWhatMakesAReplyWhole()
    {
        // Nothing else distinguishes a finished stream from a dropped one — both are just
        // a socket that stopped producing lines.
        Assert.IsType<ChatFrame.Done>(AiChatClient.Parse("data: [DONE]"));
    }

    [Fact]
    public void BlankLinesAndCommentsCarryNothing()
    {
        // SSE separates events with a blank line and keeps connections alive with `:`
        // comments. Treating either as an empty delta would append nothing forever.
        Assert.IsType<ChatFrame.Ignore>(AiChatClient.Parse(string.Empty));
        Assert.IsType<ChatFrame.Ignore>(AiChatClient.Parse(": keep-alive"));
        Assert.IsType<ChatFrame.Ignore>(AiChatClient.Parse("event: message"));
    }

    [Fact]
    public void TheRoleOnlyOpeningChunkIsNotText()
    {
        // OpenAI's first chunk announces the role and carries no content. Treating a
        // missing `content` as a parse failure would kill every reply at its first frame.
        Assert.IsType<ChatFrame.Ignore>(
            AiChatClient.Parse("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""));
    }

    [Fact]
    public void AnUnparseableFrameIsSkippedNotFatal()
    {
        Assert.IsType<ChatFrame.Ignore>(AiChatClient.Parse("data: {not json"));
        Assert.IsType<ChatFrame.Ignore>(AiChatClient.Parse("""data: {"choices":[]}"""));
    }

    [Fact]
    public void WhitespaceAfterTheColonIsNotPartOfTheText()
    {
        Assert.Equal(
            new ChatFrame.Delta("hi"),
            AiChatClient.Parse("""data:{"choices":[{"delta":{"content":"hi"}}]}"""));
    }

    [Fact]
    public void AFailurePrefersTheUpstreamSentenceOverTheStatusCode()
    {
        Assert.Equal("model not found", AiChatClient.Describe(404, """{"detail":"model not found"}"""));
        Assert.Equal("no provider", AiChatClient.Describe(400, """{"error":"no provider"}"""));
    }

    [Fact]
    public void A401NamesTheFixRatherThanTheNumber()
    {
        // It has exactly one cause — agent-platformd has a master key set and we have no
        // token — and exactly one fix, and this sentence ends up on a phone screen.
        var message = AiChatClient.Describe(401, """{"error":"unauthorized"}""");

        Assert.Contains("Mint a token", message);
        Assert.Contains("AgentPlatform.Token", message);
    }

    [Fact]
    public void AFailureWithNoUsableBodyStillSaysSomething()
    {
        Assert.Contains("502", AiChatClient.Describe(502, null));
        Assert.Contains("500", AiChatClient.Describe(500, "<html>oops</html>"));
    }
}

/// <summary>
/// The transcript the PC now owns. The interesting cases are all about what is <i>not</i>
/// true any more after a restart, and about a history that must not quietly lose the
/// question an answer belongs to.
/// </summary>
public class AiConversationTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"portal-chat-{Guid.NewGuid():n}.json");

    public void Dispose()
    {
        if (File.Exists(_path)) File.Delete(_path);
        GC.SuppressFinalize(this);
    }

    [Fact]
    public void ATurnSurvivesARestart()
    {
        // The whole point of the file: a conversation that disappears when the tray app is
        // restarted is not one anybody can refer back to.
        var first = new AiConversation(_path);
        first.Append(ChatTurn.User, "what is playing?");
        first.Append(ChatTurn.Assistant, "Nothing.");

        var reloaded = new AiConversation(_path).Turns();

        Assert.Equal(2, reloaded.Count);
        Assert.Equal("what is playing?", reloaded[0].Text);
        Assert.Equal(ChatTurn.Assistant, reloaded[1].Role);
    }

    [Fact]
    public void NothingComesBackFromDiskStillInFlight()
    {
        // The SSE socket is gone and the plan's parameters were only ever in AiActions'
        // memory, so a spinner that never resolves and a Run button that cannot run are
        // both worse than saying so.
        var live = new AiConversation(_path);
        var turn = live.Append(ChatTurn.Assistant, "half an ans", streaming: true, deciding: true);
        live.Update(turn.Id, t => t.Plan = new ChatPlan { State = PlanState.Pending });

        var reloaded = new AiConversation(_path).Turns().Single();

        Assert.False(reloaded.Streaming);
        Assert.False(reloaded.Deciding);
        Assert.True(reloaded.Incomplete);
        Assert.Equal(PlanState.Expired, reloaded.Plan!.State);
    }

    [Fact]
    public void IdsDoNotRestartAndCollideWithTheOnesAlreadyOnDisk()
    {
        // A client that never dropped its copy would upsert the new turn onto an old one.
        var first = new AiConversation(_path);
        first.Append(ChatTurn.User, "one");
        first.Append(ChatTurn.Assistant, "two");

        var reloaded = new AiConversation(_path);
        var next = reloaded.Append(ChatTurn.User, "three");

        Assert.DoesNotContain(reloaded.Turns().Take(2), t => t.Id == next.Id);
    }

    [Fact]
    public void ADeltaGrowsTheTurnItNamesAndRaisesOnlyADelta()
    {
        var conversation = new AiConversation(_path);
        var turn = conversation.Append(ChatTurn.Assistant, "Wor", streaming: true);

        var turnChanges = 0;
        var deltas = new List<string>();
        conversation.TurnChanged += _ => turnChanges++;
        conversation.Delta += (_, text) => deltas.Add(text);

        conversation.AppendDelta(turn.Id, "king");
        conversation.AppendDelta("t999", "stray");

        Assert.Equal(0, turnChanges);
        Assert.Equal(new[] { "king" }, deltas);
        Assert.Equal("Working", conversation.Find(turn.Id)!.Text);
    }

    [Fact]
    public void WhatRanIsFoldedIntoTheHistoryTheModelSees()
    {
        // The model never saw the buttons, only what it said. Without this the most
        // consequential thing in the conversation is the one thing missing from it.
        var conversation = new AiConversation(_path);
        conversation.Append(ChatTurn.User, "mute it");
        var reply = conversation.Append(ChatTurn.Assistant, "Muting.");
        conversation.Update(reply.Id, t => t.Plan = new ChatPlan
        {
            State = PlanState.Ran,
            Results = [new ChatPlanResult { Index = 0, Ok = true, Detail = "Media key: mute" }],
        });

        var messages = conversation.ForUpstream(100, 16384);

        Assert.Equal(2, messages.Count);
        Assert.Contains("ran on the PC: Media key: mute", messages[1].Content);
    }

    [Fact]
    public void TheHistorySentUpstreamDropsTheOldestRatherThanTheNewest()
    {
        var conversation = new AiConversation(_path);
        for (var i = 0; i < 10; i++) conversation.Append(ChatTurn.User, $"message {i}");

        var messages = conversation.ForUpstream(maxMessages: 3, maxChars: 16384);

        Assert.Equal(3, messages.Count);
        Assert.Equal("message 7", messages[0].Content);
        Assert.Equal("message 9", messages[^1].Content);
    }

    [Fact]
    public void RegenerateDropsTheAnswerAndHandsBackTheQuestion()
    {
        var conversation = new AiConversation(_path);
        conversation.Append(ChatTurn.User, "why?");
        conversation.Append(ChatTurn.Assistant, "a wrong answer");

        var goal = conversation.DropTrailingAssistant();

        Assert.Equal("why?", goal);
        Assert.Equal("why?", conversation.Turns().Single().Text);
    }

    [Fact]
    public void RegenerateWithNothingToReAskSaysSo()
    {
        Assert.Null(new AiConversation(_path).DropTrailingAssistant());
    }

    [Fact]
    public void BusyIsReadOffTheTranscriptRatherThanTrackedTwice()
    {
        var conversation = new AiConversation(_path);
        Assert.False(conversation.Busy);

        var turn = conversation.Append(ChatTurn.Assistant, string.Empty, streaming: true);
        Assert.True(conversation.Busy);

        conversation.Update(turn.Id, t => t.Streaming = false);
        Assert.False(conversation.Busy);
    }

    [Fact]
    public void ClearingWipesTheFileAsWellAsTheList()
    {
        var conversation = new AiConversation(_path);
        conversation.Append(ChatTurn.User, "something private");
        conversation.Clear();

        Assert.Empty(conversation.Turns());
        Assert.Empty(new AiConversation(_path).Turns());
        Assert.DoesNotContain("something private", File.ReadAllText(_path));
    }
}
