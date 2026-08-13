using PortalRemote.Update;
using Xunit;

namespace PortalRemote.Tests;

public class UpdateCheckTests
{
    [Fact]
    public void CompareIsNumericNotLexical()
    {
        Assert.True(UpdateCheck.IsNewer("0.10.0", "0.9.0"));
        Assert.True(UpdateCheck.IsNewer("1.0.0", "0.99.9"));
        Assert.False(UpdateCheck.IsNewer("0.1.0", "0.1.0"));
        Assert.False(UpdateCheck.IsNewer("0.1.0", "0.2.0"));
    }

    [Fact]
    public void PreReleaseDoesNotBeatItsRelease()
    {
        Assert.False(UpdateCheck.IsNewer("0.2.0-rc1", "0.2.0"));
        Assert.True(UpdateCheck.IsNewer("v0.2.0-rc1", "0.1.0"));
    }

    [Fact]
    public void PicksTheExeAsset()
    {
        var release = UpdateCheck.Parse(
            """
            {"tag_name":"v0.2.0","assets":[
              {"name":"PortalRemote-0.2.0.apk","browser_download_url":"https://x/apk"},
              {"name":"PortalRemote.exe","browser_download_url":"https://x/exe"}
            ]}
            """);

        Assert.NotNull(release);
        Assert.Equal("0.2.0", release!.Version);
        Assert.Equal("https://x/exe", release.ExeUrl);
    }

    [Fact]
    public void ReleaseWithoutAnExeIsNotAnUpdate()
    {
        Assert.Null(UpdateCheck.Parse("""{"tag_name":"v0.2.0","assets":[]}"""));
    }
}
