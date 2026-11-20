using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using WinControl = System.Windows.Forms.Control;

namespace PortalRemote.Theme;

/// <summary>
/// A group of rows, in the two weights the desktop needs — the phone's <c>HudPanel</c>
/// and <c>HudSectionHeader</c>, which were the two components the tray app had no
/// equivalent of.
///
/// - <b>Panel</b> — chamfered face, hairline edge, and a bracket at the two corners the
///   chamfer left square. For the things that are *live*: the phones and what they are
///   doing.
/// - <b>Section</b> — no face of its own, a tracked monospace label with a rule running
///   out from it. For configuration, which is one list of settings that happens to come
///   in three subjects. This is exactly the shape the phone's own Settings screen uses,
///   and it is the right one: four floating panels of identical weight would say those
///   subjects are four separate objects, and would put the same emphasis on "rotate the
///   pairing token" as on a connected phone.
///
/// Either way it lays its own children out top-down on the 8px rhythm (§2 rule 5) and
/// then sizes itself to them. That is the other half of the point: the window used to be
/// a <see cref="TableLayoutPanel"/> of hand-tuned <see cref="SizeType.Absolute"/> row
/// heights, so a caption that wrapped one line further than the number in the source
/// predicted was cut off — and with two phones listed, the last two rows of the window
/// fell off the bottom entirely. Here a wrapping caption is *measured* at the width it
/// actually got, so no number in the source has to predict it.
/// </summary>
public sealed class HudPanel : WinControl
{
    private const int RowGap = 8;
    private const int TitleHeight = 22;

    private readonly List<(WinControl Row, int Height)> _rows = [];
    private readonly string? _title;
    private readonly string? _glyph;
    private readonly bool _section;
    private readonly bool _rule;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <param name="title">Names the group, uppercased when drawn, or null to draw none
    /// and reclaim the band — the device list is a list of phones and does not need a
    /// word above it.</param>
    /// <param name="glyph">A <see cref="Glyphs"/> codepoint left of the title. The phone
    /// puts one on every settings-group header; §11 rule 2 says all of them or none.</param>
    /// <param name="section">Draw as a ruled section rather than a bracketed panel.</param>
    /// <param name="rule">Hairline above the title. False on the first section of a
    /// column, where there is nothing above to separate it from.</param>
    public HudPanel(string? title = null, string? glyph = null, bool section = false, bool rule = true)
    {
        _title = title?.ToUpperInvariant();
        _glyph = glyph;
        _section = section;
        _rule = rule && section;

        Dock = DockStyle.Top;
        // A bracketed panel provides its own gutter; a section sits in the column's, so
        // it owns only the space above and below itself. More room above the title than
        // under it, so the title binds to the rows it heads rather than floating between
        // two groups.
        Padding = section ? new Padding(0, 20, 0, 10) : new Padding(14, 14, 14, 14);
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }

    /// <summary>
    /// Add a row. [height] is in logical (96dpi) units and is scaled here; pass 0 for a
    /// wrapping <see cref="Label"/>, whose height is measured against the width the row
    /// is actually given rather than guessed at.
    /// </summary>
    public void Add(WinControl row, int height = 0)
    {
        // The panel positions its children itself, so a docked child would fight it for
        // the same rectangle and win.
        row.Dock = DockStyle.None;
        _rows.Add((row, height));
        Controls.Add(row);
    }

    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        BackColor = _section ? colors.Bg : colors.Surface;
        foreach (var (row, _) in _rows) row.BackColor = BackColor;
        Invalidate();
    }

    /// <summary>Re-run the layout after a row's text changed length, or a row was shown
    /// or hidden — a measured row only re-measures when something asks it to.</summary>
    public void Remeasure() => PerformLayout();

    protected override void OnLayout(LayoutEventArgs levent)
    {
        var inner = Width - Padding.Horizontal;
        if (inner <= 0)
        {
            base.OnLayout(levent);
            return;
        }

        var gap = LogicalToDeviceUnits(RowGap);
        var y = Padding.Top + (_title is null ? 0 : LogicalToDeviceUnits(TitleHeight));

        // Every row is laid out, including ones that are not on screen yet. Skipping
        // invisible rows looks like the obvious optimisation and is a trap: Control.Visible
        // is *effective* visibility, so while the form itself is still hidden — which is
        // exactly when the first layout pass runs — every child reports false, every row is
        // skipped, and the panel sizes itself to its own padding. The column collapsed into
        // a stack of overlapping slivers.
        foreach (var (row, height) in _rows)
        {
            var h = height > 0 ? LogicalToDeviceUnits(height) : MeasureWrap(row, inner);
            row.SetBounds(Padding.Left, y, inner, h);
            y += h + gap;
        }

        if (_rows.Count > 0) y -= gap;

        // Guarded: assigning Height re-enters layout, and an unguarded write of an
        // unchanged value is a wasted pass on every resize tick.
        var wanted = y + Padding.Bottom;
        if (Height != wanted) Height = wanted;

        base.OnLayout(levent);
    }

    /// <summary>How tall a wrapping label needs to be at [width]. GDI
    /// (<see cref="TextRenderer"/>) rather than GDI+ because that is what
    /// <see cref="Label"/> paints with — measuring in the other one wraps differently
    /// and clips the last line.</summary>
    private static int MeasureWrap(WinControl row, int width)
    {
        if (row is not Label label) return row.Height;
        return TextRenderer.MeasureText(
            label.Text, label.Font, new Size(width, int.MaxValue),
            TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix).Height;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        var cut = LogicalToDeviceUnits(Shapes.CutLarge);

        // A section has no face of its own, but it is an opaque control, so without this
        // it stamps a blank rectangle over the canvas's ruling and the ground stops dead
        // at the top of the settings list.
        if (_section && Parent is not null)
        {
            var root = Parent;
            while (root.Parent is not null) root = root.Parent;
            Shapes.DrawGridAligned(g, this, root, _colors.Grid, LogicalToDeviceUnits(28));
        }

        if (!_section)
            PaintFace(g, new Rectangle(0, 0, Width - 1, Height - 1), _colors, cut, LogicalToDeviceUnits(2));

        if (_title is null) return;

        var titleTop = Padding.Top;
        var x = Padding.Left;

        if (_glyph is not null)
        {
            var px = LogicalToDeviceUnits(14);
            using var image = Glyphs.Render(_glyph, px, _colors.TextSecondary);
            if (image is not null) g.DrawImage(image, x, titleTop, px, px);
            x += px + LogicalToDeviceUnits(8);
        }

        PaintTitle(g, _title, new Point(x, titleTop), Width - Padding.Right, _colors,
            LogicalToDeviceUnits((int)HudText.LabelTracking), LogicalToDeviceUnits(10));

        if (!_rule) return;
        using (var pen = new Pen(_colors.Border))
        {
            g.DrawLine(pen, 0, 0, Width, 0);
        }
    }

    /// <summary>The bracketed panel face on its own, for a surface that paints several
    /// panels itself rather than hosting one control per panel (the Stats tab).</summary>
    public static void PaintFace(Graphics g, Rectangle rect, PaletteColors colors, int cut, float bracketWeight)
    {
        using (var path = Shapes.Panel(rect, cut))
        {
            using var fill = new SolidBrush(colors.Surface);
            using var pen = new Pen(colors.Border);
            g.FillPath(fill, path);
            g.DrawPath(pen, path);
        }

        Shapes.DrawBrackets(g, rect, colors.Accent, cut, bracketWeight);
    }

    /// <summary>A tracked label with the rule running out from it to [ruleRight]. This is
    /// what makes a header one line rather than two things floating near each other — the
    /// phone's HudSectionHeader, and the reason a group reads as a group. Returns the
    /// label's height.</summary>
    public static int PaintTitle(
        Graphics g, string title, Point at, int ruleRight, PaletteColors colors, int tracking, int ruleGap)
    {
        var size = HudText.Measure(title, Fonts.Label, tracking);
        HudText.Draw(g, title, Fonts.Label, at, colors.TextSecondary, tracking);

        var ruleLeft = at.X + size.Width + ruleGap;
        if (ruleRight > ruleLeft)
        {
            using var pen = new Pen(colors.Grid);
            var mid = at.Y + size.Height / 2;
            g.DrawLine(pen, ruleLeft, mid, ruleRight, mid);
        }

        return size.Height;
    }
}
