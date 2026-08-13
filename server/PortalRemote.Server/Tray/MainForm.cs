using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using Microsoft.Win32;
using PortalRemote.Ai;
using PortalRemote.Config;
using PortalRemote.Devices;
using PortalRemote.Control;
using PortalRemote.Files;
using PortalRemote.Pairing;
using PortalRemote.Share;
using PortalRemote.Theme;

namespace PortalRemote.Tray;

/// <summary>
/// The desktop app's one window. Two columns — the conversation with the phone on
/// the left, everything about this PC on the right — so the whole app is legible
/// without scrolling, per docs/design-system.md §1 ("calm and clarity, not motion")
/// and §12.
///
/// The left column is the share thread once anything has ever paired, and the
/// pairing QR while nothing has: a paired PC whose main view is still "scan to
/// pair" is answering a question the user already answered. Either half is one
/// button from the other.
/// </summary>
public sealed class MainForm : Form
{
    private const int FadeInMs = 180;
    private const int FadeInTickMs = 15;

    /// <summary>Bubbles kept on screen. The hub only remembers 50, but this window
    /// can outlive several of those over a long session.</summary>
    private const int MaxBubbles = 60;

    private readonly ServerConfig _config;
    private readonly ConnectionState _connectionState;
    private readonly ShareHub _share;
    private readonly AiHealth _ai;
    private readonly DeviceRegistry _devices;

    private readonly Panel _header;
    private readonly Panel _statusCard;
    private TableLayoutPanel _settingsColumn = null!;
    private int _statusRow;

    // ---- left column, front: the share thread --------------------------------
    private readonly Panel _sharePanel;
    private readonly Panel _thread;
    private readonly Label _shareHeading;
    private readonly Label _threadEmpty;
    private readonly TextBox _composer;
    private readonly TokenButton _send;
    private readonly TokenButton _attach;
    private readonly Label _composerHint;
    private readonly TokenButton _showPair;

    // ---- left column, behind: pairing ----------------------------------------
    private readonly Panel _pairPanel;
    private readonly PictureBox _qr;
    private readonly Panel _qrCard;
    private readonly Label _pairHeading;
    private readonly Label _address;
    private readonly Label _hint;
    private readonly TokenButton _copy;
    private readonly TokenButton _showThread;

    // ---- right column --------------------------------------------------------
    private readonly Label _settingsHeading;
    private readonly NumericUpDown _port;
    private readonly Label _portLabel;
    private readonly Label _portNote;
    private readonly Label _shareLabel;
    private readonly TextBox _shareBox;
    private readonly TokenButton _changeShare;
    private readonly TokenButton _openShare;
    private readonly Label _castLabel;
    private readonly TextBox _castBox;
    private readonly TokenButton _copyCast;
    private readonly TokenButton _openCast;
    private readonly Label _aiLabel;
    private readonly Label _aiState;
    private readonly Label _aiDetail;
    private readonly TokenButton _aiOpen;
    private readonly TokenButton _aiCheck;
    private readonly CheckBox _startWithWindows;
    private readonly TokenButton _rotate;
    private readonly Label _rotateNote;
    private readonly Label _footer;

    private readonly System.Windows.Forms.Timer _fadeTimer = new() { Interval = FadeInTickMs };
    // Same 3s the tray icon holds its error state for, so the two surfaces don't
    // disagree about whether the refusal is still news.
    private readonly System.Windows.Forms.Timer _rejectedRevertTimer = new() { Interval = 3000 };
    private string? _rejectedPeer;
    private DateTime _fadeStart;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <summary>Opens the assistant window. Set by the tray, which owns every window's
    /// lifetime — this one only has to know that the button leads somewhere.</summary>
    public Action? OpenAssistant { get; init; }

    public MainForm(ServerConfig config, ConnectionState connectionState, ShareHub share, AiHealth ai,
        DeviceRegistry devices)
    {
        _config = config;
        _connectionState = connectionState;
        _share = share;
        _ai = ai;
        _devices = devices;

        Text = ServerInfo.Name;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(860, 640);
        MinimumSize = Size; // the layout below is sized to fit exactly this
        Padding = new Padding(20);

        // Taskbar/Alt-Tab entry. Fixed to the light accent in both themes: this is
        // the app's identity in the taskbar (same tile as app.ico and the Android
        // launcher icon), not a themed surface like the rest of this window.
        Icon = BrandMark.CreateIcon(32, Color.White, badge: Palette.Light.Accent);

        _header = new Panel { Dock = DockStyle.Top, Height = 56 };
        _header.Paint += (_, e) => DrawHeader(e.Graphics, _header.ClientRectangle);

        _footer = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 24,
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleLeft,
            Text = ServerConfig.DefaultConfigPath,
            Cursor = Cursors.Hand,
        };
        _footer.Click += (_, _) => OpenPath(ServerConfig.ConfigDirectory);

        // ---- left column, front: the thread ---------------------------------------
        _shareHeading = new Label { Text = "Shared with your phone", Font = Fonts.Heading, Dock = DockStyle.Fill };
        _showPair = new TokenButton { Text = "Pair a phone", Glyph = Glyphs.QrCode, Secondary = true, Dock = DockStyle.Right, Width = 132 };
        _showPair.Click += (_, _) => ShowPairing(true);

        _thread = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
        _threadEmpty = new Label
        {
            Dock = DockStyle.Fill,
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "Nothing shared yet.\n\nType a note below, drop a file here, or press "
                 + "Ctrl+Alt+V anywhere to send this PC's clipboard.",
        };

        _composer = new TextBox
        {
            Dock = DockStyle.Fill,
            Font = Fonts.Body,
            BorderStyle = BorderStyle.FixedSingle,
            Margin = new Padding(0, 3, 8, 0),
        };
        // Enter sends. A text box whose Enter does nothing, next to a Send button, is
        // the one thing every chat surface has trained people out of expecting.
        _composer.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter || e.Shift) return;
            e.SuppressKeyPress = true; // otherwise the box beeps at the unhandled Enter
            SendComposerText();
        };

        _attach = new TokenButton { Text = "Attach", Glyph = Glyphs.Attach, Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _attach.Click += (_, _) => PickAndSendFile();
        _send = new TokenButton { Text = "Send", Glyph = Glyphs.Send, Dock = DockStyle.Fill };
        _send.Click += (_, _) => SendComposerText();

        _composerHint = new Label { Font = Fonts.Caption, Dock = DockStyle.Bottom, Height = 32, AutoSize = false };

        var shareHeader = new Panel { Dock = DockStyle.Top, Height = 32 };
        shareHeader.Controls.Add(_shareHeading);
        shareHeader.Controls.Add(_showPair);

        var composerRow = TrailingButtonRow();
        composerRow.Dock = DockStyle.Bottom;
        composerRow.Height = LogicalToDeviceUnits(34);
        composerRow.Controls.Add(_composer, 0, 0);
        composerRow.Controls.Add(_attach, 1, 0);
        composerRow.Controls.Add(_send, 2, 0);

        _sharePanel = new Panel { Dock = DockStyle.Fill };
        // Docking is resolved in reverse order of addition — the control added last
        // claims its edge first — so the two Fills go in first and the bottom stack is
        // added upwards-last: the composer, then the hint that sits under it.
        _sharePanel.Controls.Add(_thread);
        _sharePanel.Controls.Add(_threadEmpty);
        _sharePanel.Controls.Add(shareHeader);
        _sharePanel.Controls.Add(composerRow);
        _sharePanel.Controls.Add(_composerHint);

        // ---- left column, behind: pairing -----------------------------------------
        _pairHeading = new Label { Text = "Scan to pair a phone", Font = Fonts.Heading, Dock = DockStyle.Fill };
        _showThread = new TokenButton { Text = "Messages", Glyph = Glyphs.Conversation, Secondary = true, Dock = DockStyle.Right, Width = 118 };
        _showThread.Click += (_, _) => ShowPairing(false);

        var pairHeader = new Panel { Dock = DockStyle.Top, Height = 32 };
        pairHeader.Controls.Add(_pairHeading);
        pairHeader.Controls.Add(_showThread);

        _qr = new PictureBox { SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Fill };
        _qrCard = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12) };
        _qrCard.Paint += (_, e) => DrawCard(e.Graphics, _qrCard.ClientRectangle);
        _qrCard.Controls.Add(_qr);

        // Mono is for the address itself and nothing else — prose set in it reads as
        // something to be typed literally.
        _address = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 26,
            Font = new Font(FontFamily.GenericMonospace, 9.5f),
            TextAlign = ContentAlignment.MiddleCenter,
        };

        _hint = new Label
        {
            Dock = DockStyle.Bottom,
            // Four caption lines: at this column width the firewall sentence wraps to
            // four, and the old 56 cut "networks." off the end of it.
            Height = 76,
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "Or pick this PC from the list on the phone — that way asks permission "
                 + "here first. Phone and PC must be on the same Wi-Fi; if it will not "
                 + $"connect, allow port {config.RunningPort} through Windows Firewall on "
                 + "Private networks.",
        };

        _copy = new TokenButton { Text = "Copy address", Glyph = Glyphs.Copy, Dock = DockStyle.Bottom, Height = 34 };
        _copy.Click += (_, _) => CopyAddress();

        _pairPanel = new Panel { Dock = DockStyle.Fill, Visible = false };
        _pairPanel.Controls.Add(_qrCard);
        _pairPanel.Controls.Add(pairHeader);
        _pairPanel.Controls.Add(_address);
        _pairPanel.Controls.Add(_hint);
        _pairPanel.Controls.Add(_copy);

        var leftColumn = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 0, 12, 0) };
        leftColumn.Controls.Add(_sharePanel);
        leftColumn.Controls.Add(_pairPanel);

        // ---- right column: status + settings -------------------------------------
        _statusCard = new Panel { Dock = DockStyle.Fill };
        _statusCard.Paint += (_, e) => DrawStatus(e.Graphics, _statusCard.ClientRectangle);

        _settingsHeading = new Label { Text = "Settings", Font = Fonts.Heading, Dock = DockStyle.Fill };

        _portLabel = new Label { Text = "Port", Font = Fonts.Body, AutoSize = true, Location = new Point(0, 7) };
        _port = new NumericUpDown
        {
            Minimum = 1024,
            Maximum = 65535,
            Value = config.Port,
            Width = 96,
            Location = new Point(96, 3),
            Font = Fonts.Body,
        };
        _port.ValueChanged += (_, _) => SavePort();
        var portRow = new Panel { Dock = DockStyle.Fill };
        portRow.Controls.Add(_portLabel);
        portRow.Controls.Add(_port);

        _portNote = new Label
        {
            Font = Fonts.Caption,
            Dock = DockStyle.Fill,
            // Label.AutoSize defaults to true, which keeps a docked label on one line
            // and clips the overflow instead of wrapping it.
            AutoSize = false,
            Text = "Applies the next time Portal Remote starts. Paired phones have to "
                 + "scan the new code afterwards.",
        };

        _shareLabel = new Label { Text = "Shared folder", Font = Fonts.Body, Dock = DockStyle.Fill };
        _shareBox = new TextBox
        {
            ReadOnly = true,
            Dock = DockStyle.Fill,
            Font = Fonts.Body,
            BorderStyle = BorderStyle.FixedSingle,
            Margin = new Padding(0, 3, 8, 0),
        };
        _changeShare = new TokenButton { Text = "Change", Glyph = Glyphs.Edit, Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _changeShare.Click += (_, _) => ChangeShareFolder();
        _openShare = new TokenButton { Text = "Open", Glyph = Glyphs.FolderOpen, Secondary = true, Dock = DockStyle.Fill };
        _openShare.Click += (_, _) => OpenPath(_config.ResolvedShareRoot());

        var shareRow = TrailingButtonRow();
        shareRow.Controls.Add(_shareBox, 0, 0);
        shareRow.Controls.Add(_changeShare, 1, 0);
        shareRow.Controls.Add(_openShare, 2, 0);

        // The receiver URL used to exist only in the startup banner, which is only on
        // screen if the server was started from a console. Typed into a TV's browser
        // it makes that TV a cast target, so it has to be findable from the app.
        _castLabel = new Label { Text = "Cast to a screen", Font = Fonts.Body, Dock = DockStyle.Fill };
        _castBox = new TextBox
        {
            ReadOnly = true,
            Dock = DockStyle.Fill,
            Font = Fonts.Body,
            BorderStyle = BorderStyle.FixedSingle,
            Margin = new Padding(0, 3, 8, 0),
        };
        _copyCast = new TokenButton { Text = "Copy", Glyph = Glyphs.Copy, Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _copyCast.Click += (_, _) => CopyText(PairingService.ReceiverUrl(_config));
        // Opening it here makes *this* PC the cast target, which is the common case
        // and saves typing the address anywhere at all.
        _openCast = new TokenButton { Text = "Open", Glyph = Glyphs.OpenInNew, Secondary = true, Dock = DockStyle.Fill };
        _openCast.Click += (_, _) => OpenPath(PairingService.ReceiverUrl(_config));

        var castRow = TrailingButtonRow();
        castRow.Controls.Add(_castBox, 0, 0);
        castRow.Controls.Add(_copyCast, 1, 0);
        castRow.Controls.Add(_openCast, 2, 0);

        // The assistant's backend is a separate app started on *this* PC, and until now
        // this window said nothing about it: the phone was the only surface that could
        // tell you it wasn't running, which is the wrong machine to find that out on.
        // See docs/phase7-assistant.md §4.
        _aiLabel = new Label { Text = "Assistant", Font = Fonts.Body, Dock = DockStyle.Fill };
        _aiState = new Label { Font = Fonts.Body, Dock = DockStyle.Fill };
        _aiCheck = new TokenButton { Text = "Check", Glyph = Glyphs.Refresh, Secondary = true, Dock = DockStyle.Fill };
        _aiCheck.Click += (_, _) => CheckAi();
        // The conversation is this PC's now, so it is reachable from this PC — the phone
        // is no longer the only place it exists. See Tray/AssistantForm.cs.
        _aiOpen = new TokenButton { Text = "Chat", Glyph = Glyphs.Assistant, Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _aiOpen.Click += (_, _) => OpenAssistant?.Invoke();
        _aiDetail = new Label { Font = Fonts.Caption, Dock = DockStyle.Fill, AutoSize = false };

        var aiRow = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 4, RowCount = 1 };
        aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(80)));
        aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(72)));
        aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(72)));
        aiRow.Controls.Add(_aiLabel, 0, 0);
        aiRow.Controls.Add(_aiState, 1, 0);
        aiRow.Controls.Add(_aiOpen, 2, 0);
        aiRow.Controls.Add(_aiCheck, 3, 0);

        _startWithWindows = new CheckBox
        {
            Text = "Start Portal Remote when I sign in",
            Font = Fonts.Body,
            Dock = DockStyle.Fill,
            Checked = StartsWithWindows(),
        };
        // Wired after Checked is set so reading the current state doesn't write it back.
        _startWithWindows.CheckedChanged += (_, _) => SetStartWithWindows(_startWithWindows.Checked);

        _rotate = new TokenButton { Text = "Rotate pairing token", Glyph = Glyphs.Lock, Secondary = true, Dock = DockStyle.Left, Width = 198 };
        _rotate.Click += (_, _) => RotateToken();
        _rotateNote = new Label
        {
            Font = Fonts.Caption,
            Dock = DockStyle.Fill,
            AutoSize = false,
            Text = "Locks out every paired phone until it scans the new code.",
        };

        _settingsColumn = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1 };
        var settingsColumn = _settingsColumn;
        settingsColumn.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        // Heights include AddRow's 6px bottom gap.
        AddRow(settingsColumn, _statusCard, StatusCardHeight());
        _statusRow = settingsColumn.RowStyles.Count - 1;
        AddRow(settingsColumn, _settingsHeading, 32);
        AddRow(settingsColumn, portRow, 36);
        AddRow(settingsColumn, _portNote, 44); // two caption lines
        AddRow(settingsColumn, _shareLabel, 26);
        AddRow(settingsColumn, shareRow, 40);
        AddRow(settingsColumn, _castLabel, 26);
        AddRow(settingsColumn, castRow, 40);
        AddRow(settingsColumn, aiRow, 40);
        AddRow(settingsColumn, _aiDetail, 44); // three caption lines at this column width
        AddRow(settingsColumn, _startWithWindows, 34);
        AddRow(settingsColumn, _rotate, 40);
        AddRow(settingsColumn, _rotateNote, 28);
        // Soaks up whatever is left so the rows above stay top-aligned at any height.
        settingsColumn.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var body = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1 };
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 48));
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 52));
        body.Controls.Add(leftColumn, 0, 0);
        body.Controls.Add(settingsColumn, 1, 0);

        Controls.Add(body);
        Controls.Add(_footer);
        Controls.Add(_header);

        EnableFileDrop(_thread);
        EnableFileDrop(_threadEmpty);
        foreach (var entry in _share.History) Append(entry);

        _fadeTimer.Tick += (_, _) => TickFade();
        _rejectedRevertTimer.Tick += (_, _) =>
        {
            _rejectedRevertTimer.Stop();
            _rejectedPeer = null;
            _statusCard.Invalidate();
        };
        _connectionState.Changed += OnConnectionChanged;
        _connectionState.AuthRejected += OnAuthRejected;
        _devices.Changed += OnDevicesChanged;
        _share.Added += OnShareAdded;
        _ai.Changed += OnAiChanged;

        // Nothing has ever paired: the QR is the only useful thing this window can
        // show, so it is the half that's up.
        ShowPairing(!config.Paired);
        Refresh(config);
    }

    // Fully qualified: the PortalRemote.Control namespace (ConnectionState lives
    // there) shadows System.Windows.Forms.Control by simple name in this file.
    /// <summary>
    /// [height] is in logical (96dpi) units and is scaled here.
    ///
    /// WinForms scales a control's own bounds under <c>AutoScaleMode.Font</c>, but
    /// <see cref="SizeType.Absolute"/> row and column styles are left exactly as
    /// written — so at 150% every font in this window grew and every row height below
    /// did not, and the two-line captions (the port note, the rotate note, the
    /// same-Wi-Fi hint) clipped. The csproj asks for <c>PerMonitorV2</c>, so this is
    /// real scaling rather than the bitmap stretch a DPI-unaware app would get.
    ///
    /// ponytail: scaled once at construction from the DPI the process started on.
    /// Dragging the window to a monitor at a different scale won't re-lay it out —
    /// handle <c>OnDpiChangedAfterParent</c> if that ever matters.
    /// </summary>
    private void AddRow(TableLayoutPanel table, System.Windows.Forms.Control control, int height)
    {
        // One 6px gap under every row instead of per-control margins, so the row
        // heights above are the only numbers that decide the vertical rhythm.
        control.Margin = new Padding(0, 0, 0, LogicalToDeviceUnits(6));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, LogicalToDeviceUnits(height)));
        table.Controls.Add(control, 0, table.RowStyles.Count - 1);
    }

    /// <summary>The two-button trailing columns in the composer, shared-folder and
    /// cast rows. Absolute column styles need the same scaling as the rows above, or
    /// the buttons keep their 96dpi width while their labels grow past it.</summary>
    private TableLayoutPanel TrailingButtonRow()
    {
        var row = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(72)));
        return row;
    }

    /// <summary>Which half of the left column is up. Mutually exclusive rather than
    /// stacked: both want the whole column, and a QR code beside a composer is two
    /// primary actions on one surface.</summary>
    private void ShowPairing(bool pairing)
    {
        _pairPanel.Visible = pairing;
        _sharePanel.Visible = !pairing;
    }

    /// <summary>Re-render for the current address/token/settings, and re-apply the
    /// palette in case the Windows light/dark setting changed since this window was
    /// last shown.</summary>
    public void Refresh(ServerConfig config)
    {
        ApplyTheme();

        var png = PairingService.QrPng(PairingService.PairUrl(config), pixelsPerModule: 10);
        _qr.Image?.Dispose();
        using var stream = new MemoryStream(png);
        _qr.Image = Image.FromStream(stream);

        _address.Text = PairingService.HttpBase(config);
        _shareBox.Text = config.ResolvedShareRoot();
        // Carries the token, so it has to be re-read after a rotation like the QR does.
        _castBox.Text = PairingService.ReceiverUrl(config);
        _statusCard.Invalidate();
        ApplyComposerState();

        // Passive: honours AiHealth's backoff, so opening this window can never turn
        // into a probe of a dead port every few seconds. Check is the button that
        // skips it.
        _ = ProbeAiAsync(userAsked: false);
    }

    private void ApplyTheme()
    {
        _colors = SystemTheme.Colors;

        // The window is the canvas (`bg`), the cards on it are `surface`/`surface-raised`.
        // It used to paint itself `surface` — the same token as the cards it holds — so
        // in light mode the QR card and the status card were white panels on a white
        // window, separated by a hairline and nothing else.
        BackColor = _colors.Bg;
        _header.BackColor = _colors.Bg;
        _header.Invalidate();

        foreach (var label in new[]
                 { _shareHeading, _pairHeading, _settingsHeading, _address, _portLabel, _shareLabel, _castLabel, _aiLabel })
        {
            label.BackColor = _colors.Bg;
            label.ForeColor = _colors.TextPrimary;
        }

        foreach (var caption in new[]
                 { _hint, _portNote, _rotateNote, _footer, _threadEmpty, _composerHint, _aiDetail })
        {
            caption.BackColor = _colors.Bg;
            caption.ForeColor = _colors.TextSecondary;
        }

        _startWithWindows.BackColor = _colors.Bg;
        _startWithWindows.ForeColor = _colors.TextPrimary;

        // Input faces, not card faces: `surface-muted` is the token that reads as a
        // field on both themes (`surface-raised` was white-on-white here too).
        _port.BackColor = _colors.SurfaceMuted;
        _port.ForeColor = _colors.TextPrimary;
        _composer.BackColor = _colors.SurfaceMuted;
        _composer.ForeColor = _colors.TextPrimary;
        _shareBox.BackColor = _colors.SurfaceMuted;
        _shareBox.ForeColor = _colors.TextSecondary;
        _castBox.BackColor = _colors.SurfaceMuted;
        _castBox.ForeColor = _colors.TextSecondary;

        _qr.BackColor = _colors.SurfaceRaised;
        _qrCard.BackColor = _colors.SurfaceRaised;
        _qrCard.Invalidate();
        _statusCard.BackColor = _colors.Bg;
        _statusCard.Invalidate();
        _thread.BackColor = _colors.Bg;
        _aiState.BackColor = _colors.Bg;

        foreach (var button in new[]
                 { _copy, _send, _attach, _showPair, _showThread, _changeShare, _openShare, _copyCast, _openCast, _aiOpen, _aiCheck, _rotate })
            button.ApplyTheme(_colors);

        foreach (var bubble in _thread.Controls.OfType<Bubble>()) bubble.ApplyTheme(_colors);

        RefreshAi();

        if (IsHandleCreated) ApplyTitleBarTheme();
    }

    /// <summary>Show with a 180ms ease-out fade-in rather than popping in — see
    /// docs/design-system.md §6.</summary>
    public void ShowAnimated()
    {
        if (Visible)
        {
            // Already up: don't re-run the fade, it would read as a flicker.
            BringToFront();
            return;
        }

        Opacity = 0;
        Show();
        _fadeStart = DateTime.UtcNow;
        _fadeTimer.Start();
    }

    private void TickFade()
    {
        var elapsed = (DateTime.UtcNow - _fadeStart).TotalMilliseconds;
        var t = Math.Clamp(elapsed / FadeInMs, 0, 1);
        // Quadratic ease-out: fast start, settles into place.
        Opacity = 1 - Math.Pow(1 - t, 2);

        if (t >= 1) _fadeTimer.Stop();
    }

    private void OnConnectionChanged()
    {
        // Raised from a Kestrel worker thread; hop back before touching the UI.
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(() =>
        {
            _statusCard.Invalidate();
            ApplyComposerState();
        }));
    }

    /// <summary>A phone was turned away. Shown in the status card rather than as a
    /// dialog: nothing is required of the user, and §1 puts this window's budget at
    /// calm. Held for the same few seconds the tray icon holds its error state, then
    /// the card goes back to whatever is actually true.</summary>
    private void OnAuthRejected(string peer)
    {
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(() =>
        {
            _rejectedPeer = peer;
            _rejectedRevertTimer.Stop();
            _rejectedRevertTimer.Start();
            _statusCard.Invalidate();
        }));
    }

    // ---- the thread ------------------------------------------------------------

    private void OnShareAdded(ShareEntry entry)
    {
        // Raised on whichever thread did the sharing — a Kestrel one for anything the
        // phone sent.
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(() => Append(entry)));
    }

    private void Append(ShareEntry entry)
    {
        // The same bubble the assistant window draws its turns with — a share and a
        // chat turn are the same object on screen, so they are the same control.
        var bubble = new Bubble(entry.Incoming) { Cursor = Cursors.Hand };
        bubble.ApplyTheme(_colors);
        bubble.Click += (_, _) => OpenEntry(entry);
        EnableFileDrop(bubble);

        _thread.Controls.Add(bubble);
        // Docked children claim their edge in reverse index order, so index 0 is laid
        // out last and lands lowest — where the newest message belongs.
        _thread.Controls.SetChildIndex(bubble, 0);
        bubble.SetText(ShareBody(entry.Item), $"{entry.Item.From} · {entry.Item.At:HH:mm}");

        while (_thread.Controls.Count > MaxBubbles)
        {
            var oldest = _thread.Controls[_thread.Controls.Count - 1];
            _thread.Controls.Remove(oldest);
            oldest.Dispose();
        }

        _threadEmpty.Visible = false;
        _thread.ScrollControlIntoView(bubble);
    }

    /// <summary>What a share bubble says. A file's payload is on disk, not in the
    /// item, so its name and size are the message.</summary>
    private static string ShareBody(ShareItem item) => item.Kind switch
    {
        ShareKind.Text or ShareKind.Link => item.Text ?? string.Empty,
        _ => $"{item.FileName}  ({FileSize(item.Size)})",
    };

    private static string FileSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#} KB",
        _ => $"{bytes / (1024.0 * 1024.0):0.#} MB",
    };

    /// <summary>
    /// Click a bubble to get the thing itself: a link opens, a note goes back on the
    /// clipboard, a file is *revealed* in Explorer and never launched — a paired
    /// phone can put any file in the Inbox, and one click in this window should not
    /// be able to run it.
    /// </summary>
    private void OpenEntry(ShareEntry entry)
    {
        var item = entry.Item;

        if (item.Kind == ShareKind.Link &&
            Uri.TryCreate(item.Text?.Trim(), UriKind.Absolute, out var url) &&
            (url.Scheme == Uri.UriSchemeHttp || url.Scheme == Uri.UriSchemeHttps))
        {
            Process.Start(new ProcessStartInfo { FileName = url.AbsoluteUri, UseShellExecute = true });
            return;
        }

        if (item.Text is not null)
        {
            CopyText(item.Text);
            return;
        }

        if (item.FileName is null) return;
        var path = Path.Combine(_share.InboxPath(), FilePaths.SafeFileName(item.FileName));
        Process.Start(new ProcessStartInfo
        {
            FileName = "explorer.exe",
            Arguments = $"/select,\"{path}\"",
            UseShellExecute = true
        });
    }

    /// <summary>Drop a file anywhere on the thread to send it — bubbles included,
    /// since they cover the panel and would otherwise be dead zones.</summary>
    private void EnableFileDrop(System.Windows.Forms.Control target)
    {
        target.AllowDrop = true;
        target.DragEnter += (_, e) =>
            e.Effect = _share.HasClients && e.Data?.GetDataPresent(DataFormats.FileDrop) == true
                ? DragDropEffects.Copy
                : DragDropEffects.None;
        target.DragDrop += (_, e) =>
        {
            if (e.Data?.GetData(DataFormats.FileDrop) is not string[] paths) return;
            foreach (var path in paths) _ = SendFileAsync(path);
        };
    }

    private void SendComposerText()
    {
        var text = _composer.Text.Trim();
        if (text.Length == 0 || !_share.HasClients) return;

        _composer.Clear();
        _ = _share.SendToPhonesAsync(new ShareItem(
            ShareKind.ForText(text), text, null, text.Length, Environment.MachineName, DateTimeOffset.Now));
    }

    private void PickAndSendFile()
    {
        using var picker = new OpenFileDialog { Title = "Send a file to your phone", Multiselect = true };
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        foreach (var path in picker.FileNames) _ = SendFileAsync(path);
    }

    /// <summary>
    /// Copy into the Inbox, then tell the phone where to fetch it — an incoming file's
    /// route in reverse, so the phone needs no second download path. Async because the
    /// file is whatever the user picked: a video would freeze this window for the
    /// length of the copy.
    /// </summary>
    private async Task SendFileAsync(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists) return; // a dropped folder, most likely

            string saved;
            await using (var stream = info.OpenRead())
            {
                saved = await _share.SaveToInboxAsync(info.Name, stream);
            }

            await _share.SendToPhonesAsync(new ShareItem(
                ShareKind.ForFile(saved), null, saved, info.Length, Environment.MachineName, DateTimeOffset.Now));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            TokenDialog.Show(this, "That file could not be sent", ex.Message);
        }
    }

    /// <summary>A composer that cannot deliver says so before the button is pressed,
    /// rather than swallowing the message and looking like it worked.</summary>
    private void ApplyComposerState()
    {
        var connected = _share.HasClients;
        _send.Enabled = connected;
        _attach.Enabled = connected;
        _composerHint.Text = connected
            ? "Enter sends. Drop a file on the thread, or press Ctrl+Alt+V anywhere to send the clipboard."
            : "No phone connected — open Portal Remote on your phone to send anything.";
    }

    // ---- the assistant ---------------------------------------------------------

    private void OnAiChanged(object _)
    {
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(RefreshAi));
    }

    private void RefreshAi()
    {
        var (state, color) = _ai.State switch
        {
            AiHealth.Ready => ("Ready", _colors.Success),
            AiHealth.Unconfigured => ("Not set up", _colors.TextSecondary),
            _ => ("Not running", _colors.Warning),
        };

        _aiState.Text = state;
        _aiState.ForeColor = color;
        // Ready carries no detail, and the useful thing to say then is which model the
        // phone's Assistant tab is about to be talking to.
        _aiDetail.Text = _ai.Detail
            ?? $"Answering as {_config.AgentPlatform.Model}. One conversation, shared by this PC and the phone.";
    }

    private void CheckAi() => _ = ProbeAiAsync(userAsked: true);

    private async Task ProbeAiAsync(bool userAsked)
    {
        _aiCheck.Enabled = false;
        try
        {
            await _ai.CheckAsync(userAsked);
        }
        finally
        {
            if (!IsDisposed)
            {
                _aiCheck.Enabled = true;
                // CheckAsync only raises Changed on a transition, so a probe that
                // confirmed what we already knew still has to repaint the row.
                RefreshAi();
            }
        }
    }

    /// <summary>Mark + wordmark, left-aligned, with the version trailing it — see
    /// docs/design-system.md §11. Measured rather than positioned by hand so it
    /// survives the desktop font resolving differently on Win10 vs Win11 (§4).</summary>
    private void DrawHeader(Graphics g, Rectangle bounds)
    {
        const int markSize = 32;
        const int gap = 12;

        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
        var nameSize = g.MeasureString(ServerInfo.Name, Fonts.Heading);
        var top = bounds.Y + (bounds.Height - markSize) / 2f;

        BrandMark.Draw(g, new RectangleF(bounds.X, top, markSize, markSize), _colors.Accent);

        using var primary = new SolidBrush(_colors.TextPrimary);
        var textLeft = bounds.X + markSize + gap;
        g.DrawString(ServerInfo.Name, Fonts.Heading, primary, textLeft,
            bounds.Y + (bounds.Height - nameSize.Height) / 2f);

        using var secondary = new SolidBrush(_colors.TextSecondary);
        g.DrawString(ServerInfo.Version, Fonts.Caption, secondary,
            textLeft + nameSize.Width + 4,
            bounds.Y + (bounds.Height - nameSize.Height) / 2f + 3);
    }

    /// <summary>Sockets open and close on Kestrel threads; the row height below is a
    /// control property and has to be touched on the UI one.</summary>
    private void OnDevicesChanged()
    {
        if (IsDisposed || !IsHandleCreated) return;
        BeginInvoke(new Action(RefreshDevices));
    }

    /// <summary>One phone per row, so the card grows with the list. Capped so a house
    /// full of old pairings cannot push the settings below it off the window.</summary>
    private const int DeviceRowHeight = 52;

    private const int MaxDeviceRows = 4;

    /// <summary>Row height for the card, including AddRow's 6px bottom gap.</summary>
    private int StatusCardHeight() =>
        Math.Max(1, Math.Min(_devices.Snapshot().Count, MaxDeviceRows)) * DeviceRowHeight + 6;

    /// <summary>A phone appearing or leaving changes how tall this card has to be, and
    /// a repaint alone would just clip the new row.</summary>
    private void RefreshDevices()
    {
        if (IsDisposed || _settingsColumn.RowStyles.Count <= _statusRow) return;
        var height = LogicalToDeviceUnits(StatusCardHeight());
        if (Math.Abs(_settingsColumn.RowStyles[_statusRow].Height - height) > 0.5f)
        {
            _settingsColumn.RowStyles[_statusRow].Height = height;
        }
        _statusCard.Invalidate();
    }

    /// <summary>Connection state as the same dot + label pairing the Android top bar
    /// uses (docs/design-system.md §3/§7) — one status vocabulary across both apps.</summary>
    private void DrawStatus(Graphics g, Rectangle bounds)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        var card = new Rectangle(bounds.X, bounds.Y, bounds.Width - 1, bounds.Height - 1);
        using (var path = RoundedRect.Path(card, RoundedRect.RadiusCard))
        {
            using var fill = new SolidBrush(_colors.SurfaceRaised);
            using var pen = new Pen(_colors.Border);
            g.FillPath(fill, path);
            g.DrawPath(pen, path);
        }

        // A refusal outranks everything else for a few seconds. It is the direct
        // consequence of the button directly below this card, and until now the only
        // thing that reacted was a 16px tray icon the user is not looking at while
        // they're looking at this window.
        var rejected = _rejectedPeer;
        if (rejected is not null)
        {
            DrawDeviceRow(g, card, DeviceRowHeight, Glyphs.CellPhone, _colors.Danger,
                "A phone was refused", $"{rejected} — its pairing token is not valid here", _colors.Danger);
            return;
        }

        var devices = _devices.Snapshot();
        if (devices.Count == 0)
        {
            DrawDeviceRow(g, card, DeviceRowHeight, Glyphs.CellPhone, _colors.TextSecondary,
                _config.Paired ? "Phone not connected" : "Waiting for a phone",
                _config.Paired ? "Open Portal Remote on your phone to reconnect" : "Scan the code to pair one",
                _colors.TextPrimary);
            return;
        }

        // One row per phone this PC knows, connected ones first. A device that is
        // switched off keeps its row: "which phones are mine" and "which one is awake"
        // are different questions, and the second is useless without the first.
        var y = card.Y;
        foreach (var device in devices)
        {
            var row = new Rectangle(card.X, y, card.Width, DeviceRowHeight);
            DrawDeviceRow(g, row, DeviceRowHeight, Glyphs.CellPhone,
                device.Connected ? _colors.Success : _colors.TextSecondary,
                device.Name,
                device.Connected ? $"Connected · {device.Address}" : $"Last seen {LastSeen(device.LastSeen)}",
                _colors.TextPrimary);
            y += DeviceRowHeight;
        }
    }

    /// <summary>A phone glyph, a status dot on it, and the two lines of text. Shared by
    /// the device rows and by the two states that have no device to name.</summary>
    private void DrawDeviceRow(
        Graphics g, Rectangle row, int height, string glyph, Color dotColor,
        string headline, string detail, Color headlineColor)
    {
        var iconPx = 20;
        var iconY = row.Y + (height - iconPx) / 2;
        using (var phone = Glyphs.Render(glyph, iconPx, _colors.TextSecondary))
        {
            if (phone is not null) g.DrawImage(phone, row.X + 14, iconY, iconPx, iconPx);
        }

        // On the phone's bottom corner rather than out on its own: the dot is a property
        // of that device, and a column of free-floating dots reads as a list of states
        // with the devices as their labels, which is backwards.
        using (var dot = new SolidBrush(dotColor))
        using (var ring = new SolidBrush(_colors.SurfaceRaised))
        {
            g.FillEllipse(ring, row.X + 12 + iconPx - 8, iconY + iconPx - 8, 11, 11);
            g.FillEllipse(dot, row.X + 12 + iconPx - 6.5f, iconY + iconPx - 6.5f, 8, 8);
        }

        using var primary = new SolidBrush(headlineColor);
        using var secondary = new SolidBrush(_colors.TextSecondary);
        g.DrawString(headline, Fonts.Body, primary, row.X + 46, row.Y + 12);
        g.DrawString(detail, Fonts.Caption, secondary, row.X + 46, row.Y + 32);
    }

    /// <summary>Relative for anything recent, then the clock, then the date. "Last seen
    /// 13 Aug" is what the user wants from a phone that has been off for a week; the
    /// exact minute is only interesting for the last hour or so.</summary>
    private static string LastSeen(DateTimeOffset when)
    {
        var ago = DateTimeOffset.Now - when;
        if (ago < TimeSpan.FromMinutes(1)) return "just now";
        if (ago < TimeSpan.FromHours(1)) return $"{(int)ago.TotalMinutes} min ago";
        if (when.Date == DateTimeOffset.Now.Date) return when.ToString("HH:mm");
        if (when.Date == DateTimeOffset.Now.Date.AddDays(-1)) return $"yesterday {when:HH:mm}";
        return when.ToString("d MMM");
    }

    private void DrawCard(Graphics g, Rectangle bounds)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new Rectangle(bounds.X, bounds.Y, bounds.Width - 1, bounds.Height - 1);
        using var path = RoundedRect.Path(rect, RoundedRect.RadiusCard);
        using var pen = new Pen(_colors.Border);
        g.DrawPath(pen, path);
    }

    private void CopyAddress() => CopyText(PairingService.HttpBase(_config));

    private static void CopyText(string text)
    {
        try
        {
            Clipboard.SetText(text);
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // Another process holds the clipboard; not worth interrupting the user.
        }
    }

    private void SavePort()
    {
        var port = (int)_port.Value;
        if (port == _config.Port) return;
        _config.Port = port;
        _config.Save();
        // The QR keeps showing the port the server is actually listening on
        // (ServerConfig.RunningPort) — see the note under the field.
    }

    private void ChangeShareFolder()
    {
        using var picker = new FolderBrowserDialog
        {
            Description = "Folder the phone can browse, download from and upload to",
            SelectedPath = _config.ResolvedShareRoot(),
            UseDescriptionForTitle = true,
        };
        if (picker.ShowDialog(this) != DialogResult.OK) return;

        _config.ShareRoot = picker.SelectedPath;
        _config.Save();
        _shareBox.Text = _config.ResolvedShareRoot();
    }

    private void RotateToken()
    {
        var confirmed = TokenDialog.Show(this,
            "Rotate the pairing token?",
            "Every paired phone stops working immediately and has to scan the QR code "
            + "again. There is no way to undo this from the phone's side.",
            confirmText: "Rotate",
            cancelText: "Cancel",
            destructive: true);
        if (!confirmed) return;

        _config.RotateToken();
        Refresh(_config);
        // Nothing can reach this PC until a phone scans the new code, so put the code
        // in front of the user rather than leaving them on a thread that cannot send.
        ShowPairing(true);
    }

    private static void OpenPath(string path) =>
        Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });

    // Startup registration lives in the per-user Run key — no elevation, no service,
    // and it disappears with the user profile rather than outliving it.
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "PortalRemote";

    private static bool StartsWithWindows()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        return key?.GetValue(RunValueName) is string;
    }

    private static void SetStartWithWindows(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
        if (key is null) return;

        if (!enabled)
        {
            key.DeleteValue(RunValueName, throwOnMissingValue: false);
            return;
        }

        // Null when running from a host that isn't an executable file (single-file
        // publishing and `dotnet run` both give a real path, so this is defensive).
        if (Environment.ProcessPath is not { } exe) return;
        key.SetValue(RunValueName, $"\"{exe}\"");
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);

        // Windows 11 only; DwmSetWindowAttribute no-ops (returns an error HRESULT we
        // ignore) on Windows 10 rather than needing an OS-version branch.
        const int dwmwaWindowCornerPreference = 33;
        const int dwmwcRound = 2;
        var cornerPreference = dwmwcRound;
        NativeMethods.DwmSetWindowAttribute(Handle, dwmwaWindowCornerPreference, ref cornerPreference, sizeof(int));

        ApplyTitleBarTheme();
    }

    /// <summary>Matches the title bar to the current palette so it doesn't flash
    /// light chrome around a dark client area (or vice versa).</summary>
    private void ApplyTitleBarTheme()
    {
        const int dwmwaUseImmersiveDarkMode = 20;
        var useDarkMode = SystemTheme.IsDark() ? 1 : 0;
        NativeMethods.DwmSetWindowAttribute(Handle, dwmwaUseImmersiveDarkMode, ref useDarkMode, sizeof(int));
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // The tray owns this window's lifetime: hide instead of destroying it.
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
            _connectionState.Changed -= OnConnectionChanged;
            _devices.Changed -= OnDevicesChanged;
            _connectionState.AuthRejected -= OnAuthRejected;
            _share.Added -= OnShareAdded;
            _ai.Changed -= OnAiChanged;
            _fadeTimer.Dispose();
            _rejectedRevertTimer.Dispose();
            _qr.Image?.Dispose();
            Icon?.Dispose(); // Form doesn't own icons handed to it.
        }
        base.Dispose(disposing);
    }
}
