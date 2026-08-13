using PortalRemote.Config;
using PortalRemote.Share;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The thread the desktop window draws — <see cref="ShareHub"/>'s history. Direction
/// is the part worth pinning: the window puts incoming on the left and outgoing on
/// the right, and the hub is the only thing that knows which is which.
/// </summary>
public class ShareHubTests
{
    private static ShareItem Note(string text) =>
        new(ShareKind.ForText(text), text, null, text.Length, "test", DateTimeOffset.Now);

    private static ShareHub NewHub() => new(new ServerConfig());

    [Fact]
    public async Task RecordsBothDirectionsInOrder()
    {
        var hub = NewHub();

        hub.Publish(Note("from the phone"));
        // No sockets are connected, so this sends to nobody — and still has to appear
        // in the thread, because the user typed it.
        await hub.SendToPhonesAsync(Note("from the PC"));

        Assert.Equal(
            new (string?, bool)[] { ("from the phone", true), ("from the PC", false) },
            hub.History.Select(e => (e.Item.Text, e.Incoming)));
    }

    [Fact]
    public async Task RaisesAddedForEachShare()
    {
        var hub = NewHub();
        var seen = new List<ShareEntry>();
        hub.Added += seen.Add;

        hub.Publish(Note("one"));
        await hub.SendToPhonesAsync(Note("two"));

        Assert.Equal(new[] { "one", "two" }, seen.Select(e => e.Item.Text));
    }

    [Fact]
    public void KeepsTheNewestFiftyAndDropsTheRest()
    {
        var hub = NewHub();
        for (var n = 0; n < 60; n++) hub.Publish(Note($"note {n}"));

        var history = hub.History;
        Assert.Equal(50, history.Count);
        Assert.Equal("note 10", history[0].Item.Text);
        Assert.Equal("note 59", history[^1].Item.Text);
    }

    [Fact]
    public void PublishStillReachesTheTrayIconsClipboardHandler()
    {
        // History is additional to Received, not a replacement for it: the balloon and
        // the clipboard write hang off that one.
        var hub = NewHub();
        ShareItem? received = null;
        hub.Received += item => received = item;

        hub.Publish(Note("hello"));

        Assert.Equal("hello", received?.Text);
    }
}
