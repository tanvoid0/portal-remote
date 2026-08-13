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
    private bool _secondary;
    private Color _pressedColor;
    private Color _borderColor;
    private Color _disabledColor;
    private Color _disabledBorderColor;
    private Color _disabledTextColor;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <summary>Outlined rather than accent-filled. A surface with more than one
    /// button needs exactly one filled primary, or neither reads as the main action.
    /// Settable after construction for the one case that changes: the left column's
    /// tab strip, where "which view am I on" is exactly the fill/outline distinction.</summary>
    public bool Secondary
    {
        get => _secondary;
        set
        {
            if (_secondary == value) return;
            _secondary = value;
            ApplyTheme(_colors);
        }
    }

    /// <summary>A <see cref="Glyphs"/> codepoint drawn left of the label, or null for
    /// text alone. Per docs/design-system.md §11 rule 2 this is set on a whole row of
    /// buttons or on none of it — one iconned button beside two plain ones reads as a
    /// mistake rather than as emphasis. Null on the buttons §11 rule 4 exempts, where
    /// the word is already the answer.</summary>
    public string? Glyph { get; set; }

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
        _colors = colors;
        // A secondary button is a surface token on a surface token — in light mode both
        // were pure white, so the whole control was carried by a 1.2:1 hairline. The
        // face is `surface-muted` and the outline is `borderStrong` (3:1, WCAG 1.4.11's
        // floor for a control boundary); see docs/design-system.md §3.
        BackColor = Secondary ? colors.SurfaceMuted : colors.Accent;
        ForeColor = Secondary ? colors.TextPrimary : colors.Surface;
        _pressedColor = Secondary ? colors.Border : colors.AccentPressed;
        _borderColor = Secondary ? colors.BorderStrong : colors.Border;
        // An owner-drawn button ignores Enabled unless it is drawn in, and a button that
        // looks pressable while it is not is worse than no button at all — the composer's
        // Send and Attach are disabled the whole time no phone is connected.
        //
        // Two cases, because one fill cannot serve both: a filled primary greys down to
        // `border`, while a secondary's face is already a surface token, so greying it
        // *down* lands on the color it already had. That one drops out to `bg` and keeps
        // only a weak outline — a ghost of a button rather than a slightly duller one.
        _disabledColor = Secondary ? colors.Bg : colors.Border;
        _disabledBorderColor = colors.Border;
        _disabledTextColor = colors.TextSecondary;
        Invalidate();
    }

    protected override void OnEnabledChanged(EventArgs e)
    {
        base.OnEnabledChanged(e);
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
        using var fill = new SolidBrush(!Enabled ? _disabledColor : _pressed ? _pressedColor : BackColor);
        g.FillPath(fill, path);

        if (Secondary)
        {
            // The fill alone is a surface token, so without an outline the button
            // would be invisible against the card it sits on.
            using var border = new Pen(Enabled ? _borderColor : _disabledBorderColor);
            g.DrawPath(border, path);
        }

        using var textBrush = new SolidBrush(Enabled ? ForeColor : _disabledTextColor);
        var textSize = g.MeasureString(Text, Font);

        // Glyph em is tied to the label's own height rather than to a constant, so a
        // button keeps its proportions when WinForms scales the font at 150% (§12 —
        // absolute sizes on this window do not scale themselves).
        var glyphFont = Glyph == null ? null : Glyphs.Font(textSize.Height * 0.72f);
        if (glyphFont == null)
        {
            g.DrawString(Text, Font, textBrush, new PointF(
                (Width - textSize.Width) / 2f,
                (Height - textSize.Height) / 2f));
            return;
        }

        // Measured together and centred as one group: centring the label and hanging
        // the glyph off its left edge pushes the pair off-centre by half a glyph, which
        // is visible the moment two buttons of different label lengths sit side by side.
        var glyphSize = g.MeasureString(Glyph, glyphFont);
        var gap = textSize.Height * 0.3f;
        var groupWidth = glyphSize.Width + gap + textSize.Width;
        var left = (Width - groupWidth) / 2f;

        g.DrawString(Glyph, glyphFont, textBrush, left, (Height - glyphSize.Height) / 2f);
        g.DrawString(Text, Font, textBrush, left + glyphSize.Width + gap, (Height - textSize.Height) / 2f);
    }
}
