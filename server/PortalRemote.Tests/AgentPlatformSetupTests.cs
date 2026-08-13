using PortalRemote.Ai;
using Xunit;

namespace PortalRemote.Tests;

public class AgentPlatformSetupTests
{
    /// <summary>The assets of a real agent-platform release, trimmed to the ones that
    /// could plausibly be picked. Getting this wrong installs macOS binaries, the
    /// desktop app, or a checksum file — none of which the Set up button can recover
    /// from, since it just runs whatever exe it finds.</summary>
    private const string Release = """
    {
      "tag_name": "v0.3.1",
      "assets": [
        {"name": "agent-platform-desktop-0.3.1-x86_64-pc-windows-msvc.zip",
         "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/desktop.zip"},
        {"name": "agent-platform-server-aarch64-apple-darwin.tar.xz",
         "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/mac.tar.xz"},
        {"name": "agent-platform-server-installer.ps1",
         "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/installer.ps1"},
        {"name": "agent-platform-server-x86_64-pc-windows-msvc.zip.sha256",
         "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/sum"},
        {"name": "agent-platform-server-x86_64-pc-windows-msvc.zip",
         "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/server.zip"},
        {"name": "sha256.sum", "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/sha256.sum"}
      ]
    }
    """;

    [Fact]
    public void PicksTheWindowsServerZip()
    {
        Assert.Equal("https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/server.zip", AgentPlatformSetup.AssetUrl(Release));
    }

    [Fact]
    public void NoWindowsServerBuildIsNull()
    {
        // A release that only carries the desktop app, or one whose assets are still
        // uploading. The caller turns this into "install it yourself", not a crash.
        Assert.Null(AgentPlatformSetup.AssetUrl("""
        {"assets": [{"name": "agent-platform-server-x86_64-unknown-linux-gnu.tar.xz",
                     "browser_download_url": "https://github.com/tanvoid0/agent-platform/releases/download/v0.3.1/linux.tar.xz"}]}
        """));
        Assert.Null(AgentPlatformSetup.AssetUrl("""{"tag_name": "v0.3.1"}"""));
    }

    [Fact]
    public void AZipHostedSomewhereElseIsNotOffered()
    {
        // Whatever comes back is unzipped and started, so the right-looking name is not
        // enough on its own — see Update.UpdateCheck.IsTrustedAssetUrl.
        Assert.Null(AgentPlatformSetup.AssetUrl("""
        {"assets": [{"name": "agent-platform-server-x86_64-pc-windows-msvc.zip",
                     "browser_download_url": "https://evil.example/server.zip"}]}
        """));
    }
}
