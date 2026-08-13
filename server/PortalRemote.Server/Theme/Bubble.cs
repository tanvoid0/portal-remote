using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace PortalRemote.Theme;

/// <summary>
/// One line of a conversation on the desktop — a share hand-off or an assistant turn,
/// which are the same shape and so are the same control. Incoming on the left in
/// <c>surface-muted</c>, outgoing on the right in <c>accent</c>: the chat shape the
/// phone already uses, so a thread reads the same from either end
/// (docs/design-system.md §12).
///
/// Full-width and docked, drawing its bubble at whichever edge it belongs to, so the
/// parent needs no per-item alignment logic and a resize costs one measure. The text
/// is mutable because an assistant reply is one bubble that grows a few hundred times.
/// </summary>
public sealed class Bubble : Panel
{
    private const int PadX = 12;
    private const int PadY = 8;
    private const int MetaGap = 2;

    /// <summary>A bubble that reached the far edge would read as a full-width block
    /// and lose the left/right cue that says who sent it.</summary>
    private const float MaxFraction = 0.86f;

    // GDI (TextRenderer) rather than GDI+ (Graphics.DrawString) for both the measure
    // and the paint: the two disagree about wrapping, and measuring in one to draw in
    // the other clips the last line of anything that wraps.
    private const TextFormatFlags TextFlags = TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix;

    public bool Incoming { get; }

    private string _body = string.Empty;
    private string _meta = string.Empty;
    private PaletteColors _colors = SystemTheme.Colors;
    private Rectangle _bubble;
    private Rectangle _bodyRect;
    private Rectangle _metaRect;

    public Bubble(bool incoming)
    {
        Incoming = incoming;
        Dock = DockStyle.Top;
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }

    /// <summary>The message and the quiet line under it (who, when, and whatever else
    /// is true of it). Re-measures, so a reply that grew a line taller gets the room.</summary>
    public void SetText(string body, string meta)
    {
        if (_body == body && _meta == meta) return;
        _body = body;
        _meta = meta;
        Measure();
        Invalidate();
    }

    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        // Whatever the thread is painted on. A bubble is full-width, so a background of
        // its own would be a band across the thread that stops wherever the last
        // message happens to be — visible as an edge nothing put there.
        BackColor = Parent?.BackColor ?? colors.Bg;
        Invalidate();
    }

    protected override void OnParentChanged(EventArgs e)
    {
        base.OnParentChanged(e);
        if (Parent is not null) BackColor = Parent.BackColor;
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        Measure();
    }

    private void Measure()
    {
        if (Width <= 0) return;

        var gap = LogicalToDeviceUnits(6);
        var padX = LogicalToDeviceUnits(PadX);
        var padY = LogicalToDeviceUnits(PadY);
        var inner = Math.Max(LogicalToDeviceUnits(60), (int)(Width * MaxFraction) - padX * 2);

        var body = TextRenderer.MeasureText(_body, Fonts.Body, new Size(inner, int.MaxValue), TextFlags);
        var meta = TextRenderer.MeasureText(_meta, Fonts.Caption, new Size(inner, int.MaxValue), TextFlags);
        var content = Math.Max(body.Width, meta.Width);

        _bubble = new Rectangle(
            Incoming ? 0 : Width - (content + padX * 2), 0,
            content + padX * 2, body.Height + MetaGap + meta.Height + padY * 2);
        _bodyRect = new Rectangle(_bubble.X + padX, padY, content, body.Height);
        _metaRect = new Rectangle(_bubble.X + padX, _bodyRect.Bottom + MetaGap, content, meta.Height);

        // Guarded: the setter re-enters OnResize, and an unguarded assignment of an
        // unchanged value would still be one wasted layout pass per resize tick.
        var height = _bubble.Height + gap;
        if (Height != height) Height = height;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        using (var path = RoundedRect.Path(_bubble, RoundedRect.RadiusCard))
        {
            using var fill = new SolidBrush(Incoming ? _colors.SurfaceMuted : _colors.Accent);
            g.FillPath(fill, path);
            if (Incoming)
            {
                // `surface-muted` is close to the window's own surface by design, so the
                // outline is what makes the bubble an object rather than a paragraph.
                using var border = new Pen(_colors.Border);
                g.DrawPath(border, path);
            }
        }

        var body = Incoming ? _colors.TextPrimary : _colors.Surface;
        // GDI text ignores alpha, so the quieter meta line is a blend rather than a
        // transparent version of the body color.
        var meta = Incoming ? _colors.TextSecondary : Mix(_colors.Surface, _colors.Accent, 0.4f);

        TextRenderer.DrawText(g, _body, Fonts.Body, _bodyRect, body, TextFlags);
        TextRenderer.DrawText(g, _meta, Fonts.Caption, _metaRect, meta, TextFlags);
    }

    private static Color Mix(Color a, Color b, float amount) => Color.FromArgb(
        (int)(a.R + (b.R - a.R) * amount),
        (int)(a.G + (b.G - a.G) * amount),
        (int)(a.B + (b.B - a.B) * amount));
}
