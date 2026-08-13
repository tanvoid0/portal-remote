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
    public void StandingSeparatesUpToDateFromUnreleasedAndBehind()
    {
        // The three cases the tray reports differently. A build made after a release but
        // before the next tag is newer than anything published, and offering to "update"
        // that one would install an older exe over a newer one.
        Assert.Equal(UpdateCheck.Standing.Same, UpdateCheck.StandingOf("0.3.1", "0.3.1"));
        Assert.Equal(UpdateCheck.Standing.Same, UpdateCheck.StandingOf("v0.3.1", "0.3.1"));
        Assert.Equal(UpdateCheck.Standing.Behind, UpdateCheck.StandingOf("0.2.0", "0.3.1"));
        Assert.Equal(UpdateCheck.Standing.Unreleased, UpdateCheck.StandingOf("0.4.0", "0.3.1"));
        // A pre-release segment counts as 0, so 0.4.0-rc1 is 0.4.0 — still unreleased.
        Assert.Equal(UpdateCheck.Standing.Unreleased, UpdateCheck.StandingOf("0.4.0-rc1", "0.3.1"));
    }

    [Fact]
    public void ADevBuildIsNeverBehind()
    {
        // The csproj default trails whatever has been tagged since it was last touched,
        // so comparing its digits would call a build of today's source "behind" and offer
        // to replace it with an older release.
        Assert.Equal(UpdateCheck.Standing.Unreleased, UpdateCheck.StandingOf("0.1.0-dev", "0.3.1"));
        Assert.Equal(UpdateCheck.Standing.Unreleased, UpdateCheck.StandingOf("0.1.0-dev", "0.1.0"));
        Assert.False(UpdateCheck.IsDevBuild("0.1.0"));
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
