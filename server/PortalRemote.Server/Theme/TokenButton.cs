using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace PortalRemote.Theme;

/// <summary>
/// A flat, owner-drawn button using the token palette — chamfered on all four corners and
/// lettered in tracked monospace, so it is cut from the same 45° as the panel holding it
/// and speaks in the same register as every control on the phone (docs/design-system.md
/// §5/§7, <c>ui/theme/Theme.kt</c>'s shape scale).
///
/// It draws the full desktop state set: default, hover, pressed, disabled, focused. The
/// last two are the ones an owner-drawn control loses by default and this one used to be
/// missing — WinForms paints no focus rectangle for you, so a keyboard user tabbing
/// through the window could not see where they were.
/// </summary>
public sealed class TokenButton : Button
{
    /// <summary>The phone's `labelLarge` tracking, in pixels at 96dpi. Narrower than a
    /// section label's: a control word is read as a word, not scanned as a heading.</summary>
    private const float Tracking = 1f;

    private bool _pressed;
    private bool _hover;
    private bool _secondary;
    private Color _pressedColor;
    private Color _hoverColor;
    private Color _borderColor;
    private Color _disabledColor;
    private Color _disabledBorderColor;
    private Color _disabledTextColor;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <summary>Outlined rather than accent-filled. A surface with more than one button
    /// needs exactly one filled primary, or neither reads as the main action. Settable
    /// after construction for the one case that changes: the left column's tab strip,
    /// where "which view am I on" is exactly the fill/outline distinction.</summary>
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

    /// <summary>A <see cref="Glyphs"/> codepoint drawn left of the label, or null for text
    /// alone. Per §11 rule 2 this is set on a whole row of buttons or on none of it — one
    /// iconned button beside two plain ones reads as a mistake rather than as emphasis.</summary>
    public string? Glyph { get; set; }

    public TokenButton()
    {
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        Font = Fonts.Control;
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        ApplyTheme(SystemTheme.Colors);
    }

    /// <summary>Re-tint for the current light/dark palette. Called by the windows on every
    /// refresh so a Windows theme change is picked up next time one is shown.</summary>
    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        // A secondary button is a surface token on a surface token — in light mode both
        // were pure white, so the whole control was carried by a 1.2:1 hairline. The face
        // is `surface-muted` and the outline is `borderStrong` (3:1, WCAG 1.4.11's floor
        // for a control boundary); see docs/design-system.md §3.
        BackColor = Secondary ? colors.SurfaceMuted : colors.Accent;
        ForeColor = Secondary ? colors.TextPrimary : colors.Surface;
        _pressedColor = Secondary ? colors.Border : colors.AccentPressed;
        // Halfway to pressed. A control that only reacts once the mouse is already down
        // tells you what you did and never what you are about to do.
        _hoverColor = Mix(BackColor, _pressedColor, 0.5f);
        _borderColor = Secondary ? colors.BorderStrong : colors.Border;
        // An owner-drawn button ignores Enabled unless it is drawn in, and a button that
        // looks pressable while it is not is worse than no button at all — the composer's
        // Send and Attach are disabled the whole time no phone is connected.
        //
        // Two cases, because one fill cannot serve both: a filled primary greys down to
        // `border`, while a secondary's face is already a surface token, so greying it
        // *down* lands on the colour it already had. That one drops out to `bg` and keeps
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

    protected override void OnMouseEnter(EventArgs e)
    {
        _hover = true;
        Invalidate();
        base.OnMouseEnter(e);
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
        _hover = false;
        Invalidate();
        base.OnMouseLeave(e);
    }

    // These two only ask for a repaint; OnPaint decides whether the focus ring is on.
    protected override void OnGotFocus(EventArgs e)
    {
        Invalidate();
        base.OnGotFocus(e);
    }

    protected override void OnLostFocus(EventArgs e)
    {
        Invalidate();
        base.OnLostFocus(e);
    }

    protected override void OnPaint(PaintEventArgs pevent)
    {
        var g = pevent.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        var bounds = new Rectangle(0, 0, Width - 1, Height - 1);
        var cut = LogicalToDeviceUnits(Shapes.CutMedium);

        using (var path = Shapes.Cut(bounds, cut))
        {
            var face = !Enabled ? _disabledColor
                : _pressed ? _pressedColor
                : _hover ? _hoverColor
                : BackColor;
            using var fill = new SolidBrush(face);
            g.FillPath(fill, path);

            if (Secondary)
            {
                // The fill alone is a surface token, so without an outline the button
                // would be invisible against the panel it sits on.
                using var border = new Pen(Enabled ? _borderColor : _disabledBorderColor);
                g.DrawPath(border, path);
            }
        }

        // Inside the face rather than around it: these buttons sit in tight rows, and a
        // ring drawn outside one would be painted over by its neighbour. ShowFocusCues is
        // false until the window has seen keyboard navigation, so a click doesn't leave a
        // ring behind.
        if (Focused && ShowFocusCues)
        {
            var inset = LogicalToDeviceUnits(3);
            var ringBounds = Rectangle.Inflate(bounds, -inset, -inset);
            using var ringPath = Shapes.Cut(ringBounds, Math.Max(2, cut - inset));
            using var ring = new Pen(Secondary ? _colors.Accent : _colors.Surface, LogicalToDeviceUnits(2));
            g.DrawPath(ring, ringPath);
        }

        var ink = Enabled ? ForeColor : _disabledTextColor;
        var tracking = LogicalToDeviceUnits((int)Tracking);
        var textSize = HudText.Measure(Text, Font, tracking);

        // Glyph em is tied to the label's own height rather than to a constant, so a
        // button keeps its proportions when WinForms scales the font at 150% (§12 —
        // absolute sizes on these windows do not scale themselves).
        var glyphPx = Glyph is null ? 0 : (int)Math.Round(textSize.Height * 1.05f);
        var gap = Glyph is null ? 0 : (int)Math.Round(textSize.Height * 0.45f);

        // Measured together and centred as one group: centring the label and hanging the
        // glyph off its left edge pushes the pair off-centre by half a glyph, which is
        // visible the moment two buttons of different label lengths sit side by side.
        var left = (Width - (glyphPx + gap + textSize.Width)) / 2;

        if (Glyph is not null)
        {
            using var image = Glyphs.Render(Glyph, glyphPx, ink);
            if (image is not null) g.DrawImage(image, left, (Height - glyphPx) / 2, glyphPx, glyphPx);
        }

        HudText.Draw(g, Text, Font, new Point(left + glyphPx + gap, (Height - textSize.Height) / 2), ink, tracking);
    }

    private static Color Mix(Color a, Color b, float amount) => Color.FromArgb(
        (int)(a.R + (b.R - a.R) * amount),
        (int)(a.G + (b.G - a.G) * amount),
        (int)(a.B + (b.B - a.B) * amount));
}
