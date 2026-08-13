using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace PortalRemote.Theme;

/// <summary>
/// A flat, owner-drawn button using the token palette. WinForms' stock
/// <see cref="Button"/> has no way to render rounded corners, so this draws its own
/// face and tints on press per docs/design-system.md §7 (MainForm "Copy address").
/// </summary>
public sealed class TokenButton : Button
{
    private bool _pressed;
    private Color _pressedColor;
    private Color _borderColor;

    /// <summary>Outlined rather than accent-filled. A surface with more than one
    /// button needs exactly one filled primary, or neither reads as the main action.
    /// Set before the button is first painted.</summary>
    public bool Secondary { get; init; }

    public TokenButton()
    {
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        Font = Fonts.Body;
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        ApplyTheme(SystemTheme.Colors);
    }

    /// <summary>Re-tint for the current light/dark palette. Called by MainForm on
    /// every Refresh so a Windows theme change is picked up next time it's shown.</summary>
    public void ApplyTheme(PaletteColors colors)
    {
        BackColor = Secondary ? colors.SurfaceRaised : colors.Accent;
        ForeColor = Secondary ? colors.TextPrimary : colors.Surface;
        _pressedColor = Secondary ? colors.Border : colors.AccentPressed;
        _borderColor = colors.Border;
        Invalidate();
    }

    protected override void OnMouseDown(MouseEventArgs mevent)
    {
        _pressed = true;
        Invalidate();
        base.OnMouseDown(mevent);
    }

    protected override void OnMouseUp(MouseEventArgs mevent)
    {
        _pressed = false;
        Invalidate();
        base.OnMouseUp(mevent);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _pressed = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnPaint(PaintEventArgs pevent)
    {
        var g = pevent.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        using var path = RoundedRect.Path(new Rectangle(0, 0, Width - 1, Height - 1), RoundedRect.RadiusSmall);
        using var fill = new SolidBrush(_pressed ? _pressedColor : BackColor);
        g.FillPath(fill, path);

        if (Secondary)
        {
            // The fill alone is a surface token, so without an outline the button
            // would be invisible against the card it sits on.
            using var border = new Pen(_borderColor);
            g.DrawPath(border, path);
        }

        var textSize = g.MeasureString(Text, Font);
        var textPos = new PointF((Width - textSize.Width) / 2f, (Height - textSize.Height) / 2f);
        using var textBrush = new SolidBrush(ForeColor);
        g.DrawString(Text, Font, textBrush, textPos);
    }
}
