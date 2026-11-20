using System.Drawing;
using System.Drawing.Drawing2D;

namespace PortalRemote.Theme;

/// <summary>
/// The shape scale — see docs/design-system.md §5. Chamfers, not radii.
///
/// This replaces the rounded-corner scale the desktop half shipped with, and it is the
/// single largest reason the two halves of this product used to look like two products.
/// The phone's own note on <c>HudPanelShape</c> is the argument: "the form is the
/// identity, not the darkness" — the character of this app is chamfered panels,
/// bracketed corners and monospace labels on a ruled ground, none of which needs a dark
/// background and all of which the tray app was missing. A window drawing 8px radii in
/// system sans beside a phone drawing 14dp chamfers in tracked mono is not one product
/// with two clients.
///
/// Mirrors <c>ui/theme/Theme.kt</c>'s shape scale and <c>Hud.kt</c>'s
/// <c>HudPanelShape</c>; keep both in sync when a number here changes.
/// </summary>
public static class Shapes
{
    /// <summary>Chips, keycaps, inputs — Compose's <c>small</c>.</summary>
    public const int CutSmall = 5;

    /// <summary>Buttons — Compose's <c>medium</c>.</summary>
    public const int CutMedium = 8;

    /// <summary>Panels, bubbles, dialogs — Compose's <c>extraLarge</c>, and the arm
    /// length of the corner brackets that go with it.</summary>
    public const int CutLarge = 14;

    /// <summary>
    /// The panel silhouette: cut at the top-right and bottom-left, square at the other
    /// two. Two corners, not four — four reads as a stop sign, and the two square ones
    /// are where <see cref="DrawBrackets"/> puts its marks.
    /// </summary>
    public static GraphicsPath Panel(Rectangle b, int cut)
    {
        var path = new GraphicsPath();
        path.AddPolygon(new[]
        {
            new Point(b.Left, b.Top),
            new Point(b.Right - cut, b.Top),
            new Point(b.Right, b.Top + cut),
            new Point(b.Right, b.Bottom),
            new Point(b.Left + cut, b.Bottom),
            new Point(b.Left, b.Bottom - cut),
        });
        path.CloseFigure();
        return path;
    }

    /// <summary>Cut on all four corners — the octagon a button or chip is drawn in, and
    /// what makes a control look cut from the same 45° as the panel holding it.</summary>
    public static GraphicsPath Cut(Rectangle b, int cut)
    {
        var path = new GraphicsPath();
        path.AddPolygon(new[]
        {
            new Point(b.Left + cut, b.Top),
            new Point(b.Right - cut, b.Top),
            new Point(b.Right, b.Top + cut),
            new Point(b.Right, b.Bottom - cut),
            new Point(b.Right - cut, b.Bottom),
            new Point(b.Left + cut, b.Bottom),
            new Point(b.Left, b.Bottom - cut),
            new Point(b.Left, b.Top + cut),
        });
        path.CloseFigure();
        return path;
    }

    /// <summary>
    /// A chat bubble: chamfered on three corners, square on the bottom one that points
    /// at its own side of the thread. The classic chat tail, minus the tail — and the
    /// idea reads better cut than rounded, because a 45° chamfer on three corners beside
    /// a hard right angle on the fourth is a more obvious pointer than a radius change.
    /// Ported from the phone's <c>ShareBubble</c>.
    /// </summary>
    public static GraphicsPath Bubble(Rectangle b, int cut, bool incoming)
    {
        var path = new GraphicsPath();
        path.AddPolygon(incoming
            ? new[]
            {
                new Point(b.Left + cut, b.Top),
                new Point(b.Right - cut, b.Top),
                new Point(b.Right, b.Top + cut),
                new Point(b.Right, b.Bottom - cut),
                new Point(b.Right - cut, b.Bottom),
                new Point(b.Left, b.Bottom),
            }
            : new[]
            {
                new Point(b.Left + cut, b.Top),
                new Point(b.Right - cut, b.Top),
                new Point(b.Right, b.Top + cut),
                new Point(b.Right, b.Bottom),
                new Point(b.Left + cut, b.Bottom),
                new Point(b.Left, b.Bottom - cut),
            });
        path.CloseFigure();
        return path;
    }

    /// <summary>
    /// The mark at a panel's two square corners.
    ///
    /// This is the trick that makes the panel cheap: four sides of border is a box, and
    /// a short bright arm at the corner reads as something machined, for the price of
    /// four lines. Same 55% accent the phone uses.
    /// </summary>
    public static void DrawBrackets(Graphics g, Rectangle b, Color accent, int arm, float weight)
    {
        using var pen = new Pen(Color.FromArgb(140, accent), weight);
        g.DrawLine(pen, b.Left, b.Top, b.Left + arm, b.Top);
        g.DrawLine(pen, b.Left, b.Top, b.Left, b.Top + arm);
        g.DrawLine(pen, b.Right, b.Bottom, b.Right - arm, b.Bottom);
        g.DrawLine(pen, b.Right, b.Bottom, b.Right, b.Bottom - arm);
    }

    /// <summary>
    /// The ruled ground, drawn behind a whole window rather than per panel so the panels
    /// read as objects resting on one surface instead of each carrying its own texture.
    /// Half strength, with the difference between the two faces carried by the `grid`
    /// token rather than by a second alpha here.
    /// </summary>
    public static void DrawGrid(Graphics g, Rectangle bounds, Color grid, int cell)
    {
        using var pen = new Pen(Color.FromArgb(128, grid));
        for (var x = bounds.Left; x < bounds.Right; x += cell) g.DrawLine(pen, x, bounds.Top, x, bounds.Bottom);
        for (var y = bounds.Top; y < bounds.Bottom; y += cell) g.DrawLine(pen, bounds.Left, y, bounds.Right, y);
    }

    /// <summary>
    /// The same ruling, drawn inside a child surface but aligned to [root]'s origin, so
    /// an opaque panel sitting on the canvas continues the grid instead of stamping its
    /// own out of phase with it. WinForms has no real transparency to lean on here; this
    /// is the cheap way to get one continuous ground under several opaque surfaces.
    /// </summary>
    public static void DrawGridAligned(
        Graphics g, System.Windows.Forms.Control surface, System.Windows.Forms.Control root,
        Color grid, int cell)
    {
        var here = surface.PointToScreen(Point.Empty);
        var there = root.PointToScreen(Point.Empty);
        var state = g.Save();
        g.TranslateTransform(there.X - here.X, there.Y - here.Y);
        DrawGrid(g, root.ClientRectangle, grid, cell);
        g.Restore(state);
    }

    /// <summary>
    /// A lit point — "this is happening now", which is otherwise a word that has to be
    /// written out. The bloom passes are the phone's <c>glowDot</c>, scaled by the
    /// palette's <see cref="PaletteColors.Glow"/> so the light face gets a trace of a
    /// shoulder rather than a grey smudge.
    /// </summary>
    public static void DrawGlowDot(Graphics g, PointF center, Color color, float radius, float glow)
    {
        if (glow > 0f)
        {
            Ring(3.5f, 0.14f);
            Ring(2.0f, 0.30f);
        }

        using var core = new SolidBrush(color);
        g.FillEllipse(core, center.X - radius, center.Y - radius, radius * 2, radius * 2);

        void Ring(float scale, float alpha)
        {
            var r = radius * scale;
            using var brush = new SolidBrush(Color.FromArgb((int)(255 * alpha * glow), color));
            g.FillEllipse(brush, center.X - r, center.Y - r, r * 2, r * 2);
        }
    }

    /// <summary>A lit stroke — the phone's <c>glowLine</c>: two wide, nearly transparent
    /// passes under the line itself. GDI+ has no blur either, and this is the same three
    /// draws it fakes one with.</summary>
    public static void DrawGlowLine(Graphics g, GraphicsPath path, Color color, float width, float glow)
    {
        if (glow > 0f)
        {
            Pass(4.5f, 0.07f);
            Pass(2.2f, 0.16f);
        }

        using var pen = new Pen(color, width) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        g.DrawPath(pen, path);

        void Pass(float scale, float alpha)
        {
            using var wide = new Pen(Color.FromArgb((int)(255 * alpha * glow), color), width * scale)
            { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
            g.DrawPath(wide, path);
        }
    }

    /// <summary><see cref="DrawGlowLine"/>, for an arc — the phone's <c>glowArc</c>.</summary>
    public static void DrawGlowArc(
        Graphics g, RectangleF bounds, float startAngle, float sweepAngle, Color color, float width, float glow)
    {
        if (glow > 0f)
        {
            Pass(3.0f, 0.07f);
            Pass(1.8f, 0.18f);
        }

        using var pen = new Pen(color, width) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        g.DrawArc(pen, bounds, startAngle, sweepAngle);

        void Pass(float scale, float alpha)
        {
            using var wide = new Pen(Color.FromArgb((int)(255 * alpha * glow), color), width * scale)
            { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawArc(wide, bounds, startAngle, sweepAngle);
        }
    }
}
