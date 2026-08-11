using System.Drawing;
using System.Drawing.Text;
using System.Linq;

namespace PortalRemote.Theme;

/// <summary>
/// Type scale — see docs/design-system.md §4. Three weights only, shared by every
/// desktop surface. Resolved once at first use and reused (GDI+ <see cref="Font"/>
/// instances are heavy and immutable).
/// </summary>
public static class Fonts
{
    // "Segoe UI Variable" ships on Win11 as named static instances, not a weight axis
    // GDI+ can drive — GDI+ has no semibold FontStyle, so Heading uses Bold (the
    // closest available weight) rather than true semibold.
    private static readonly string FamilyName = ResolveFamilyName();

    public static Font Heading { get; } = new(FamilyName, 11f, FontStyle.Bold);
    public static Font Body { get; } = new(FamilyName, 9.5f, FontStyle.Regular);
    public static Font Caption { get; } = new(FamilyName, 9f, FontStyle.Regular);

    private static string ResolveFamilyName()
    {
        const string preferred = "Segoe UI Variable Text";
        using var installed = new InstalledFontCollection();
        var hasPreferred = installed.Families.Any(f => f.Name == preferred);
        return hasPreferred ? preferred : "Segoe UI";
    }
}
