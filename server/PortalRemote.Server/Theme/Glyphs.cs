using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Text;
using System.Linq;

namespace PortalRemote.Theme;

/// <summary>
/// The desktop's icon set — see docs/design-system.md §11. Android gets Material
/// Symbols from a dependency; the equivalent here is the icon font Windows already
/// ships, so this adds no asset, no package and nothing to keep in sync with a
/// redraw. §11's "no hand-drawn glyphs outside the brand mark" holds: none of these
/// are drawn by us.
///
/// "Segoe Fluent Icons" is Win11's; "Segoe MDL2 Assets" is Win10's and carries the
/// same codepoints for everything used here. If neither resolves, <see cref="Available"/>
/// is false and every call site falls back to text alone rather than printing boxes.
/// </summary>
public static class Glyphs
{
    // Only the glyphs actually on a surface. Codepoints are verified by rendering them,
    // not taken from a remembered table — the two fonts carry near-duplicates a few
    // codepoints apart (E8B7 folder vs E838 folder-fill, E8C8 copy vs E8B8 contact-card)
    // and memory lands on the wrong one often enough not to be worth trusting. Adding
    // one means rendering a sheet and looking at it, including at 16px.
    public const string Copy = "\uE8C8";
    public const string FolderOpen = "\uE8DA";
    public const string Edit = "\uE70F";
    public const string QrCode = "\uED14";
    public const string Conversation = "\uE8F2";
    public const string Send = "\uE724";
    public const string Attach = "\uE723";
    public const string Refresh = "\uE72C";
    public const string Sync = "\uE895";
    public const string Lock = "\uE72E";
    public const string OpenInNew = "\uE897";
    public const string Assistant = "\uE99A";
    public const string Wrench = "\uE90F";
    public const string Power = "\uE7E8";

    private static readonly string? FamilyName = ResolveFamilyName();

    /// <summary>Fonts are heavy and immutable, and the same handful of sizes is asked
    /// for over and over across a repaint.</summary>
    private static readonly ConcurrentDictionary<float, Font> Cache = new();

    /// <summary>False on a Windows without either icon font. Call sites draw their
    /// label and skip the glyph rather than rendering a row of tofu boxes.</summary>
    public static bool Available => FamilyName != null;

    /// <summary>An icon font at <paramref name="pixels"/> em, or null if neither font
    /// is installed. Sized in pixels because these sit beside text measured in points
    /// and the glyph has to match the cap height, not the point size.</summary>
    public static Font? Font(float pixels) =>
        FamilyName == null
            ? null
            : Cache.GetOrAdd(pixels, px => new Font(FamilyName, px, GraphicsUnit.Pixel));

    /// <summary>
    /// A glyph as a standalone bitmap, for the one place that can't take a font —
    /// <c>ToolStripMenuItem.Image</c>. Returns null when no icon font is installed,
    /// which is exactly what that property wants for "no image".
    /// </summary>
    /// <remarks>
    /// ponytail: rendered once at the DPI passed in, like every absolute size in
    /// MainForm (§12) — dragging the window to a differently-scaled monitor will not
    /// re-render it. Re-render on <c>DpiChanged</c> if that ever matters.
    /// </remarks>
    public static Bitmap? Render(string glyph, int pixels, Color color)
    {
        var font = Font(pixels * 0.8f);
        if (font == null) return null;

        var bmp = new Bitmap(pixels, pixels);
        bmp.SetResolution(96, 96);
        using var g = Graphics.FromImage(bmp);
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        using var brush = new SolidBrush(color);
        var size = g.MeasureString(glyph, font);
        g.DrawString(glyph, font, brush, (pixels - size.Width) / 2f, (pixels - size.Height) / 2f);
        return bmp;
    }

    private static string? ResolveFamilyName()
    {
        using var installed = new InstalledFontCollection();
        var names = installed.Families.Select(f => f.Name).ToHashSet();
        foreach (var candidate in new[] { "Segoe Fluent Icons", "Segoe MDL2 Assets" })
        {
            if (names.Contains(candidate)) return candidate;
        }

        return null;
    }
}
