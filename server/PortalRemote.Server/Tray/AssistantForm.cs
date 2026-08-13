using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using PortalRemote.Ai;
using PortalRemote.Theme;

namespace PortalRemote.Tray;

/// <summary>
/// The assistant on the PC — the same conversation the phone is showing, live.
///
/// It exists because the transcript stopped being the phone's. <see cref="AiConversation"/>
/// holds it on this machine and pushes every change to everything watching, so this window
/// is a second renderer of one thing rather than a second chat: type here and it appears on
/// the phone as it streams; approve a plan there and the card here updates itself.
///
/// It reuses <see cref="Bubble"/> — the control the share thread in <see cref="MainForm"/>
/// already uses — because a hand-off between two devices and a turn of conversation are the
/// same shape, and two implementations of "a chat thread" in one app is one too many.
///
/// Motion budget is §1's "calm": the 180ms fade-in the rest of the desktop app uses, and
/// nothing else. Text arriving is content, not animation.
/// </summary>
public sealed class AssistantForm : Form
{
    private const int FadeInMs = 180;
    private const int FadeInTickMs = 15;

    /// <summary>Controls kept on screen. The transcript itself holds more; this window
    /// renders the tail of it, which is the part anybody scrolls.</summary>
    private const int MaxControls = 120;

    private readonly AiAssistant _assistant;
    private readonly AiHealth _health;

    private readonly Panel _header;
    private readonly Label _backend;
    private readonly Panel _thread;
    private readonly Label _empty;
    private readonly TextBox _draft;
    private readonly TokenButton _send;
    private readonly TokenButton _clear;
    private readonly Label _hint;

    private readonly Dictionary<string, TurnViews> _views = [];

    /// <summary>Turn ids oldest-first, so trimming drops whole turns rather than leaving a
    /// plan card behind the bubble it belonged to.</summary>
    private readonly List<string> _order = [];
    private readonly System.Windows.Forms.Timer _fadeTimer = new() { Interval = FadeInTickMs };
    private DateTime _fadeStart;
    private PaletteColors _colors = SystemTheme.Colors;

    public AssistantForm(AiAssistant assistant, AiHealth health)
    {
        _assistant = assistant;
        _health = health;

        Text = $"{ServerInfo.Name} — Assistant";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(580, 700);
        MinimumSize = new Size(440, 480);
        Padding = new Padding(20);
        Icon = BrandMark.CreateIcon(32, Color.White, badge: Palette.Light.Accent);

        _header = new Panel { Dock = DockStyle.Top, Height = 44 };
        _header.Paint += (_, e) => DrawHeader(e.Graphics, _header.ClientRectangle);

        _backend = new Label
        {
            Dock = DockStyle.Top,
            Height = 24,
            Font = Fonts.Caption,
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
        };

        _thread = new Panel { Dock = DockStyle.Fill, AutoScroll = true };

        // §11 rule 2: an empty screen states the state. A blank panel reads as one still
        // loading, and the useful thing to say here is what this window is *for*.
        _empty = new Label
        {
            Dock = DockStyle.Fill,
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "Ask the assistant something.\n\nThis is the same conversation your phone sees. "
                 + "It can act on this PC too — every action is listed and approved before "
                 + "anything happens.",
        };

        _draft = new TextBox
        {
            Dock = DockStyle.Fill,
            Font = Fonts.Body,
            Multiline = true,
            BorderStyle = BorderStyle.FixedSingle,
            Margin = new Padding(0, 0, 8, 0),
        };
        _draft.KeyDown += OnDraftKeyDown;
        _draft.TextChanged += (_, _) => UpdateComposer();

        _send = new TokenButton { Text = "Send", Dock = DockStyle.Fill };
        _send.Click += (_, _) => SendOrStop();

        _clear = new TokenButton { Text = "Clear", Secondary = true, Dock = DockStyle.Fill };
        _clear.Click += (_, _) => ClearConversation();

        _hint = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 22,
            Font = Fonts.Caption,
            AutoSize = false,
            Text = "Enter sends · Shift+Enter starts a new line",
        };

        var composerRow = new TableLayoutPanel
        {
            Dock = DockStyle.Bottom,
            Height = LogicalToDeviceUnits(76),
            ColumnCount = 2,
            RowCount = 2,
        };
        composerRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        composerRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(96)));
        composerRow.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        composerRow.RowStyles.Add(new RowStyle(SizeType.Absolute, LogicalToDeviceUnits(34)));
        composerRow.Controls.Add(_draft, 0, 0);
        composerRow.SetRowSpan(_draft, 2);
        composerRow.Controls.Add(_send, 1, 0);
        composerRow.Controls.Add(_clear, 1, 1);

        // Docking is resolved in reverse order of addition — the control added last claims
        // its edge first — so the two Fills go in first and the chrome is added outwards.
        Controls.Add(_thread);
        Controls.Add(_empty);
        Controls.Add(_hint);
        Controls.Add(composerRow);
        Controls.Add(_backend);
        Controls.Add(_header);

        _fadeTimer.Tick += (_, _) => TickFade();

        _assistant.Conversation.TurnChanged += OnTurnChanged;
        _assistant.Conversation.Delta += OnDelta;
        _assistant.Conversation.Reset += OnReset;
        _health.Changed += OnHealthChanged;

        ApplyTheme();
        Rebuild();
    }

    /// <summary>Re-apply the palette — the Windows light/dark setting can have changed
    /// since this window was last shown. Not an override of <see cref="Control.Refresh"/>:
    /// that one repaints, this one re-themes.</summary>
    public void RefreshTheme()
    {
        ApplyTheme();
    }

    /// <summary>Show with the same 180ms ease-out fade the rest of the desktop app uses
    /// (docs/design-system.md §6).</summary>
    public void ShowAnimated()
    {
        if (Visible)
        {
            BringToFront();
            return;
        }

        Opacity = 0;
        Show();
        _fadeStart = DateTime.UtcNow;
        _fadeTimer.Start();
        _draft.Focus();
    }

    private void TickFade()
    {
        var elapsed = (DateTime.UtcNow - _fadeStart).TotalMilliseconds;
        var t = Math.Clamp(elapsed / FadeInMs, 0, 1);
        // Quadratic ease-out: fast start, settles into place.
        Opacity = 1 - Math.Pow(1 - t, 2);
        if (t >= 1) _fadeTimer.Stop();
    }

    private void ApplyTheme()
    {
        _colors = SystemTheme.Colors;

        BackColor = _colors.Bg;
        _header.BackColor = _colors.Bg;
        _header.Invalidate();

        foreach (var caption in new[] { _backend, _empty, _hint })
        {
            caption.BackColor = _colors.Bg;
            caption.ForeColor = _colors.TextSecondary;
        }

        // Matched to Bubble's own background so the thread reads as one sheet rather than
        // as a stack of full-width bands.
        _thread.BackColor = _colors.Surface;
        _empty.BackColor = _colors.Surface;

        _draft.BackColor = _colors.SurfaceRaised;
        _draft.ForeColor = _colors.TextPrimary;

        _send.ApplyTheme(_colors);
        _clear.ApplyTheme(_colors);

        foreach (var views in _views.Values)
        {
            views.Bubble.ApplyTheme(_colors);
            views.Card?.ApplyTheme(_colors);
        }

        ApplyBackendState();
        if (IsHandleCreated) ApplyTitleBarTheme();
    }

    private void ApplyBackendState()
    {
        var ready = _health.State == AiHealth.Ready;
        _backend.ForeColor = ready ? _colors.TextSecondary : _colors.Warning;
        // Not-ready always names the missing app and where to fix it: this window is
        // often the first place somebody finds out the assistant has a backend at all,
        // and AiHealth's detail is a diagnostic, not an instruction.
        _backend.Text = ready
            ? "agent-platform is answering."
            : "Needs agent-platform on this PC — press Set up in the Portal Remote window.";
        UpdateComposer();
    }

    // ---- transcript ----------------------------------------------------------------

    /// <summary>Throw the thread away and rebuild it. Only for a reset — a snapshot, a
    /// clear, a regenerate, a trim — since an upsert cannot express "and these are gone".</summary>
    private void Rebuild()
    {
        _thread.SuspendLayout();
        foreach (var views in _views.Values) views.Remove(_thread);
        _views.Clear();
        _order.Clear();

        foreach (var turn in _assistant.Conversation.Turns()) Apply(turn);
        _thread.ResumeLayout();

        UpdateEmptyState();
        UpdateComposer();
        ScrollToEnd();
    }

    /// <summary>Add or update one turn. Docked children claim their edge in reverse index
    /// order, so index 0 is laid out last and lands lowest — where the newest belongs, and
    /// where a turn's plan card belongs relative to the turn's own bubble.</summary>
    private void Apply(ChatTurn turn)
    {
        if (!_views.TryGetValue(turn.Id, out var views))
        {
            var bubble = new Bubble(incoming: turn.Role != ChatTurn.User);
            bubble.ApplyTheme(_colors);
            _thread.Controls.Add(bubble);
            _thread.Controls.SetChildIndex(bubble, 0);
            views = new TurnViews(bubble);
            _views[turn.Id] = views;
            _order.Add(turn.Id);
        }

        views.Bubble.SetText(Body(turn), Meta(turn));

        if (turn.Plan is { } plan)
        {
            if (views.Card is null)
            {
                views.Card = new PlanCard(
                    approved => OnConfirm(turn.Id, approved),
                    () => _assistant.Cancel(turn.Id));
                views.Card.ApplyTheme(_colors);
                _thread.Controls.Add(views.Card);
                _thread.Controls.SetChildIndex(views.Card, 0);
            }

            views.Card.Bind(plan);
        }

        Trim();
    }

    /// <summary>An assistant turn is empty for the instant between the request going out
    /// and the first token landing; an empty bubble looks like a bug.</summary>
    private static string Body(ChatTurn turn) =>
        turn.Text.Length > 0 ? turn.Text : turn.Streaming ? "…" : turn.Error is not null ? "—" : string.Empty;

    /// <summary>The quiet line under a turn: why it stopped, or what it is still doing.
    /// The time is the fallback, because a transcript that now survives a restart is one
    /// where "when was this" is a real question.</summary>
    private static string Meta(ChatTurn turn)
    {
        var when = DateTimeOffset.FromUnixTimeMilliseconds(turn.At).ToLocalTime().ToString("HH:mm");
        if (turn.Error is { Length: > 0 } error) return error;
        if (turn.Deciding) return "Working out what to do on the PC…";
        if (turn.Incomplete) return $"{when} · cut off";
        return when;
    }

    private void Trim()
    {
        while (_thread.Controls.Count > MaxControls && _order.Count > 1)
        {
            var oldest = _order[0];
            _order.RemoveAt(0);
            if (_views.Remove(oldest, out var views)) views.Remove(_thread);
        }
    }

    private void OnTurnChanged(ChatTurn turn) => OnUi(() =>
    {
        Apply(turn);
        UpdateEmptyState();
        UpdateComposer();
        ScrollToEnd();
    });

    /// <summary>The text itself is already on the turn — this window holds the live model
    /// object, not a copy — so a delta only has to re-measure the bubble.</summary>
    private void OnDelta(string id, string text) => OnUi(() =>
    {
        if (!_views.TryGetValue(id, out var views)) return;
        if (_assistant.Conversation.Find(id) is not { } turn) return;
        views.Bubble.SetText(Body(turn), Meta(turn));
        ScrollToEnd();
    });

    private void OnReset() => OnUi(Rebuild);

    private void OnHealthChanged(object _) => OnUi(ApplyBackendState);

    /// <summary>Every one of these is raised on a Kestrel thread or a background task;
    /// hop back before touching a control.</summary>
    private void OnUi(Action action)
    {
        if (!IsHandleCreated || IsDisposed) return;
        try
        {
            BeginInvoke(action);
        }
        catch (ObjectDisposedException)
        {
            // The window closed between the check and the post.
        }
    }

    private void UpdateEmptyState()
    {
        var empty = _views.Count == 0;
        _empty.Visible = empty;
        _thread.Visible = !empty;
    }

    private void ScrollToEnd()
    {
        if (_thread.Controls.Count == 0) return;
        _thread.ScrollControlIntoView(_thread.Controls[0]);
    }

    // ---- composing -----------------------------------------------------------------

    private void OnDraftKeyDown(object? sender, KeyEventArgs e)
    {
        // Enter sends, Shift+Enter breaks the line — the shape every chat box has.
        if (e.KeyCode != Keys.Enter || e.Shift) return;
        e.SuppressKeyPress = true;
        SendOrStop();
    }

    private void SendOrStop()
    {
        if (_assistant.Busy)
        {
            _assistant.Stop();
            return;
        }

        var text = _draft.Text.Trim();
        if (text.Length == 0) return;

        // Cleared only by a send that started, never by a failure: retyping a question
        // because the backend blinked is the rudest possible way to report that it did.
        _draft.Clear();
        _assistant.Ask(text);
        UpdateComposer();
    }

    private void UpdateComposer()
    {
        var busy = _assistant.Busy;
        _send.Text = busy ? "Stop" : "Send";
        _send.Enabled = busy || _draft.Text.Trim().Length > 0;
        _clear.Enabled = _views.Count > 0;
    }

    private void ClearConversation()
    {
        if (_views.Count == 0) return;

        var confirmed = TokenDialog.Show(this,
            "Clear the conversation?",
            "It is deleted from this PC and disappears from your phone as well. "
            + "There is no way to get it back.",
            confirmText: "Clear",
            cancelText: "Cancel",
            destructive: true);
        if (!confirmed) return;

        _assistant.Clear();
    }

    // ---- approving -----------------------------------------------------------------

    /// <summary>
    /// Run the ticked subset. The second confirmation on the two power modes that lose
    /// unsaved work is the same gate the phone puts up and the same one the TV remote's
    /// power menu already had — a mis-tap here costs whatever was open.
    /// </summary>
    private void OnConfirm(string id, IReadOnlyList<int> approved)
    {
        var plan = _assistant.Conversation.Find(id)?.Plan;
        if (plan is null) return;

        if (plan.Actions.Any(a => a.Destructive && approved.Contains(a.Index)))
        {
            var confirmed = TokenDialog.Show(this,
                "Shut down or restart this PC?",
                "Anything unsaved on this PC will be lost.",
                confirmText: "Do it",
                cancelText: "Cancel",
                destructive: true);
            if (!confirmed) return;
        }

        _assistant.Confirm(id, approved);
    }

    // ---- chrome --------------------------------------------------------------------

    private void DrawHeader(Graphics g, Rectangle bounds)
    {
        const int markSize = 26;
        const int gap = 10;

        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        var top = bounds.Y + (bounds.Height - markSize) / 2f;
        BrandMark.Draw(g, new RectangleF(bounds.X, top, markSize, markSize), _colors.Accent);

        using var primary = new SolidBrush(_colors.TextPrimary);
        var size = g.MeasureString("Assistant", Fonts.Heading);
        g.DrawString("Assistant", Fonts.Heading, primary,
            bounds.X + markSize + gap, bounds.Y + (bounds.Height - size.Height) / 2f);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);

        // Windows 11 only; DwmSetWindowAttribute no-ops on Windows 10 rather than needing
        // an OS-version branch.
        const int dwmwaWindowCornerPreference = 33;
        const int dwmwcRound = 2;
        var cornerPreference = dwmwcRound;
        NativeMethods.DwmSetWindowAttribute(Handle, dwmwaWindowCornerPreference, ref cornerPreference, sizeof(int));

        ApplyTitleBarTheme();
    }

    private void ApplyTitleBarTheme()
    {
        const int dwmwaUseImmersiveDarkMode = 20;
        var useDarkMode = SystemTheme.IsDark() ? 1 : 0;
        NativeMethods.DwmSetWindowAttribute(Handle, dwmwaUseImmersiveDarkMode, ref useDarkMode, sizeof(int));
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // The tray owns this window's lifetime, same as MainForm: hide, don't destroy.
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }

        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _assistant.Conversation.TurnChanged -= OnTurnChanged;
            _assistant.Conversation.Delta -= OnDelta;
            _assistant.Conversation.Reset -= OnReset;
            _health.Changed -= OnHealthChanged;
            _fadeTimer.Dispose();
            Icon?.Dispose();
        }

        base.Dispose(disposing);
    }

    /// <summary>The controls one turn owns: its bubble, and the plan card under it when
    /// the same answer also proposed doing something.</summary>
    private sealed class TurnViews(Bubble bubble)
    {
        public Bubble Bubble { get; } = bubble;

        public PlanCard? Card { get; set; }

        public void Remove(Panel thread)
        {
            thread.Controls.Remove(Bubble);
            Bubble.Dispose();
            if (Card is null) return;
            thread.Controls.Remove(Card);
            Card.Dispose();
        }
    }
}

/// <summary>
/// The plan, inline in the thread rather than in a modal.
///
/// This is what makes the assistant an agent rather than a chatbot that occasionally raises
/// a dialog: the proposal sits in the conversation it came from, and it stays there
/// afterwards saying what happened. Nothing auto-runs, approval is still per action, and
/// the destructive modes still take a second confirmation (docs/phase7-assistant.md §7).
/// </summary>
internal sealed class PlanCard : Panel
{
    private const int Pad = 12;
    private const TextFormatFlags TextFlags = TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix;

    /// <summary>Matches <see cref="Bubble"/>'s own cap, so the card lines up with the
    /// reply it belongs to instead of overhanging it.</summary>
    private const float MaxFraction = 0.86f;

    private readonly Action<IReadOnlyList<int>> _run;
    private readonly Action _notNow;
    private readonly List<CheckBox> _ticks = [];
    private readonly List<Label> _lines = [];
    private readonly TokenButton _primary;
    private readonly TokenButton _secondary;

    private ChatPlan _plan = new();
    private PaletteColors _colors = SystemTheme.Colors;
    private Rectangle _card;
    private Rectangle _headingRect;
    private Rectangle _thoughtRect;
    private string _heading = string.Empty;
    private string _thought = string.Empty;

    public PlanCard(Action<IReadOnlyList<int>> run, Action notNow)
    {
        _run = run;
        _notNow = notNow;
        Dock = DockStyle.Top;
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);

        _primary = new TokenButton { Text = "Run" };
        _primary.Click += (_, _) => _run(Approved());
        _secondary = new TokenButton { Text = "Not now", Secondary = true };
        _secondary.Click += (_, _) => _notNow();

        Controls.Add(_primary);
        Controls.Add(_secondary);
    }

    public void ApplyTheme(PaletteColors colors)
    {
        _colors = colors;
        BackColor = colors.Surface;
        _primary.ApplyTheme(colors);
        _secondary.ApplyTheme(colors);
        Measure();
        Invalidate();
    }

    public void Bind(ChatPlan plan)
    {
        _plan = plan;

        _heading = plan.State switch
        {
            PlanState.Pending => plan.Actions.Count == 1 ? "Do this on the PC?" : "Do these on the PC?",
            PlanState.Ran => "Done on the PC",
            PlanState.Cancelled => "Not run",
            PlanState.Expired => "This was never run — ask again",
            _ => "Could not work out an action",
        };
        // The model's own reasoning, which is the only thing that explains *why* these
        // actions and not others. Its error takes the same slot when there is one.
        _thought = plan.Error ?? plan.Thought;

        Rebuild();
    }

    /// <summary>The rows, which are checkboxes while the plan is still a question and
    /// plain lines once it is a record of what happened.</summary>
    private void Rebuild()
    {
        foreach (var tick in _ticks)
        {
            Controls.Remove(tick);
            tick.Dispose();
        }
        _ticks.Clear();

        foreach (var line in _lines)
        {
            Controls.Remove(line);
            line.Dispose();
        }
        _lines.Clear();

        var pending = _plan.State == PlanState.Pending;
        _primary.Visible = pending;
        _secondary.Visible = pending;

        if (pending)
        {
            // Everything starts ticked: the model was asked to do this, and a card that
            // starts empty makes the common case — "yes, all of that" — the fiddly one.
            foreach (var action in _plan.Actions)
            {
                var tick = new CheckBox
                {
                    Text = action.Summary,
                    Checked = true,
                    Font = Fonts.Body,
                    AutoSize = false,
                    UseMnemonic = false,
                    Tag = action.Index,
                };
                tick.CheckedChanged += (_, _) => UpdatePrimary();
                _ticks.Add(tick);
                Controls.Add(tick);
            }
        }
        else if (_plan.Results.Count > 0)
        {
            foreach (var result in _plan.Results)
                _lines.Add(AddLine($"{(result.Ok ? "✓" : "✕")}  {result.Detail}", result.Ok));
        }
        else
        {
            // Cancelled, expired or failed: no results, so the actions themselves are the
            // record of what was proposed and never done.
            foreach (var action in _plan.Actions) _lines.Add(AddLine($"·  {action.Summary}", ok: true));
        }

        Measure();
        UpdatePrimary();
        Invalidate();
    }

    private Label AddLine(string text, bool ok)
    {
        var line = new Label { Text = text, Font = Fonts.Body, AutoSize = false, UseMnemonic = false, Tag = ok };
        Controls.Add(line);
        return line;
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        Measure();
    }

    private void Measure()
    {
        if (Width <= 0) return;

        var pad = LogicalToDeviceUnits(Pad);
        var gap = LogicalToDeviceUnits(6);
        var rowGap = LogicalToDeviceUnits(4);
        var buttonHeight = LogicalToDeviceUnits(32);
        var cardWidth = Math.Max(LogicalToDeviceUnits(180), (int)(Width * MaxFraction));
        var inner = cardWidth - pad * 2;

        var y = pad;
        var headingHeight = TextRenderer.MeasureText(_heading, Fonts.Heading, new Size(inner, int.MaxValue), TextFlags).Height;
        _headingRect = new Rectangle(pad, y, inner, headingHeight);
        y += headingHeight + rowGap;

        if (_thought.Length > 0)
        {
            var thoughtHeight = TextRenderer.MeasureText(_thought, Fonts.Caption, new Size(inner, int.MaxValue), TextFlags).Height;
            _thoughtRect = new Rectangle(pad, y, inner, thoughtHeight);
            y += thoughtHeight + gap;
        }
        else
        {
            _thoughtRect = Rectangle.Empty;
        }

        foreach (var tick in _ticks)
        {
            // The box eats about 20px before the caption starts wrapping.
            var tickHeight = Math.Max(LogicalToDeviceUnits(24),
                TextRenderer.MeasureText(tick.Text, Fonts.Body, new Size(inner - LogicalToDeviceUnits(24), int.MaxValue), TextFlags).Height + 4);
            tick.SetBounds(pad, y, inner, tickHeight);
            tick.ForeColor = IsDestructive(tick) ? _colors.Danger : _colors.TextPrimary;
            tick.BackColor = _colors.SurfaceMuted;
            y += tickHeight + rowGap;
        }

        foreach (var line in _lines)
        {
            var lineHeight = TextRenderer.MeasureText(line.Text, Fonts.Body, new Size(inner, int.MaxValue), TextFlags).Height + 2;
            line.SetBounds(pad, y, inner, lineHeight);
            line.ForeColor = line.Tag is false ? _colors.Danger : _colors.TextSecondary;
            line.BackColor = _colors.SurfaceMuted;
            y += lineHeight + rowGap;
        }

        if (_primary.Visible)
        {
            y += rowGap;
            var half = (inner - gap) / 2;
            _primary.SetBounds(pad, y, half, buttonHeight);
            _secondary.SetBounds(pad + half + gap, y, inner - half - gap, buttonHeight);
            y += buttonHeight;
        }

        y += pad;
        _card = new Rectangle(0, 0, cardWidth, y);

        // Guarded: the setter re-enters OnResize, and an unchanged value would still cost
        // a layout pass per resize tick.
        var height = y + gap;
        if (Height != height) Height = height;
    }

    private bool IsDestructive(CheckBox tick) =>
        _plan.Actions.Any(a => a.Index == (int)tick.Tag! && a.Destructive);

    /// <summary>
    /// A one-action plan is approved by pressing the thing it does — "Mute", "Shut down" —
    /// rather than a generic Run beside a sentence that already says it. The word comes
    /// from the PC, which is the side that knows what the action presses.
    /// </summary>
    private void UpdatePrimary()
    {
        if (!_primary.Visible) return;

        var approved = Approved();
        _primary.Enabled = approved.Count > 0;
        _primary.Text = approved.Count == 1 && _plan.Actions.FirstOrDefault(a => a.Index == approved[0]) is { } only
            ? only.Verb
            : "Run";
    }

    private List<int> Approved() => _ticks.Where(t => t.Checked).Select(t => (int)t.Tag!).ToList();

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        using (var path = RoundedRect.Path(new Rectangle(_card.X, _card.Y, _card.Width - 1, _card.Height - 1),
                   RoundedRect.RadiusCard))
        {
            using var fill = new SolidBrush(_colors.SurfaceMuted);
            using var border = new Pen(_plan.State == PlanState.Pending ? _colors.Accent : _colors.Border);
            g.FillPath(fill, path);
            g.DrawPath(border, path);
        }

        var headingColor = _plan.State == PlanState.Failed ? _colors.Danger : _colors.TextPrimary;
        TextRenderer.DrawText(g, _heading, Fonts.Heading, _headingRect, headingColor, TextFlags);
        if (_thoughtRect != Rectangle.Empty)
            TextRenderer.DrawText(g, _thought, Fonts.Caption, _thoughtRect, _colors.TextSecondary, TextFlags);
    }
}
