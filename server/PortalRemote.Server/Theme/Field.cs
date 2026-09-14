using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using WinControl = System.Windows.Forms.Control;

namespace PortalRemote.Theme;

/// <summary>
/// The face an input is set into: `surface-muted` — one step *in* from the panel — cut
/// at the corners like everything else, with the hairline that makes it a well rather
/// than a lighter patch. The phone's <c>sunken</c> (docs/design-system.md §3).
///
/// It exists because WinForms will not chamfer a <see cref="TextBox"/>: the control
/// draws its own border and offers <see cref="BorderStyle"/> and nothing else. So the
/// box goes borderless inside this, and this draws the shape. The same wrapper takes the
/// read-only path labels, so a field the user can type in and one they can only copy
/// from are the same object with the same edge — which is the point, because in the
/// build this replaces they were both a bordered box with a tinted fill and there was
/// no way to tell which was which until you clicked one.
/// </summary>
public sealed class Field : WinControl
{
    private readonly bool _readOnly;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <param name="readOnly">Draw as a value to be read, not a box to type in: the panel
    /// face rather than the sunken one, and no bright edge inviting a caret.</param>
    public Field(WinControl content, bool readOnly = false)
    {
        _readOnly = readOnly;
        Padding = new Padding(10, 0, 10, 0);
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);

        content.Dock = DockStyle.Fill;
        Controls.Add(content);
    }

    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        BackColor = _readOnly ? colors.Surface : colors.SurfaceMuted;
        foreach (WinControl child in Controls)
        {
            child.BackColor = BackColor;
            child.ForeColor = _readOnly ? colors.TextSecondary : colors.TextPrimary;
        }
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        var bounds = new Rectangle(0, 0, Width - 1, Height - 1);
        using var path = Shapes.Cut(bounds, LogicalToDeviceUnits(Shapes.CutSmall));
        using var fill = new SolidBrush(BackColor);
        using var pen = new Pen(_readOnly ? _colors.Grid : _colors.Border);
        g.FillPath(fill, path);
        g.DrawPath(pen, path);
    }
}
