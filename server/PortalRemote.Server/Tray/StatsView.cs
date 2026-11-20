using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using PortalRemote.Metrics;
using PortalRemote.Theme;
using WinControl = System.Windows.Forms.Control;

namespace PortalRemote.Tray;

/// <summary>
/// The phone's Stats screen, on the PC it is about — <c>ui/StatsScreen.kt</c> drawn in
/// GDI+. Same panels in the same order: what the machine is, the two dials, the last
/// minute of both, a column per core, the mirrored network trace, a meter per fixed
/// drive, the busiest processes. Same sample too: <see cref="SystemStats"/> feeds this
/// and the phone the one <see cref="SysSample"/>, so the two never disagree.
///
/// One control painting every panel itself, rather than a <see cref="HudPanel"/> per
/// section with a canvas inside each: the whole surface is one readout that redraws as a
/// unit once a second, and a y-cursor over a list of panels is less to get wrong than a
/// docked stack of self-sizing controls in a table for the dial row. The chrome is still
/// the panel's — <see cref="HudPanel.PaintFace"/> and <see cref="HudPanel.PaintTitle"/>
/// — so a section here and a section in the settings column are the same drawing.
///
/// <b>Static, on purpose.</b> The phone tweens every reading and sweeps a radar pass
/// round the CPU dial (docs/design-system.md §7); §8 keeps desktop motion to the window's
/// fade-in because WinForms has no compositor to make a 30fps repaint cheap or smooth.
/// Readings here step once a second, the same rate the sample arrives at.
///
/// Sampling costs the PC something, so it runs only while this is on screen: the
/// subscription follows <see cref="WinControl.Visible"/>, which is <i>effective</i>
/// visibility — hiding the window, or switching tabs, both release it.
/// </summary>
public sealed class StatsView : ScrollableControl
{
    /// <summary>How many samples the charts have room for — a minute at one a second,
    /// matching the phone's ring buffer so the axis is a full minute wide even while it
    /// is still filling.</summary>
    private const int HistoryCapacity = 60;

    /// <summary>History survives a glance at another tab but not a longer absence: a
    /// trace with a hole in it stretched across an unbroken axis is a lie about the
    /// last minute.</summary>
    private const int HistoryGapMs = 5000;

    private const int Gap = 12;
    private const int Pad = 14;
    private const int TitleHeight = 22;

    private const int DialSize = 118;

    /// <summary>How many ticks make up a dial's outer ring. Enough that the ring reads as
    /// a scale rather than as a dashed circle, few enough that each one is a mark.</summary>
    private const int DialTicks = 44;

    /// <summary>A 270° sweep with the gap at the bottom — the shape every physical gauge
    /// uses, because the gap is where the label goes.</summary>
    private const float DialStart = 135f;
    private const float DialSweep = 270f;

    /// <summary>How many cells make up one core's bar.</summary>
    private const int CoreCells = 14;

    /// <summary>How many segments a meter is divided into — the phone's HudMeter.</summary>
    private const int MeterSegments = 32;

    /// <summary>The smallest throughput the network chart will scale to. Below this the
    /// link is idle, and a chart that zooms in on 300 bytes of keepalive is drawing noise
    /// as traffic.</summary>
    private const long NetworkFloorBytes = 256L * 1024;

    private readonly SystemStats _stats;
    private readonly StatsSubscription _watch;
    private readonly List<SysSample> _history = new(HistoryCapacity);
    private SysSample? _latest;
    private long _lastSampleAt;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <summary>One bracketed panel: its title, how tall its body is, how to draw the
    /// body, and what (if anything) sits at the right end of the title line.</summary>
    private sealed record Section(
        string? Title, int Body, Action<Graphics, Rectangle> Draw, Trailing? Trailing = null);

    /// <summary>Something at the right end of a title line — a legend, a count.</summary>
    private sealed record Trailing(int Width, Action<Graphics, Rectangle> Draw);

    public StatsView(SystemStats stats)
    {
        _stats = stats;
        _watch = new StatsSubscription(stats);
        AutoScroll = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint
               | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        _stats.Changed += OnSample;
    }

    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        BackColor = colors.Bg;
        Invalidate();
    }

    // ---- feed -------------------------------------------------------------------

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        _watch.Set(Visible);
        // A phone may already be watching, in which case there is a sample to draw now
        // rather than a blank second from now.
        if (Visible && _stats.Latest is { } latest) Accept(latest);
    }

    private void OnSample(SysSample sample)
    {
        // Raised on the sampler's timer thread.
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(() => Accept(sample)));
    }

    private void Accept(SysSample sample)
    {
        if (!Visible || IsDisposed) return;
        var now = Environment.TickCount64;
        if (now - _lastSampleAt > HistoryGapMs) _history.Clear();
        _lastSampleAt = now;

        // Same object twice (Latest on show, then the tick that produced it) is one
        // point on the chart, not two.
        if (_history.Count == 0 || !ReferenceEquals(_history[^1], sample))
        {
            _history.Add(sample);
            if (_history.Count > HistoryCapacity) _history.RemoveAt(0);
        }

        _latest = sample;
        AutoScrollMinSize = new Size(0, ContentHeight(sample));
        Invalidate();
    }

    // ---- layout -----------------------------------------------------------------

    private int D(int logical) => LogicalToDeviceUnits(logical);

    private float S(float logical) => logical * DeviceDpi / 96f;

    private int Tracking => D((int)HudText.LabelTracking);

    private int LabelHeight => HudText.Measure("M", Fonts.Label, Tracking).Height;

    private static int LineHeight(Font font) => TextRenderer.MeasureText("M", font, Size.Empty, TextFormatFlags.NoPadding).Height;

    private int PanelHeight(Section panel) =>
        D(Pad) * 2 + (panel.Title is null ? 0 : D(TitleHeight)) + panel.Body;

    private int ContentHeight(SysSample sample)
    {
        var rows = Rows(sample);
        return rows.Sum(row => row.Max(PanelHeight)) + D(Gap) * (rows.Length - 1);
    }

    /// <summary>The dashboard, top to bottom. A row is one panel across, or two side by
    /// side at the same height.</summary>
    private Section[][] Rows(SysSample s)
    {
        var labelH = LabelHeight;
        var monoH = LineHeight(Fonts.Mono);
        var readoutH = LineHeight(Fonts.Readout);
        var dialBody = D(DialSize) + D(10) + labelH;
        var memPercent = s.Mem.Total > 0 ? 100.0 * s.Mem.Used / s.Mem.Total : 0;

        var rows = new List<Section[]>();
        rows.Add([new Section(null, labelH * 2 + D(4), (g, r) => DrawMachine(g, r, s))]);
        rows.Add([
            // The average hides one core pegged while the rest idle, which is exactly
            // what a stuck single-threaded process looks like. The peak is the tell.
            new Section("CPU", dialBody, (g, r) => DrawDial(g, r, s.Cpu, LoadColor(s.Cpu),
                s.Cores.Length > 0 ? $"PEAK CORE {(int)s.Cores.Max()}%" : "")),
            new Section("MEMORY", dialBody, (g, r) => DrawDial(g, r, memPercent, _colors.Second,
                $"{Bytes(s.Mem.Used)} / {Bytes(s.Mem.Total)}".ToUpperInvariant())),
        ]);
        rows.Add([new Section("LAST 60S", D(122), DrawLoadHistory, new Trailing(LegendWidth(), DrawLegend))]);

        if (s.Cores.Length > 1)
        {
            var count = s.Cores.Length.ToString();
            rows.Add([new Section("CORES", D(72), (g, r) => DrawCores(g, r, s.Cores),
                new Trailing(HudText.Measure(count, Fonts.Label, Tracking).Width,
                    (g, r) => HudText.Draw(g, count, Fonts.Label, r.Location, _colors.TextPrimary, Tracking)))]);
        }

        rows.Add([new Section("NETWORK", labelH + D(3) + readoutH + D(12) + D(76), (g, r) => DrawNetwork(g, r, s))]);

        if (s.Disks.Length > 0)
            rows.Add([new Section("STORAGE",
                s.Disks.Length * (monoH + D(6) + D(9)) + (s.Disks.Length - 1) * D(12),
                (g, r) => DrawStorage(g, r, s.Disks))]);

        if (s.Top.Length > 0)
            rows.Add([new Section("TOP PROCESSES",
                s.Top.Length * (monoH + D(16)) + (s.Top.Length - 1) * D(2),
                (g, r) => DrawProcesses(g, r, s.Top))]);

        return rows.ToArray();
    }

    // ---- paint ------------------------------------------------------------------

    protected override void OnScroll(ScrollEventArgs se)
    {
        base.OnScroll(se);
        // The panels scroll; the ruled ground under them does not, so a scrolled copy of
        // the old pixels would carry a shifted grid. Repaint the lot.
        Invalidate();
    }

    protected override void OnMouseWheel(MouseEventArgs e)
    {
        base.OnMouseWheel(e);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        // No ground of its own: continue the window's, so the panels rest on the same
        // surface as everything else rather than on a second grid out of phase with it.
        var root = (WinControl)this;
        while (root.Parent is not null) root = root.Parent;
        if (root != this) Shapes.DrawGridAligned(g, this, root, _colors.Grid, D(28));

        if (_latest is null)
        {
            DrawLinkPending(g);
            return;
        }

        // Scrolled by moving the cursor, not by a transform on the Graphics: TextRenderer
        // is GDI and ignores GDI+'s TranslateTransform, so a transformed paint scrolls
        // every arc and leaves every label where it was.
        var width = ClientSize.Width;
        var y = AutoScrollPosition.Y;
        foreach (var row in Rows(_latest))
        {
            var height = row.Max(PanelHeight);
            var cell = (width - D(Gap) * (row.Length - 1)) / row.Length;
            for (var i = 0; i < row.Length; i++)
                DrawPanel(g, new Rectangle(i * (cell + D(Gap)), y, cell, height), row[i]);
            y += height + D(Gap);
        }
    }

    /// <summary>Waiting for the first sample. A spinner would be the app's generic
    /// chrome; this is the panel saying it hasn't been fed yet, in its own language.</summary>
    private void DrawLinkPending(Graphics g)
    {
        var size = D(56);
        var inset = S(4);
        var cx = ClientSize.Width / 2f;
        var cy = ClientSize.Height / 2f - D(20);
        Shapes.DrawGlowArc(g,
            new RectangleF(cx - size / 2f + inset, cy - size / 2f + inset, size - inset * 2, size - inset * 2),
            -90, 300, Color.FromArgb(178, _colors.Accent), S(2), _colors.Glow);

        const string text = "ESTABLISHING LINK";
        var w = HudText.Measure(text, Fonts.Label, Tracking).Width;
        HudText.Draw(g, text, Fonts.Label, new Point((int)(cx - w / 2f), (int)(cy + size / 2f + D(18))),
            _colors.TextSecondary, Tracking);
    }

    private void DrawPanel(Graphics g, Rectangle rect, Section panel)
    {
        HudPanel.PaintFace(g, new Rectangle(rect.X, rect.Y, rect.Width - 1, rect.Height - 1),
            _colors, D(Shapes.CutLarge), D(2));

        var x = rect.X + D(Pad);
        var top = rect.Y + D(Pad);
        var right = rect.Right - D(Pad);

        if (panel.Title is not null)
        {
            var ruleRight = right;
            if (panel.Trailing is { } trailing)
            {
                var at = new Rectangle(right - trailing.Width, top, trailing.Width, LabelHeight);
                trailing.Draw(g, at);
                ruleRight = at.Left - D(10);
            }
            HudPanel.PaintTitle(g, panel.Title, new Point(x, top), ruleRight, _colors, Tracking, D(10));
            top += D(TitleHeight);
        }

        panel.Draw(g, new Rectangle(x, top, right - x, panel.Body));
    }

    /// <summary>What this machine is, on one line: name, health, uptime.</summary>
    private void DrawMachine(Graphics g, Rectangle r, SysSample s)
    {
        var labelH = LabelHeight;
        const string live = "LIVE";
        var liveW = HudText.Measure(live, Fonts.Label, Tracking).Width;
        var pulse = D(21);
        var textRight = r.Right - liveW - pulse - D(8);

        HudText.Draw(g, Fit(s.CpuName.ToUpperInvariant(), textRight - r.X), Fonts.Label,
            new Point(r.X, r.Y), _colors.TextPrimary, Tracking);
        HudText.Draw(g, Fit($"{s.Os.ToUpperInvariant()}  ·  UP {Uptime(s.UptimeMs).ToUpperInvariant()}", textRight - r.X),
            Fonts.Label, new Point(r.X, r.Y + labelH + D(4)), _colors.TextSecondary, Tracking);

        // The live dot, on a canvas three times its size — the bloom is three times its
        // radius, and a clipped bloom reads as a smudge.
        var cy = r.Y + r.Height / 2f;
        Shapes.DrawGlowDot(g, new PointF(r.Right - liveW - pulse / 2f, cy), _colors.Accent, S(3.5f), _colors.Glow);
        HudText.Draw(g, live, Fonts.Label, new Point(r.Right - liveW, (int)(cy - labelH / 2f)), _colors.Accent, Tracking);
    }

    /// <summary>A reading as a lit ring: a tick scale, a bloomed arc over it, and a head
    /// that glows.</summary>
    private void DrawDial(Graphics g, Rectangle r, double percent, Color color, string caption)
    {
        var size = D(DialSize);
        var left = r.X + (r.Width - size) / 2f;
        var top = (float)r.Y;
        var center = new PointF(left + size / 2f, top + size / 2f);
        var value = (float)Math.Clamp(percent / 100, 0, 1);

        var stroke = S(7);
        var arcInset = S(16) + stroke / 2;
        var arc = new RectangleF(left + arcInset, top + arcInset, size - arcInset * 2, size - arcInset * 2);

        // The outer tick scale: lit up to the reading, dim past it.
        var outer = size / 2f;
        var inner = outer - S(7);
        var lit = (int)(DialTicks * value);
        for (var i = 0; i < DialTicks; i++)
        {
            var angle = Math.PI / 180 * (DialStart + DialSweep * i / (DialTicks - 1f));
            var cos = (float)Math.Cos(angle);
            var sin = (float)Math.Sin(angle);
            var on = i < lit;
            using var pen = new Pen(on ? Color.FromArgb(217, color) : _colors.Grid, on ? S(2.2f) : S(1.5f))
            { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawLine(pen, center.X + inner * cos, center.Y + inner * sin, center.X + outer * cos, center.Y + outer * sin);
        }

        // The unlit remainder, so the ring is a scale with a filled part rather than a
        // line that stops in mid air.
        using (var track = new Pen(_colors.Grid, stroke) { StartCap = LineCap.Round, EndCap = LineCap.Round })
        {
            g.DrawArc(track, arc, DialStart, DialSweep);
        }

        if (value > 0.004f)
        {
            Shapes.DrawGlowArc(g, arc, DialStart, DialSweep * value, color, stroke, _colors.Glow);
            var head = Math.PI / 180 * (DialStart + DialSweep * value);
            var radius = arc.Width / 2;
            Shapes.DrawGlowDot(g,
                new PointF(center.X + radius * (float)Math.Cos(head), center.Y + radius * (float)Math.Sin(head)),
                color, stroke / 2.4f, _colors.Glow);
        }

        // The figure and its unit, as one centred column.
        var figure = ((int)percent).ToString();
        var figureSize = TextRenderer.MeasureText(figure, Fonts.Dial, Size.Empty, TextFormatFlags.NoPadding);
        var unitW = HudText.Measure("%", Fonts.Label, Tracking).Width;
        var columnTop = center.Y - (figureSize.Height + LabelHeight) / 2f;
        TextRenderer.DrawText(g, figure, Fonts.Dial,
            new Point((int)(center.X - figureSize.Width / 2f), (int)columnTop), _colors.TextPrimary, TextFormatFlags.NoPadding);
        HudText.Draw(g, "%", Fonts.Label, new Point((int)(center.X - unitW / 2f), (int)(columnTop + figureSize.Height)),
            _colors.TextSecondary, Tracking);

        if (caption.Length == 0) return;
        caption = Fit(caption, r.Width);
        var captionW = HudText.Measure(caption, Fonts.Label, Tracking).Width;
        HudText.Draw(g, caption, Fonts.Label, new Point((int)(center.X - captionW / 2f), (int)(top + size + D(10))),
            _colors.TextSecondary, Tracking);
    }

    /// <summary>CPU and memory over the last minute, on one shared 0–100 axis.</summary>
    private void DrawLoadHistory(Graphics g, Rectangle r)
    {
        // Quarter rules plus a brighter baseline. Without a scale behind it a line
        // drifting across an empty box reads as 5% or 50% depending on nothing.
        using (var faint = new Pen(Color.FromArgb(178, _colors.Grid)))
        {
            for (var i = 0; i < 4; i++)
            {
                var y = r.Y + r.Height * i / 4f;
                g.DrawLine(faint, r.X, y, r.Right, y);
            }
        }
        using (var baseline = new Pen(_colors.Grid, 2f))
        {
            g.DrawLine(baseline, r.X, r.Bottom, r.Right, r.Bottom);
        }

        DrawSeries(g, r, _history.Select(h => h.Mem.Total > 0 ? (float)(100.0 * h.Mem.Used / h.Mem.Total) : 0f).ToList(),
            _colors.Second, fill: false);
        DrawSeries(g, r, _history.Select(h => (float)h.Cpu).ToList(), _colors.Accent, fill: true);
    }

    /// <summary>
    /// One 0–100 series, right-aligned like every hardware monitor's own graph: the
    /// newest sample is at the right edge and a history still filling leaves the left
    /// blank, rather than stretching six points across the width — stretching makes the
    /// *scale* look like it is changing when only the sample count is.
    /// </summary>
    private void DrawSeries(Graphics g, Rectangle r, List<float> values, Color color, bool fill)
    {
        if (values.Count < 2) return;
        var step = r.Width / (HistoryCapacity - 1f);
        var start = HistoryCapacity - values.Count;
        var points = new PointF[values.Count];
        for (var i = 0; i < values.Count; i++)
            points[i] = new PointF(r.X + (start + i) * step, r.Y + r.Height * (1f - Math.Clamp(values[i] / 100f, 0f, 1f)));
        var last = points[^1];

        if (fill)
        {
            using var area = new GraphicsPath();
            area.AddLines(points);
            area.AddLine(last, new PointF(last.X, r.Bottom));
            area.AddLine(new PointF(last.X, r.Bottom), new PointF(points[0].X, r.Bottom));
            area.CloseFigure();
            using var brush = new LinearGradientBrush(
                new Point(0, r.Y), new Point(0, r.Bottom), Color.FromArgb(77, color), Color.FromArgb(0, color));
            g.FillPath(brush, area);
        }

        using var path = new GraphicsPath();
        path.AddLines(points);
        Shapes.DrawGlowLine(g, path, color, S(2), _colors.Glow);
        // Where the trace is right now. On a chart that scrolls, this is the only point
        // that is a reading rather than a memory.
        Shapes.DrawGlowDot(g, last, color, S(2.5f), _colors.Glow);
    }

    private int LegendWidth() =>
        D(6) + D(5) + HudText.Measure("CPU", Fonts.Label, Tracking).Width + D(12)
      + D(6) + D(5) + HudText.Measure("MEM", Fonts.Label, Tracking).Width;

    private void DrawLegend(Graphics g, Rectangle r)
    {
        var x = r.X;
        var dot = D(6);
        var cy = r.Y + r.Height / 2f;
        Key(_colors.Accent, "CPU");
        x += D(12);
        Key(_colors.Second, "MEM");

        void Key(Color color, string label)
        {
            using var brush = new SolidBrush(color);
            g.FillEllipse(brush, x, cy - dot / 2f, dot, dot);
            x += dot + D(5);
            HudText.Draw(g, label, Fonts.Label, new Point(x, r.Y), _colors.TextSecondary, Tracking);
            x += HudText.Measure(label, Fonts.Label, Tracking).Width;
        }
    }

    /// <summary>One column per logical processor, each a stack of lit cells — where a
    /// whole-machine average hides a single core pinned while the rest idle.</summary>
    private void DrawCores(Graphics g, Rectangle r, double[] cores)
    {
        var gap = S(3);
        var column = (r.Width - gap * (cores.Length - 1)) / cores.Length;
        var cellGap = S(2);
        var cell = (r.Height - cellGap * (CoreCells - 1)) / CoreCells;
        for (var i = 0; i < cores.Length; i++)
        {
            var x = r.X + i * (column + gap);
            var value = (float)Math.Clamp(cores[i] / 100, 0, 1);
            var lit = (int)(CoreCells * value + 0.5f);
            var color = LoadColor(cores[i]);
            using var on = new SolidBrush(color);
            using var off = new SolidBrush(_colors.Grid);
            for (var c = 0; c < CoreCells; c++)
            {
                var y = r.Bottom - (c + 1) * cell - c * cellGap;
                g.FillRectangle(c < lit ? on : off, x, y, column, cell);
            }
            // One bloom pass over the lit stack rather than per cell — the glow is for
            // the column, and 32 columns × 14 cells of it would be 448 extra fills a
            // frame for something nobody can see cell by cell.
            if (lit > 0 && _colors.Glow > 0f)
            {
                var top = r.Bottom - lit * (cell + cellGap);
                using var bloom = new SolidBrush(Color.FromArgb((int)(46 * _colors.Glow), color));
                g.FillRectangle(bloom, x - gap / 2, top, column + gap, r.Bottom - top);
            }
        }
    }

    /// <summary>
    /// Throughput, as a mirrored trace: down above the centre line, up below it. Two
    /// series on one axis would need the reader to tell two colours apart before knowing
    /// which direction is which; split about a centre line, the shape says it.
    /// </summary>
    private void DrawNetwork(Graphics g, Rectangle r, SysSample s)
    {
        var half = r.Width / 2;
        DrawReading(g, new Point(r.X, r.Y), "DOWN", Rate(s.Net.Down), _colors.Accent);
        DrawReading(g, new Point(r.X + half, r.Y), "UP", Rate(s.Net.Up), _colors.Second);

        var chartTop = r.Y + LabelHeight + D(3) + LineHeight(Fonts.Readout) + D(12);
        var chart = new Rectangle(r.X, chartTop, r.Width, r.Bottom - chartTop);
        using (var mid = new Pen(_colors.Grid))
        {
            g.DrawLine(mid, chart.X, chart.Y + chart.Height / 2f, chart.Right, chart.Y + chart.Height / 2f);
        }

        var peak = (float)Math.Max(NetworkFloorBytes, Math.Max(
            _history.Count == 0 ? 0 : _history.Max(h => h.Net.Down),
            _history.Count == 0 ? 0 : _history.Max(h => h.Net.Up)));
        DrawMirrored(g, chart, _history.Select(h => (float)(h.Net.Down / peak)).ToList(), _colors.Accent, up: true);
        DrawMirrored(g, chart, _history.Select(h => (float)(h.Net.Up / peak)).ToList(), _colors.Second, up: false);
    }

    /// <summary>The shape every number in the app is shown in: label above, figure below,
    /// in the hue that means something about it — the phone's HudReading.</summary>
    private void DrawReading(Graphics g, Point at, string label, string value, Color color)
    {
        HudText.Draw(g, label, Fonts.Label, at, _colors.TextSecondary, Tracking);
        TextRenderer.DrawText(g, value, Fonts.Readout, new Point(at.X, at.Y + LabelHeight + D(3)), color,
            TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
    }

    /// <summary>One half of the network trace, drawn away from the centre line.</summary>
    private void DrawMirrored(Graphics g, Rectangle r, List<float> values, Color color, bool up)
    {
        if (values.Count < 2) return;
        var mid = r.Y + r.Height / 2f;
        var step = r.Width / (HistoryCapacity - 1f);
        var start = HistoryCapacity - values.Count;
        var points = new PointF[values.Count];
        for (var i = 0; i < values.Count; i++)
        {
            var extent = r.Height / 2f * Math.Clamp(values[i], 0f, 1f);
            points[i] = new PointF(r.X + (start + i) * step, up ? mid - extent : mid + extent);
        }

        using (var area = new GraphicsPath())
        {
            area.AddLine(new PointF(points[0].X, mid), points[0]);
            area.AddLines(points);
            area.AddLine(points[^1], new PointF(points[^1].X, mid));
            area.CloseFigure();
            using var brush = new SolidBrush(Color.FromArgb(41, color));
            g.FillPath(brush, area);
        }

        using var path = new GraphicsPath();
        path.AddLines(points);
        Shapes.DrawGlowLine(g, path, color, S(1.5f), _colors.Glow);
    }

    /// <summary>Every fixed drive, as a segmented meter each.</summary>
    private void DrawStorage(Graphics g, Rectangle r, DiskSample[] disks)
    {
        var monoH = LineHeight(Fonts.Mono);
        var y = r.Y;
        foreach (var disk in disks)
        {
            var size = $"{Bytes(disk.Used)} / {Bytes(disk.Total)}".ToUpperInvariant();
            var sizeW = HudText.Measure(size, Fonts.Label, Tracking).Width;
            HudText.Draw(g, size, Fonts.Label, new Point(r.Right - sizeW, y + (monoH - LabelHeight) / 2),
                _colors.TextSecondary, Tracking);

            var name = disk.Label is null ? disk.Name : $"{disk.Name} {disk.Label}".ToUpperInvariant();
            TextRenderer.DrawText(g, name, Fonts.Mono, new Rectangle(r.X, y, r.Width - sizeW - D(12), monoH),
                _colors.TextPrimary, TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.EndEllipsis);
            y += monoH + D(6);

            var fraction = disk.Total > 0 ? (float)disk.Used / disk.Total : 0f;
            DrawMeter(g, new Rectangle(r.X, y, r.Width, D(9)), fraction, LoadColor(fraction * 100));
            y += D(9) + D(12);
        }
    }

    /// <summary>A bar that is a row of lit cells rather than a filled rectangle. A
    /// continuous bar at 61% and one at 64% are the same picture; "lit cells out of 32"
    /// is a number you can read off the shape. The phone's HudMeter.</summary>
    private void DrawMeter(Graphics g, Rectangle r, float fraction, Color color)
    {
        var gap = S(2);
        var segment = (r.Width - gap * (MeterSegments - 1)) / MeterSegments;
        var lit = (int)(MeterSegments * Math.Clamp(fraction, 0f, 1f) + 0.5f);
        using var on = new SolidBrush(color);
        using var off = new SolidBrush(_colors.Grid);
        for (var i = 0; i < MeterSegments; i++)
            g.FillRectangle(i < lit ? on : off, r.X + i * (segment + gap), r.Y, segment, r.Height);
        if (lit > 0 && _colors.Glow > 0f)
        {
            using var bloom = new SolidBrush(Color.FromArgb((int)(41 * _colors.Glow), color));
            g.FillRectangle(bloom, r.X, r.Y - S(2), lit * (segment + gap), r.Height + S(4));
        }
    }

    /// <summary>The busiest processes, ranked by the sampler; this only draws the order
    /// it was given.</summary>
    private void DrawProcesses(Graphics g, Rectangle r, ProcessLoad[] processes)
    {
        // Bars are relative to the busiest one rather than to 100: a process's percentage
        // is against a single core, so on a 32-thread machine the top of the list is
        // routinely past 100 and a fixed scale would peg every row.
        var ceiling = Math.Max(1, processes.Max(p => p.Cpu));
        var rowH = LineHeight(Fonts.Mono) + D(16);
        var readoutH = LineHeight(Fonts.Readout);
        var y = r.Y;
        foreach (var process in processes)
        {
            var color = LoadColor(process.Cpu);
            using (var bar = new SolidBrush(Color.FromArgb(33, color)))
            {
                g.FillRectangle(bar, r.X, y, (float)(r.Width * Math.Clamp(process.Cpu / ceiling, 0, 1)), rowH);
            }

            var cpu = $"{(int)process.Cpu}%";
            var cpuW = TextRenderer.MeasureText(cpu, Fonts.Readout, Size.Empty, TextFormatFlags.NoPadding).Width;
            var cpuLeft = r.Right - D(8) - cpuW;
            TextRenderer.DrawText(g, cpu, Fonts.Readout, new Point(cpuLeft, y + (rowH - readoutH) / 2), color,
                TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);

            var mem = Bytes(process.Memory).ToUpperInvariant();
            var memW = HudText.Measure(mem, Fonts.Label, Tracking).Width;
            var memLeft = cpuLeft - D(12) - memW;
            HudText.Draw(g, mem, Fonts.Label, new Point(memLeft, y + (rowH - LabelHeight) / 2), _colors.TextSecondary, Tracking);

            TextRenderer.DrawText(g, process.Name.ToUpperInvariant(), Fonts.Mono,
                new Rectangle(r.X + D(8), y + D(8), memLeft - D(12) - r.X - D(8), rowH - D(16)),
                _colors.TextPrimary, TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix | TextFormatFlags.EndEllipsis);
            y += rowH + D(2);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private Color LoadColor(double percent) => percent switch
    {
        >= 90 => _colors.Danger,
        >= 70 => _colors.Warning,
        _ => _colors.Accent,
    };

    /// <summary>A tracked label cut to [width] with an ellipsis. <see cref="HudText"/> steps
    /// the advance by hand, so <see cref="TextFormatFlags.EndEllipsis"/> can't do it.</summary>
    private string Fit(string text, int width)
    {
        if (HudText.Measure(text, Fonts.Label, Tracking).Width <= width) return text;
        while (text.Length > 1 && HudText.Measure(text + "…", Fonts.Label, Tracking).Width > width)
            text = text[..^1];
        return text + "…";
    }

    /// <summary>"9.4 GB", "247 GB": one decimal below ten, none above — the phone's
    /// formatBytes, so the two screens print the same figure for the same byte count.</summary>
    private static string Bytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        var value = bytes / 1024.0;
        string[] units = ["KB", "MB", "GB", "TB", "PB"];
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return value < 10 ? $"{value:0.0} {units[unit]}" : $"{Math.Round(value)} {units[unit]}";
    }

    /// <summary>Idle is a dash rather than "0 B/s": a column of zeroes is what a broken
    /// meter looks like.</summary>
    private static string Rate(double bytesPerSecond) =>
        bytesPerSecond <= 0 ? "—" : $"{Bytes((long)bytesPerSecond)}/s";

    /// <summary>"6d 4h", "4h 12m", "3m" — one step of precision.</summary>
    private static string Uptime(long millis)
    {
        var minutes = millis / 60_000;
        var days = minutes / 1_440;
        var hours = minutes % 1_440 / 60;
        if (days > 0) return $"{days}d {hours}h";
        if (hours > 0) return $"{hours}h {minutes % 60}m";
        return $"{minutes}m";
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _stats.Changed -= OnSample;
            _watch.Dispose();
        }
        base.Dispose(disposing);
    }
}
