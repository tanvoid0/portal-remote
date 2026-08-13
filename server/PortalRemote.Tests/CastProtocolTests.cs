using PortalRemote.Cast;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The pure parsing behind the Roku and DLNA senders — steps 4i and 4j of
/// <c>docs/phase4-casting.md</c>. These are the parts where being wrong is silent: a
/// misread position parks the phone's scrub bar somewhere the film isn't, and a device
/// description read wrong means a TV that answered the search never appears in the
/// picker. Neither shows up as an exception.
///
/// The XML samples are shapes taken from the ECP and UPnP specs, not captures from a
/// specific device — driving real hardware is still outstanding.
/// </summary>
public class RokuTests
{
    [Fact]
    public void ReadsPositionAndDuration()
    {
        var status = RokuPlayer.ParseStatus(
            """
            <player error="false" state="play">
              <plugin id="2213" name="Roku Media Player"/>
              <position>93000 ms</position>
              <duration>3600000 ms</duration>
            </player>
            """);

        Assert.NotNull(status);
        // Milliseconds on the wire, seconds in the status the phone already understands.
        Assert.Equal(93, status!.Position);
        Assert.Equal(3600, status.Duration);
        Assert.True(status.Playing);
        Assert.False(status.Paused);
    }

    [Fact]
    public void BufferingCountsAsPaused()
    {
        // Not paused in the Roku's vocabulary, but not advancing either — and the phone
        // interpolates the playhead forward whenever it thinks something is playing.
        var status = RokuPlayer.ParseStatus("""<player state="buffer"><position>0 ms</position></player>""");

        Assert.True(status!.Playing);
        Assert.True(status.Paused);
    }

    [Fact]
    public void AClosedChannelIsNotPlaying()
    {
        // What the Roku reports once you leave the media player. It must take the
        // phone's transport away, not leave it driving nothing.
        var status = RokuPlayer.ParseStatus("""<player error="false" state="close"/>""");

        Assert.NotNull(status);
        Assert.False(status!.Playing);
    }

    [Fact]
    public void ErrorIsCarried()
    {
        var status = RokuPlayer.ParseStatus("""<player error="true" state="play"/>""");
        Assert.True(status!.Error);
    }

    [Theory]
    [InlineData("not xml at all")]
    [InlineData("<something-else/>")]
    public void RubbishIsNullRatherThanZero(string body)
    {
        // A zeroed status would pin the bar at 0:00 and claim that is where the film is.
        Assert.Null(RokuPlayer.ParseStatus(body));
    }

    [Fact]
    public void PrefersTheNameTheOwnerSet()
    {
        // A house with two Rokus needs "Bedroom", not two rows called Roku Express.
        var name = RokuPlayer.FriendlyName(
            """
            <device-info>
              <user-device-name>Bedroom</user-device-name>
              <friendly-device-name>Roku Express - Bedroom</friendly-device-name>
              <model-name>Roku Express</model-name>
            </device-info>
            """);

        Assert.Equal("Bedroom", name);
    }

    [Fact]
    public void FallsBackDownTheNameChain()
    {
        var name = RokuPlayer.FriendlyName("<device-info><model-name>Roku Ultra</model-name></device-info>");
        Assert.Equal("Roku Ultra", name);
    }

    [Theory]
    [InlineData("https://example.com/a/clip.mp4", "mp4")]
    [InlineData("https://example.com/live/index.m3u8", "hls")]
    [InlineData("http://192.168.1.9:8766/f/3.mkv", "mkv")]
    // A guess is worse than no guess: the Roku sniffs it when we say nothing, and
    // refuses outright when we name the wrong container.
    [InlineData("https://example.com/stream?id=7", null)]
    [InlineData("https://example.com/clip.avi", null)]
    public void GuessesTheContainerOnlyWhenSure(string url, string? expected)
    {
        Assert.Equal(expected, RokuPlayer.VideoFormat(url));
    }

    [Fact]
    public void QueryStringsDoNotConfuseTheExtension()
    {
        Assert.Equal("mp4", RokuPlayer.VideoFormat("https://cdn.example.com/v/clip.mp4?token=abc.m3u8"));
    }
}

public class DlnaTests
{
    [Theory]
    [InlineData("0:01:23", 83)]
    [InlineData("00:01:23", 83)]
    [InlineData("1:00:00", 3600)]
    [InlineData("0:00:10.500", 10.5)]
    public void ParsesUpnpTimes(string text, double expected)
    {
        Assert.Equal(expected, DlnaPlayer.ParseTime(text));
    }

    [Theory]
    [InlineData("NOT_IMPLEMENTED")]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("garbage")]
    public void UnknownTimesAreZeroNotAnException(string? text)
    {
        // A live stream has no length and several renderers say so in words.
        Assert.Equal(0, DlnaPlayer.ParseTime(text));
    }

    [Theory]
    [InlineData(83, "0:01:23")]
    [InlineData(3600, "1:00:00")]
    [InlineData(0, "0:00:00")]
    public void FormatsBackForSeek(double seconds, string expected)
    {
        Assert.Equal(expected, DlnaPlayer.FormatTime(seconds));
    }

    [Fact]
    public void RoundTripsThroughSeek()
    {
        Assert.Equal(2401, DlnaPlayer.ParseTime(DlnaPlayer.FormatTime(2401)));
    }

    [Fact]
    public void FindsBothControlUrls()
    {
        var player = DlnaPlayer.FromDescription(Description, "http://192.168.1.30:8200/desc.xml", "uuid:abc");

        Assert.NotNull(player);
        Assert.Equal("Living Room TV", player!.Name);
        Assert.Equal("dlna:uuid:abc", player.Id);
        // RenderingControl is what makes an absolute volume possible; without it the
        // phone must not offer a slider.
        Assert.True(player.Caps.Volume);
        Assert.True(player.Caps.Seek);
    }

    [Fact]
    public void NoAvTransportIsNotACastTarget()
    {
        // Plenty of UPnP devices answer a MediaRenderer search and turn out to be a
        // speaker with no transport. Listing one offers a cast that cannot happen.
        var xml = Description.Replace("urn:schemas-upnp-org:service:AVTransport:1", "urn:some-vendor:service:Other:1");
        Assert.Null(DlnaPlayer.FromDescription(xml, "http://192.168.1.30:8200/desc.xml", "uuid:abc"));
    }

    [Fact]
    public void NoRenderingControlMeansNoVolume()
    {
        var xml = Description.Replace("urn:schemas-upnp-org:service:RenderingControl:1", "urn:x:service:Nope:1");
        var player = DlnaPlayer.FromDescription(xml, "http://192.168.1.30:8200/desc.xml", "uuid:abc");

        Assert.NotNull(player);
        Assert.False(player!.Caps.Volume);
    }

    [Fact]
    public void UrlBaseWinsOverTheDescriptionAddress()
    {
        // Legal, and some devices serve the description from a different port than the
        // one their services answer on — resolving against the wrong one gives a target
        // that lists fine and then fails every command.
        var xml = Description.Replace(
            "<friendlyName>", "<URLBase>http://192.168.1.30:49152/</URLBase><friendlyName>");
        var player = DlnaPlayer.FromDescription(xml, "http://192.168.1.30:8200/desc.xml", "uuid:abc");

        Assert.NotNull(player);
        Assert.Equal("http://192.168.1.30:49152/AVTransport/control", player!.Control.ToString());
    }

    [Fact]
    public void MalformedDescriptionIsNull()
    {
        Assert.Null(DlnaPlayer.FromDescription("<not-really", "http://192.168.1.30:8200/desc.xml", "uuid:abc"));
    }

    [Fact]
    public void MetadataEscapesTheTitleAndTheUrl()
    {
        // The DIDL is escaped a second time into the SOAP body, so an unescaped
        // ampersand here breaks the whole envelope rather than just the title.
        var didl = DlnaPlayer.Didl("http://host/f/1?a=1&b=2", "Rock & Roll <live>");

        Assert.Contains("Rock &amp; Roll &lt;live&gt;", didl);
        Assert.Contains("a=1&amp;b=2", didl);
        Assert.DoesNotContain("&b=2", didl);
    }

    [Fact]
    public void MetadataHasATitleEvenWithoutOne()
    {
        Assert.Contains("<dc:title>Portal Remote</dc:title>", DlnaPlayer.Didl("http://host/f/1", null));
    }

    private const string Description =
        """
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room TV</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/AVTransport/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/RenderingControl/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
        """;
}

public class SsdpTests
{
    [Fact]
    public void ReadsHeadersRegardlessOfCase()
    {
        // Devices are inconsistent about header case, and a miss here means a TV that
        // answered is dropped on the floor.
        var reply =
            "HTTP/1.1 200 OK\r\n" +
            "cache-control: max-age=1800\r\n" +
            "Location: http://192.168.1.30:8200/desc.xml\r\n" +
            "ST: \"urn:schemas-upnp-org:device:MediaRenderer:1\"\r\n" +
            "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";

        Assert.Equal("http://192.168.1.30:8200/desc.xml", Ssdp.HeaderValue(reply, "LOCATION"));
        Assert.Equal("max-age=1800", Ssdp.HeaderValue(reply, "Cache-Control"));
        // Some devices quote ST. The quotes are not part of the value.
        Assert.Equal("urn:schemas-upnp-org:device:MediaRenderer:1", Ssdp.HeaderValue(reply, "ST"));
        Assert.Null(Ssdp.HeaderValue(reply, "SERVER"));
    }
}
