using PortalRemote.Files;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The share root is the only thing standing between a paired phone and the rest of the
/// disk, and <see cref="FilePaths"/> is all of it — every <c>/files/*</c> and
/// <c>/share/*</c> handler resolves through here before touching the filesystem. So the
/// interesting cases are the ones an attacker would actually send, not the happy path.
/// </summary>
public class FilePathsTests
{
    private const string Root = @"C:\share";

    [Fact]
    public void OrdinaryPathsResolveUnderTheRoot()
    {
        Assert.Equal(@"C:\share", FilePaths.ResolveSafe(Root, null));
        Assert.Equal(@"C:\share", FilePaths.ResolveSafe(Root, ""));
        Assert.Equal(@"C:\share\a.txt", FilePaths.ResolveSafe(Root, "a.txt"));
        Assert.Equal(@"C:\share\sub\a.txt", FilePaths.ResolveSafe(Root, "sub/a.txt"));
        // The phone sends whichever separator its own path handling produced.
        Assert.Equal(@"C:\share\sub\a.txt", FilePaths.ResolveSafe(Root, @"sub\a.txt"));
        // Collapses back inside, so it is allowed rather than rejected on the raw string.
        Assert.Equal(@"C:\share\a.txt", FilePaths.ResolveSafe(Root, "sub/../a.txt"));
    }

    [Theory]
    // Straight escapes, and the ones that only escape once normalized.
    [InlineData("..")]
    [InlineData("../")]
    [InlineData("../secrets.txt")]
    [InlineData(@"..\secrets.txt")]
    [InlineData("a/../../secrets.txt")]
    [InlineData("sub/../../../Windows/System32/config/SAM")]
    // Drive-rooted and UNC: not relative to anything, so never in the share.
    [InlineData(@"C:\Windows\System32\config\SAM")]
    [InlineData("/Windows")]
    [InlineData(@"\Windows")]
    [InlineData(@"\\server\share\file")]
    [InlineData("//server/share/file")]
    // A drive-relative path ("C:file" means "the current directory on C:").
    [InlineData("C:secrets.txt")]
    // An NTFS stream on the root directory itself.
    [InlineData(":stream")]
    public void PathsThatLeaveTheShareRootAreRejected(string path)
    {
        Assert.Throws<PathTraversalException>(() => FilePaths.ResolveSafe(Root, path));
    }

    [Fact]
    public void ASiblingDirectoryWithTheSamePrefixIsNotInsideTheRoot()
    {
        // "C:\share-public" starts with "C:\share" as a string but is a different folder;
        // the separator is what makes the comparison mean containment.
        Assert.Throws<PathTraversalException>(() => FilePaths.ResolveSafe(Root, "../share-public/x.txt"));
    }

    [Fact]
    public void SafeFileNameKeepsOrdinaryNames()
    {
        Assert.Equal("holiday.jpg", FilePaths.SafeFileName("holiday.jpg"));
        Assert.Equal("a b (2).png", FilePaths.SafeFileName("a b (2).png"));
    }

    [Theory]
    [InlineData("../../evil.exe", "evil.exe")]
    [InlineData(@"..\..\evil.exe", "evil.exe")]
    [InlineData(@"C:\Windows\System32\evil.exe", "evil.exe")]
    public void SafeFileNameDropsAnyDirectoryComponent(string sent, string expected)
    {
        Assert.Equal(expected, FilePaths.SafeFileName(sent));
    }

    [Fact]
    public void SafeFileNameStripsAnNtfsStreamSuffix()
    {
        // Path.GetFileName deliberately preserves this, and File.Create would then write
        // an alternate data stream that nothing subsequently lists.
        Assert.Equal("notes.txt_hidden", FilePaths.SafeFileName("notes.txt:hidden"));
    }

    [Fact]
    public void SafeFileNameStripsQuotesAndControlCharacters()
    {
        // The tray's "reveal in folder" puts this name on explorer.exe's argument line
        // inside quotes, so a quote in it would start a second argument.
        Assert.DoesNotContain('"', FilePaths.SafeFileName(@"a"" C:\Windows\System32\calc.exe """));
        Assert.DoesNotContain('\n', FilePaths.SafeFileName("two\nlines.txt"));
    }

    [Theory]
    [InlineData("NUL")]
    [InlineData("nul")]
    [InlineData("CON.txt")]
    [InlineData("COM1")]
    [InlineData("LPT9.log")]
    public void SafeFileNameDefusesReservedDeviceNames(string sent)
    {
        // These name a device in every directory, so File.Create on one opens the device.
        Assert.StartsWith("_", FilePaths.SafeFileName(sent));
    }

    [Theory]
    [InlineData("")]
    [InlineData(".")]
    [InlineData("..")]
    [InlineData("   ")]
    [InlineData("...")]
    public void SafeFileNameRefusesNamesThatAreNotFiles(string sent)
    {
        // Callers treat empty as "skip this one" (files) or "use share.bin" (share).
        Assert.Equal(string.Empty, FilePaths.SafeFileName(sent));
    }

    [Fact]
    public void SafeFileNameTrimsWhatWindowsWouldTrimSilently()
    {
        // Otherwise the name that gets created is not the name that was checked.
        Assert.Equal("report.pdf", FilePaths.SafeFileName("report.pdf."));
        Assert.Equal("report.pdf", FilePaths.SafeFileName("report.pdf "));
    }
}
