using System.Text.Json;
using PortalRemote.Ai;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The two pure halves of step 7c (<c>docs/phase7-assistant.md</c>): what survives
/// validation, and what the survivors turn into.
///
/// Both are places where being wrong is silent and expensive. A model's action list is
/// untrusted input — <c>/decide</c> has text-parsing fallbacks, so a malformed action is a
/// realistic case (§7) — and the mapping is what decides which key actually gets pressed
/// on a machine in another room. Neither shows up as an exception.
/// </summary>
public class AiActionsTests
{
    private static JsonElement Decision(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void KeepsAKnownActionWithItsParameters()
    {
        var actions = AiActions.Validate(Decision(
            """
            {"thought":"…","actions":[
              {"action_id":"media_control","parameters":{"action":"play_pause"}}]}
            """));

        var action = Assert.Single(actions);
        Assert.Equal("media_control", action.ActionId);
        Assert.Equal("Media key: play_pause", action.Summary);
        Assert.False(action.Destructive);
    }

    [Fact]
    public void DropsAnActionThisPcDoesNotHave()
    {
        // A hallucinated capability must never reach the confirmation sheet: approving it
        // is one tap, and the tap is the only gate there is.
        var actions = AiActions.Validate(Decision(
            """{"actions":[{"action_id":"send_email","parameters":{"to":"a@b.c"}}]}"""));

        Assert.Empty(actions);
    }

    [Fact]
    public void DropsAnActionMissingTheParameterItCannotRunWithout()
    {
        var actions = AiActions.Validate(Decision(
            """{"actions":[{"action_id":"type_text","parameters":{}},{"action_id":"power"}]}"""));

        Assert.Empty(actions);
    }

    [Fact]
    public void FlagsThePowerModesThatLoseUnsavedWork()
    {
        var actions = AiActions.Validate(Decision(
            """
            {"actions":[
              {"action_id":"power","parameters":{"mode":"lock"}},
              {"action_id":"power","parameters":{"mode":"shutdown"}}]}
            """));

        Assert.Equal(2, actions.Count);
        Assert.False(actions[0].Destructive);
        Assert.True(actions[1].Destructive);
    }

    [Fact]
    public void MapsOntoTheMessagesTheButtonsAlreadySend()
    {
        var actions = AiActions.Validate(Decision(
            """
            {"actions":[
              {"action_id":"press_keys","parameters":{"keys":["ctrl","s"]}},
              {"action_id":"cast_url","parameters":{"url":"http://example/v.mp4","title":"V"}},
              {"action_id":"player_transport","parameters":{"action":"seek","to":90}}]}
            """));

        Assert.Equal(
            """{"t":"combo","keys":["ctrl","s"]}""",
            JsonSerializer.Serialize(AiActions.ToMessage(actions[0])));
        Assert.Equal(
            """{"t":"cast","url":"http://example/v.mp4","title":"V"}""",
            JsonSerializer.Serialize(AiActions.ToMessage(actions[1])));
        // Absent transport parameters stay absent rather than becoming zeroes — a seek
        // that quietly meant "to the start" would be a very confusing way to fail.
        Assert.Equal(
            """{"t":"player","action":"seek","to":90,"by":null,"level":null,"muted":null}""",
            JsonSerializer.Serialize(AiActions.ToMessage(actions[2])));
    }

    [Fact]
    public void ReadsASingleKeyAsAListOfOne()
    {
        // Answering "press escape" with a string rather than a list is a model being
        // reasonable about English, not a malformed action.
        var actions = AiActions.Validate(Decision(
            """{"actions":[{"action_id":"press_keys","parameters":{"keys":"esc"}}]}"""));

        Assert.Equal(
            """{"t":"combo","keys":["esc"]}""",
            JsonSerializer.Serialize(AiActions.ToMessage(Assert.Single(actions))));
    }
}
