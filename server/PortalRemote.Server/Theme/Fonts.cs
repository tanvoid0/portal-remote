using System.Drawing;
using System.Drawing.Text;
using System.Linq;

namespace PortalRemote.Theme;

/// <summary>
/// Type scale — see docs/design-system.md §4. Mirrors the phone's <c>ui/theme/Type.kt</c>
/// and <c>Hud.kt</c>'s <c>HudType</c>; keep both in sync.
///
/// <b>Two registers, split by what the text is for.</b>
///
/// Prose — anything you read a sentence of: a hint, an error, a shared note, a filename —
/// is the system sans. Instrument text — labels, units, figures, and the short imperative
/// words on controls — is monospace and tracked, drawn through <see cref="HudText"/>.
/// That is the split the phone already makes, and the desktop half made neither side of
/// it: every word in the window was 9, 9.5 or 11pt system sans, which is not a scale
/// (half a point between body and caption is a difference nobody can see) and not the
/// product's voice.
///
/// The sans steps sit ~1.1 apart, with a weight change carrying <see cref="Body"/> to
/// <see cref="Section"/> — the tight ratio product UI wants, where a dozen type elements
/// share one screen and exaggerated contrast is noise.
/// </summary>
public static class Fonts
{
    // "Segoe UI Variable" ships on Win11 as named static instances, not a weight axis
    // GDI+ can drive — GDI+ has no semibold FontStyle, so the bold steps use Bold (the
    // closest available weight) rather than true semibold.
    private static readonly string FamilyName = ResolveFamilyName();

    // Consolas over GenericMonospace: the generic family resolves to Courier New, whose
    // hairline strokes go to mush at label size and read as a typewriter rather than as
    // an instrument. Consolas ships on every Windows this app supports.
    private static readonly string MonoName = ResolveMonoName();

    // ---- prose ---------------------------------------------------------------------

    /// <summary>The wordmark in the window header, and nothing else.</summary>
    public static Font Title { get; } = new(FamilyName, 14f, FontStyle.Bold);

    /// <summary>A surface's own heading.</summary>
    public static Font Heading { get; } = new(FamilyName, 12f, FontStyle.Bold);

    public static Font Body { get; } = new(FamilyName, 10f, FontStyle.Regular);

    public static Font Caption { get; } = new(FamilyName, 9f, FontStyle.Regular);

    // ---- instrument ----------------------------------------------------------------

    /// <summary>Names a group or a reading. Uppercased at the call site and tracked
    /// through <see cref="HudText"/> — at this size the tracking is what separates a
    /// label from the value under it without spending a second colour on it.</summary>
    public static Font Label { get; } = new(MonoName, 8.5f, FontStyle.Bold);

    /// <summary>The words on a control. Tracked, but not uppercased — a button says what
    /// it does, and shouting it is a different tone from naming a group.</summary>
    public static Font Control { get; } = new(MonoName, 9f, FontStyle.Regular);

    /// <summary>Row text that is data but not prose — an address, a path, a model name.
    /// Monospace so the figures don't reflow under the eye.</summary>
    public static Font Mono { get; } = new(MonoName, 9.5f, FontStyle.Regular);

    /// <summary>A value worth reading off. The phone's <c>Readout</c>.</summary>
    public static Font Readout { get; } = new(MonoName, 11f, FontStyle.Bold);

    /// <summary>The figure in the middle of a gauge. The phone's <c>Dial</c>, at the same
    /// quarter of the dial's diameter.</summary>
    public static Font Dial { get; } = new(MonoName, 22f, FontStyle.Bold);

    /// <summary>Kept as the section step for surfaces that have not been ported to the
    /// instrument register yet (the assistant window).</summary>
    public static Font Section { get; } = new(FamilyName, 10f, FontStyle.Bold);

    private static string ResolveFamilyName() => Resolve("Segoe UI Variable Text", "Segoe UI");

    private static string ResolveMonoName() => Resolve("Consolas", "Courier New");

    private static string Resolve(string preferred, string fallback)
    {
        using var installed = new InstalledFontCollection();
        return installed.Families.Any(f => f.Name == preferred) ? preferred : fallback;
    }
}
