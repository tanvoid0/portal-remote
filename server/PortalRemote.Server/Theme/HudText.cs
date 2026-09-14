using System.Drawing;
using System.Windows.Forms;

namespace PortalRemote.Theme;

/// <summary>
/// Tracked monospace labels — the register the phone draws every label, unit and control
/// word in (see <c>ui/theme/Type.kt</c>, docs/design-system.md §4).
///
/// It exists because GDI has no letter-spacing: a <see cref="Font"/> carries a family, a
/// size and a style, and nothing that corresponds to the phone's <c>letterSpacing = 2.sp</c>.
/// So the advance is stepped by hand. That is cheap here and only here, because tracking
/// is reserved for *instrument* text — short, uppercase, monospace — and monospace means
/// every character advances by the same amount, so one measurement drives the whole run.
///
/// Prose is never drawn through this. Tracked uppercase is slower to read, and this app
/// puts real sentences in front of people at the moments they are least patient.
/// </summary>
public static class HudText
{
    /// <summary>The phone's `letterSpacing` at label size, in pixels at 96dpi.</summary>
    public const float LabelTracking = 2f;

    // NoPadding on both the measure and the draw: TextRenderer adds a few pixels of its
    // own either side by default, which at one call per character would accumulate into
    // a visible drift against the measured width.
    private const TextFormatFlags Flags = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix;

    /// <summary>One character's advance. Monospace, so this is the same for all of
    /// them — measured over a run rather than a single glyph so rounding in the font's
    /// metrics doesn't compound across a label.</summary>
    private static float Advance(Font font) =>
        TextRenderer.MeasureText(new string('M', 10), font, Size.Empty, Flags).Width / 10f;

    public static Size Measure(string text, Font font, float tracking)
    {
        if (text.Length == 0) return Size.Empty;
        var advance = Advance(font);
        return new Size(
            (int)Math.Ceiling(text.Length * advance + (text.Length - 1) * tracking),
            TextRenderer.MeasureText("M", font, Size.Empty, Flags).Height);
    }

    /// <summary>Draw [text] from [at], stepping the advance by hand. [at] is the top-left
    /// of the run, matching <see cref="TextRenderer.DrawText(IDeviceContext, string, Font, Point, Color)"/>.</summary>
    public static void Draw(Graphics g, string text, Font font, Point at, Color color, float tracking)
    {
        var advance = Advance(font);
        var x = (float)at.X;
        foreach (var ch in text)
        {
            TextRenderer.DrawText(g, ch.ToString(), font, new Point((int)Math.Round(x), at.Y), color, Flags);
            x += advance + tracking;
        }
    }
}
