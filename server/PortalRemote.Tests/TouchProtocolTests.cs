using System.Text.Json;
using PortalRemote.Input;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The parsing behind the `touch` message (real Win32 touch injection, not a mouse
/// pretending to be one — see docs/design-system.md and WinInput.Touch). Split from
/// dispatch specifically so this is testable: actually injecting a touch requires a
/// real Windows desktop, which this suite doesn't have.
/// </summary>
public class TouchProtocolTests
{
    private static JsonElement Parse(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void ReadsEveryContactField()
    {
        var contacts = InputActions.ParseTouchContacts(Parse(
            """
            { "t": "touch", "contacts": [
                { "id": 0, "nx": 0.25, "ny": 0.75, "phase": "down" },
                { "id": 1, "nx": 0.5, "ny": 0.5, "phase": "move" }
            ]}
            """));

        Assert.Equal(2, contacts.Count);
        Assert.Equal(0u, contacts[0].Id);
        Assert.Equal(0.25, contacts[0].Nx);
        Assert.Equal(0.75, contacts[0].Ny);
        Assert.Equal(WinInput.TouchPhase.Down, contacts[0].Phase);
        Assert.Equal(WinInput.TouchPhase.Move, contacts[1].Phase);
    }

    [Fact]
    public void UpPhaseParses()
    {
        var contacts = InputActions.ParseTouchContacts(Parse(
            """{ "t": "touch", "contacts": [{ "id": 3, "nx": 0.1, "ny": 0.1, "phase": "up" }] }"""));

        Assert.Equal(WinInput.TouchPhase.Up, contacts[0].Phase);
    }

    [Fact]
    public void MissingContactsIsEmptyNotAnError()
    {
        // Dispatch is what rejects an empty list (a `touch` with nothing in it is
        // pointless); the parser itself just reports what it found.
        var contacts = InputActions.ParseTouchContacts(Parse("""{ "t": "touch" }"""));
        Assert.Empty(contacts);
    }

    [Theory]
    [InlineData("""{"nx": 0.1, "ny": 0.1, "phase": "down"}""")] // missing id
    [InlineData("""{"id": 0, "ny": 0.1, "phase": "down"}""")] // missing nx
    [InlineData("""{"id": 0, "nx": 0.1, "phase": "down"}""")] // missing ny
    [InlineData("""{"id": 0, "nx": 0.1, "ny": 0.1}""")] // missing phase
    [InlineData("""{"id": 0, "nx": 0.1, "ny": 0.1, "phase": "sideways"}""")] // unknown phase
    [InlineData("""{"id": 10, "nx": 0.1, "ny": 0.1, "phase": "down"}""")] // id out of 0..9
    [InlineData("""{"id": -1, "nx": 0.1, "ny": 0.1, "phase": "down"}""")] // id out of 0..9
    public void RejectsMalformedContacts(string contact)
    {
        var msg = Parse($$"""{ "t": "touch", "contacts": [{{contact}}] }""");
        Assert.Throws<UnknownMessageException>(() => InputActions.ParseTouchContacts(msg));
    }
}
