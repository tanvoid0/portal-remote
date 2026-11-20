using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using PortalRemote.Ai;
using PortalRemote.Config;
using PortalRemote.Devices;
using PortalRemote.Control;
using PortalRemote.Files;
using PortalRemote.Metrics;
using PortalRemote.Pairing;
using PortalRemote.Share;
using PortalRemote.Theme;
using WinControl = System.Windows.Forms.Control;

namespace PortalRemote.Tray;

/// <summary>
/// The desktop app's one window. Two columns — the conversation with the phone on the
/// left, everything about this PC on the right — so the whole app is legible without
/// hunting, per docs/design-system.md §1 ("calm and clarity, not motion") and §12.
///
/// The left column is the share thread once anything has ever paired, and the pairing QR
/// while nothing has: a paired PC whose main view is still "scan to pair" is answering a
/// question the user already answered. Either is one tab from the other, and from the
/// third: the phone's resource dashboard, drawn here for the PC it is about
/// (<see cref="StatsView"/>).
///
/// <b>The right column is a settings list, not a stack of cards.</b> One bracketed panel
/// at the top for the thing that is actually live — which phones this PC knows and what
/// they are doing — and under it three ruled sections for configuration, which is the
/// shape the phone's own Settings screen uses. The version this replaced put every one of
/// those thirteen rows at the same weight under a single "Settings" heading, so "rotate
/// the pairing token" (destructive, annual) read exactly as loudly as "open the shared
/// folder" (harmless, daily).
///
/// <b>Nothing here is laid out by a hardcoded row height.</b> <see cref="HudPanel"/>
/// measures its own rows and sizes itself to them, and the column scrolls. The previous
/// version was a <see cref="TableLayoutPanel"/> of absolute heights that summed past the
/// window's fixed client height as soon as two phones were listed, which silently cut the
/// last two controls off the bottom.
/// </summary>
public sealed class MainForm : Form
{
    private const int FadeInMs = 180;
    private const int FadeInTickMs = 15;

    /// <summary>The phone's <c>hudGrid</c> cell, in logical units. The ruled ground is
    /// texture and never content; it is what stops the canvas reading as an empty
    /// dialog.</summary>
    private const int GridCell = 28;

    /// <summary>Bubbles kept on screen. The hub only remembers 50, but this window can
    /// outlive several of those over a long session.</summary>
    private const int MaxBubbles = 60;

    private readonly ServerConfig _config;
    private readonly ConnectionState _connectionState;
    private readonly ShareHub _share;
    private readonly AiHealth _ai;
    private readonly DeviceRegistry _devices;

    private readonly Panel _header;
    private readonly Panel _statusCard;
    private readonly Panel _rightColumn;
    private readonly Panel _rightStack;
    private readonly ToolTip _tips = new();

    // ---- left column, front: the share thread --------------------------------
    private readonly Panel _sharePanel;
    private readonly Panel _thread;
    private readonly Label _threadEmpty;
    private readonly TextBox _composer;
    private readonly Field _composerField;
    private readonly TokenButton _send;
    private readonly TokenButton _attach;
    private readonly Label _composerHint;

    // ---- left column, the tab strip ------------------------------------------
    private readonly TokenButton _showThread;
    private readonly TokenButton _showPair;
    private readonly TokenButton _showStats;

    /// <summary>Which of the left column's three views is up.</summary>
    private enum View { Thread, Pair, Stats }

    // ---- left column, behind: pairing ----------------------------------------
    private readonly Panel _pairPanel;
    private readonly PictureBox _qr;
    private readonly Panel _qrCard;
    private readonly Label _address;
    private readonly Label _hint;
    private readonly TokenButton _copy;

    // ---- left column, behind: the dashboard ----------------------------------
    private readonly StatsView _statsView;

    // ---- right column --------------------------------------------------------
    private readonly HudPanel _devicePanel;
    private readonly HudPanel _sharingSection;
    private readonly HudPanel _assistantSection;
    private readonly HudPanel _advancedSection;
    private readonly TextBox _port;
    private readonly Field _portField;
    private readonly Label _portLabel;
    private readonly Label _portNote;
    private readonly Label _shareLabel;
    private readonly TextBox _shareValue;
    private readonly Field _shareField;
    private readonly TokenButton _changeShare;
    private readonly TokenButton _openShare;
    private readonly Label _castLabel;
    private readonly TextBox _castValue;
    private readonly Field _castField;
    private readonly TokenButton _copyCast;
    private readonly TokenButton _openCast;
    private readonly Label _aiLabel;
    private readonly Label _aiState;
    private readonly Label _aiDetail;
    private readonly TokenButton _aiOpen;
    private readonly TokenButton _aiCheck;
    private readonly TokenButton _aiGet;
    private readonly TableLayoutPanel _aiRow;
    private readonly CheckBox _startWithWindows;
    private readonly TokenButton _rotate;
    private readonly Label _rotateNote;
    private readonly Label _footer;

    private readonly System.Windows.Forms.Timer _fadeTimer = new() { Interval = FadeInTickMs };
    // Same 3s the tray icon holds its error state for, so the two surfaces don't disagree
    // about whether the refusal is still news.
    private readonly System.Windows.Forms.Timer _rejectedRevertTimer = new() { Interval = 3000 };
    private string? _rejectedPeer;
    private DateTime _fadeStart;
    private PaletteColors _colors = SystemTheme.Colors;

    /// <summary>Opens the assistant window. Set by the tray, which owns every window's
    /// lifetime — this one only has to know that the button leads somewhere.</summary>
    public Action? OpenAssistant { get; init; }

    public MainForm(ServerConfig config, ConnectionState connectionState, ShareHub share, AiHealth ai,
        DeviceRegistry devices, SystemStats stats)
    {
        _config = config;
        _connectionState = connectionState;
        _share = share;
        _ai = ai;
        _devices = devices;

        Text = ServerInfo.Name;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(920, 660);
        // Resizable, with a floor rather than a lock. The previous build pinned
        // MinimumSize to ClientSize, so a window whose content had overflowed could not
        // be dragged bigger to see the rest of it.
        MinimumSize = new Size(760, 560);
        Padding = new Padding(20);
        DoubleBuffered = true;

        // Taskbar/Alt-Tab entry. Fixed to the light accent in both themes: this is the
        // app's identity in the taskbar (same tile as app.ico and the Android launcher
        // icon), not a themed surface like the rest of this window.
        Icon = BrandMark.CreateIcon(32, Color.White, badge: Palette.Light.Accent);

        _header = new Panel { Dock = DockStyle.Top, Height = 56 };
        _header.Paint += (_, e) => DrawHeader(e.Graphics, _header.ClientRectangle);

        _footer = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 24,
            Font = Fonts.Mono,
            TextAlign = ContentAlignment.MiddleLeft,
            Text = ServerConfig.DefaultConfigPath,
            Cursor = Cursors.Hand,
            AutoEllipsis = true,
            AutoSize = false,
        };
        _footer.Click += (_, _) => OpenPath(ServerConfig.ConfigDirectory);
        _tips.SetToolTip(_footer, "Open the folder this PC's settings are stored in");

        // ---- left column: the tab strip -------------------------------------------
        // Two buttons that are a segmented control, not two actions: the filled one is
        // the view you are on. They used to live one inside each panel, so you only ever
        // saw the one that switched away — a lone "Pair a phone" button above a thread,
        // which reads as something that starts a pairing rather than as a tab.
        _showThread = new TokenButton { Text = "Messages", Glyph = Glyphs.Conversation, Width = 132 };
        _showThread.Click += (_, _) => Show(View.Thread);
        _showPair = new TokenButton { Text = "Pair", Glyph = Glyphs.QrCode, Secondary = true, Width = 108 };
        _showPair.Click += (_, _) => Show(View.Pair);
        _showStats = new TokenButton { Text = "Stats", Glyph = Glyphs.Stats, Secondary = true, Width = 108 };
        _showStats.Click += (_, _) => Show(View.Stats);

        var tabs = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = LogicalToDeviceUnits(42),
            Padding = new Padding(0, 0, 0, LogicalToDeviceUnits(8)),
            WrapContents = false,
        };
        _showThread.Margin = new Padding(0, 0, LogicalToDeviceUnits(6), 0);
        _showPair.Margin = new Padding(0, 0, LogicalToDeviceUnits(6), 0);
        _showStats.Margin = new Padding(0);
        _showThread.Height = LogicalToDeviceUnits(34);
        _showPair.Height = LogicalToDeviceUnits(34);
        _showStats.Height = LogicalToDeviceUnits(34);
        tabs.Controls.Add(_showThread);
        tabs.Controls.Add(_showPair);
        tabs.Controls.Add(_showStats);

        // ---- left column, front: the thread ---------------------------------------
        _thread = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
        // The empty state is a child of the thread, not its sibling. As siblings both
        // wanted Dock.Fill, and the thread — added first, so topmost — simply covered it:
        // the "nothing shared yet" copy could never be seen.
        _thread.Paint += (_, e) => Shapes.DrawGridAligned(
            e.Graphics, _thread, this, _colors.Grid, LogicalToDeviceUnits(GridCell));

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
            Font = Fonts.Body,
            // Borderless inside a Field, which draws the chamfered well instead — a
            // TextBox will not chamfer itself.
            BorderStyle = BorderStyle.None,
        };
        // Enter sends. A text box whose Enter does nothing, next to a Send button, is the
        // one thing every chat surface has trained people out of expecting.
        _composer.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter || e.Shift) return;
            e.SuppressKeyPress = true; // otherwise the box beeps at the unhandled Enter
            SendComposerText();
        };
        _composerField = new Field(_composer) { Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0) };

        _attach = new TokenButton
        {
            Text = "Attach", Glyph = Glyphs.Attach, Secondary = true, Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _attach.Click += (_, _) => PickAndSendFile();
        _send = new TokenButton { Text = "Send", Glyph = Glyphs.Send, Dock = DockStyle.Fill };
        _send.Click += (_, _) => SendComposerText();

        _composerHint = new Label { Font = Fonts.Caption, Dock = DockStyle.Bottom, Height = 34, AutoSize = false };

        var composerRow = TrailingButtonRow();
        composerRow.Dock = DockStyle.Bottom;
        composerRow.Height = LogicalToDeviceUnits(36);
        composerRow.Controls.Add(_composerField, 0, 0);
        composerRow.Controls.Add(_attach, 1, 0);
        composerRow.Controls.Add(_send, 2, 0);
        _composerField.Dock = DockStyle.Fill;

        _sharePanel = new Panel { Dock = DockStyle.Fill };
        // Docking is resolved in reverse order of addition — the control added last
        // claims its edge first — so the two Fills go in first and the bottom stack is
        // added upwards-last: the composer, then the hint that sits under it.
        _thread.Controls.Add(_threadEmpty);
        _sharePanel.Controls.Add(_thread);
        _sharePanel.Controls.Add(composerRow);
        _sharePanel.Controls.Add(_composerHint);

        // ---- left column, behind: pairing -----------------------------------------
        _qr = new PictureBox { SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Fill };
        _qrCard = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16) };
        _qrCard.Paint += (_, e) => DrawQrCard(e.Graphics, _qrCard.ClientRectangle);
        _qrCard.Controls.Add(_qr);

        // Mono is for the address itself and nothing else — prose set in it reads as
        // something to be typed literally.
        _address = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 28,
            Font = Fonts.Readout,
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

        _copy = new TokenButton { Text = "Copy address", Glyph = Glyphs.Copy, Dock = DockStyle.Bottom, Height = 36 };
        _copy.Click += (_, _) => CopyAddress();

        _pairPanel = new Panel { Dock = DockStyle.Fill, Visible = false };
        _pairPanel.Controls.Add(_qrCard);
        _pairPanel.Controls.Add(_address);
        _pairPanel.Controls.Add(_hint);
        _pairPanel.Controls.Add(_copy);

        // ---- left column, behind: the dashboard -----------------------------------
        // Hidden until its tab is picked, and it samples the machine only while shown —
        // see StatsView.
        _statsView = new StatsView(stats) { Dock = DockStyle.Fill, Visible = false };

        var leftColumn = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 0, 20, 0) };
        leftColumn.Controls.Add(_sharePanel);
        leftColumn.Controls.Add(_pairPanel);
        leftColumn.Controls.Add(_statsView);
        leftColumn.Controls.Add(tabs);

        // ---- right column, the live panel -----------------------------------------
        _statusCard = new Panel();
        _statusCard.Paint += (_, e) => DrawStatus(e.Graphics, _statusCard.ClientRectangle);
        _devicePanel = new HudPanel();
        _devicePanel.Add(_statusCard);

        // ---- right column, section: sharing ---------------------------------------
        _shareLabel = new Label { Text = "Shared folder", Font = Fonts.Body, AutoSize = false };
        // Read-only rather than a Label: a Label wraps, and a path wrapped across two
        // lines inside a one-line well is worse than one that scrolls. This also stays
        // selectable, which is the whole point of showing an address.
        _shareValue = new TextBox
        {
            Font = Fonts.Mono, ReadOnly = true, BorderStyle = BorderStyle.None, TabStop = false,
        };
        _shareField = new Field(_shareValue, readOnly: true)
        {
            Dock = DockStyle.Fill, Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _changeShare = new TokenButton
        {
            Text = "Change", Glyph = Glyphs.Edit, Secondary = true, Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _changeShare.Click += (_, _) => ChangeShareFolder();
        _openShare = new TokenButton { Text = "Open", Glyph = Glyphs.FolderOpen, Secondary = true, Dock = DockStyle.Fill };
        _openShare.Click += (_, _) => OpenPath(_config.ResolvedShareRoot());

        var shareRow = TrailingButtonRow();
        shareRow.Controls.Add(_shareField, 0, 0);
        shareRow.Controls.Add(_changeShare, 1, 0);
        shareRow.Controls.Add(_openShare, 2, 0);

        // The receiver URL used to exist only in the startup banner, which is only on
        // screen if the server was started from a console. Typed into a TV's browser it
        // makes that TV a cast target, so it has to be findable from the app.
        _castLabel = new Label { Text = "Cast to a screen", Font = Fonts.Body, AutoSize = false };
        _castValue = new TextBox
        {
            Font = Fonts.Mono, ReadOnly = true, BorderStyle = BorderStyle.None, TabStop = false,
        };
        _castField = new Field(_castValue, readOnly: true)
        {
            Dock = DockStyle.Fill, Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _copyCast = new TokenButton
        {
            Text = "Copy", Glyph = Glyphs.Copy, Secondary = true, Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _copyCast.Click += (_, _) => CopyText(PairingService.ReceiverUrl(_config));
        // Opening it here makes *this* PC the cast target, which is the common case and
        // saves typing the address anywhere at all.
        _openCast = new TokenButton { Text = "Open", Glyph = Glyphs.OpenInNew, Secondary = true, Dock = DockStyle.Fill };
        _openCast.Click += (_, _) => OpenPath(PairingService.ReceiverUrl(_config));

        var castRow = TrailingButtonRow();
        castRow.Controls.Add(_castField, 0, 0);
        castRow.Controls.Add(_copyCast, 1, 0);
        castRow.Controls.Add(_openCast, 2, 0);

        _sharingSection = new HudPanel("Sharing", Glyphs.FolderOpen, section: true, rule: false);
        _sharingSection.Add(_shareLabel, 20);
        _sharingSection.Add(shareRow, 36);
        _sharingSection.Add(_castLabel, 20);
        _sharingSection.Add(castRow, 36);

        // ---- right column, section: assistant -------------------------------------
        // The assistant's backend is a separate app started on *this* PC, and until
        // recently this window said nothing about it: the phone was the only surface that
        // could tell you it wasn't running, which is the wrong machine to find that out
        // on. See docs/phase7-assistant.md §4.
        _aiLabel = new Label
        {
            Text = "Backend", Font = Fonts.Body, Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
        };
        _aiState = new Label { Font = Fonts.Readout, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        _aiCheck = new TokenButton { Text = "Check", Glyph = Glyphs.Refresh, Secondary = true, Dock = DockStyle.Fill };
        _aiCheck.Click += (_, _) => CheckAi();
        // The conversation is this PC's now, so it is reachable from this PC — the phone
        // is no longer the only place it exists. See Tray/AssistantForm.cs.
        _aiOpen = new TokenButton
        {
            Text = "Chat", Glyph = Glyphs.Assistant, Secondary = true, Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _aiOpen.Click += (_, _) => OpenAssistant?.Invoke();
        // The one dependency this app cannot ship: agent-platform is somebody else's
        // release on GitHub. Telling the user to go and find it is the step that never
        // happens, so this button is the whole install — download, unzip, start. It is
        // only on screen while the backend isn't answering. See Ai/AgentPlatformSetup.cs.
        _aiGet = new TokenButton
        {
            Text = "Set up", Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, LogicalToDeviceUnits(8), 0),
        };
        _aiGet.Click += (_, _) => _ = SetUpAiAsync();
        _aiDetail = new Label { Font = Fonts.Caption, AutoSize = false };

        _aiRow = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 5, RowCount = 1 };
        _aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(76)));
        _aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        _aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        _aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        _aiRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        _aiRow.Controls.Add(_aiLabel, 0, 0);
        _aiRow.Controls.Add(_aiState, 1, 0);
        _aiRow.Controls.Add(_aiGet, 2, 0);
        _aiRow.Controls.Add(_aiOpen, 3, 0);
        _aiRow.Controls.Add(_aiCheck, 4, 0);

        _assistantSection = new HudPanel("Assistant", Glyphs.Assistant, section: true);
        _assistantSection.Add(_aiRow, 36);
        _assistantSection.Add(_aiDetail);

        // ---- right column, section: advanced --------------------------------------
        // The two controls that can lock a phone out live at the bottom, under their own
        // heading, because they are annual and consequential and the three above them are
        // neither.
        _startWithWindows = new CheckBox
        {
            Text = "Start Portal Remote when I sign in",
            Font = Fonts.Body,
            FlatStyle = FlatStyle.Flat,
            Checked = StartupRegistration.IsEnabled(),
        };
        // Wired after Checked is set so reading the current state doesn't write it back.
        _startWithWindows.CheckedChanged += (_, _) => StartupRegistration.SetEnabled(_startWithWindows.Checked);

        _portLabel = new Label { Text = "Port", Font = Fonts.Body, AutoSize = false, Width = 60 };
        // A plain box, not a NumericUpDown. The spinner is drawn by the system and cannot
        // be given the palette — it stayed a white pair of arrows in the middle of a dark
        // well — and nobody arrives at a port number by clicking up 6,741 times.
        _port = new TextBox
        {
            Text = config.Port.ToString(),
            Font = Fonts.Mono,
            BorderStyle = BorderStyle.None,
            MaxLength = 5,
        };
        // On leaving the field, not on every keystroke. The NumericUpDown this replaced
        // saved on ValueChanged, which fires per typed character — so entering 8765 wrote
        // and saved port 8, clamped up to the 1024 minimum, before the second digit landed.
        _port.Leave += (_, _) => SavePort();

        _portField = new Field(_port) { Dock = DockStyle.Fill };
        // Three columns for two controls: a TableLayoutPanel hands the leftover width to
        // its last column, so with only the label and the field declared, a port number
        // got a 300px well. The trailing percent column is the one that absorbs it.
        var portRow = new TableLayoutPanel { ColumnCount = 3, RowCount = 1 };
        portRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(60)));
        portRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(110)));
        portRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        portRow.Controls.Add(_portLabel, 0, 0);
        portRow.Controls.Add(_portField, 1, 0);
        _portLabel.Dock = DockStyle.Fill;
        _portLabel.TextAlign = ContentAlignment.MiddleLeft;

        _portNote = new Label
        {
            Font = Fonts.Caption,
            // Label.AutoSize defaults to true, which keeps a docked label on one line and
            // clips the overflow instead of wrapping it.
            AutoSize = false,
            Text = "Applies the next time Portal Remote starts. Paired phones have to scan "
                 + "the new code afterwards.",
        };

        _rotate = new TokenButton
        {
            Text = "Rotate pairing token", Glyph = Glyphs.Lock, Secondary = true, Dock = DockStyle.Fill,
        };
        _rotate.Click += (_, _) => RotateToken();
        // Sized to its label and left-aligned rather than stretched across the column: a
        // panel row is full width, and a full-width button is a primary action. This one
        // locks every paired phone out.
        var rotateRow = new TableLayoutPanel { ColumnCount = 2, RowCount = 1 };
        rotateRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(230)));
        rotateRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        rotateRow.Controls.Add(_rotate, 0, 0);
        _rotateNote = new Label
        {
            Font = Fonts.Caption,
            AutoSize = false,
            Text = "Locks out every paired phone until it scans the new code.",
        };

        _advancedSection = new HudPanel("Advanced", Glyphs.Wrench, section: true);
        _advancedSection.Add(_startWithWindows, 26);
        _advancedSection.Add(portRow, 34);
        _advancedSection.Add(_portNote);
        _advancedSection.Add(rotateRow, 36);
        _advancedSection.Add(_rotateNote);

        // Docked top in reverse order of addition, so the visual order below is the
        // reverse of the reading order. Scrolls rather than clips: a phone list four rows
        // long plus three sections can exceed a short window, and the answer to that is a
        // scrollbar, not a truncated column.
        // Two panels, not one. A docked child of an AutoScroll panel sizes itself to the
        // *scrollable* width, which is the width it had before the vertical scrollbar
        // appeared — so the moment the column is long enough to scroll, every section is
        // one scrollbar too wide and the panel grows a horizontal scrollbar to cover for
        // it. The sections dock inside a stack whose width is pinned to the scroller's
        // ClientSize instead, and ClientSize is measured after the scrollbar is there.
        // Sized by hand rather than by AutoSize: AutoSize drives both dimensions, so it
        // kept overwriting the width pinned below with the width its docked children had
        // already taken, and the horizontal scrollbar came straight back.
        _rightStack = new Panel { Location = Point.Empty };
        _rightStack.Controls.Add(_advancedSection);
        _rightStack.Controls.Add(_assistantSection);
        _rightStack.Controls.Add(_sharingSection);
        _rightStack.Controls.Add(_devicePanel);

        _rightColumn = new Panel { Dock = DockStyle.Fill, AutoScroll = true };
        _rightColumn.Controls.Add(_rightStack);
        // Layout, not just SizeChanged: the vertical scrollbar appears *because* the stack
        // got tall, which happens after the last SizeChanged has already been handled — so
        // resizing alone never learns that ClientSize just lost a scrollbar's width. The
        // fit is a no-op once it agrees with the column, so re-entering here converges
        // rather than looping.
        _rightColumn.Layout += (_, _) => FitRightStack();
        _rightColumn.SizeChanged += (_, _) => FitRightStack();
        // A section that re-measured (a phone joined, the assistant's detail line changed
        // length) changes how tall the stack has to be.
        foreach (var panel in new[] { _devicePanel, _sharingSection, _assistantSection, _advancedSection })
            panel.SizeChanged += (_, _) => FitRightStack();

        var body = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1 };
        // The thread is the primary surface and now gets the wider half. It used to get
        // the narrower one, so the chat had ~380px and the settings form had more room
        // than the thing the window is for.
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 56));
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 44));
        body.Controls.Add(leftColumn, 0, 0);
        body.Controls.Add(_rightColumn, 1, 0);

        Controls.Add(body);
        Controls.Add(_footer);
        Controls.Add(_header);

        // The whole window takes a drop, not just the thread panel. A file dropped on the
        // right-hand column used to be silently refused.
        EnableFileDrop(this);
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

        // Nothing has ever paired: the QR is the only useful thing this window can show,
        // so it is the view that's up.
        Show(config.Paired ? View.Thread : View.Pair);
        RefreshDevices();
        Refresh(config);
    }

    /// <summary>Pin the settings stack to the scroller's client width — the width left
    /// over once the vertical scrollbar has taken its own, so the sections never overflow
    /// sideways.</summary>
    private void FitRightStack()
    {
        var width = _rightColumn.ClientSize.Width;
        if (width <= 0) return;

        var height = 0;
        foreach (WinControl child in _rightStack.Controls) height = Math.Max(height, child.Bottom);

        if (_rightStack.Width != width || _rightStack.Height != height)
            _rightStack.Size = new Size(width, height);
    }

    /// <summary>The two-button trailing columns in the composer, shared-folder and cast
    /// rows. Absolute column styles need the same scaling as everything else here, or the
    /// buttons keep their 96dpi width while their labels grow past it. One set of widths
    /// for all three rows, so the buttons line up down the column — they used to be
    /// 88/72, 80/72/72 and 132 and nothing aligned with anything.</summary>
    private TableLayoutPanel TrailingButtonRow()
    {
        var row = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(96)));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        return row;
    }

    /// <summary>Which view of the left column is up. Mutually exclusive rather than
    /// stacked: each wants the whole column, and a QR code beside a composer is two primary
    /// actions on one surface. The tab strip's fill follows, so the strip says which of
    /// the three you are looking at.</summary>
    private void Show(View view)
    {
        _sharePanel.Visible = view == View.Thread;
        _pairPanel.Visible = view == View.Pair;
        _statsView.Visible = view == View.Stats;
        _showThread.Secondary = view != View.Thread;
        _showPair.Secondary = view != View.Pair;
        _showStats.Secondary = view != View.Stats;
    }

    /// <summary>Re-render for the current address/token/settings, and re-apply the palette
    /// in case the Windows light/dark setting changed since this window was last
    /// shown.</summary>
    public void Refresh(ServerConfig config)
    {
        ApplyTheme();

        var png = PairingService.QrPng(PairingService.PairUrl(config), pixelsPerModule: 10);
        _qr.Image?.Dispose();
        using var stream = new MemoryStream(png);
        _qr.Image = Image.FromStream(stream);

        _address.Text = PairingService.HttpBase(config);
        _shareValue.Text = config.ResolvedShareRoot();
        _tips.SetToolTip(_shareValue, _shareValue.Text);
        // Carries the token, so it has to be re-read after a rotation like the QR does.
        _castValue.Text = PairingService.ReceiverUrl(config);
        _tips.SetToolTip(_castValue, _castValue.Text);
        _statusCard.Invalidate();
        ApplyComposerState();

        // Passive: honours AiHealth's backoff, so opening this window can never turn into
        // a probe of a dead port every few seconds. Check is the button that skips it.
        _ = ProbeAiAsync(userAsked: false);
    }

    private void ApplyTheme()
    {
        _colors = SystemTheme.Colors;

        // The window is the canvas (`bg`), the panels on it are `surface`. It used to
        // paint itself `surface` — the same token as the cards it held — so in light mode
        // the QR card and the status card were white panels on a white window, separated
        // by a hairline and nothing else.
        BackColor = _colors.Bg;
        _header.BackColor = _colors.Bg;
        _header.Invalidate();

        foreach (var label in new[] { _address, _portLabel, _shareLabel, _castLabel, _aiLabel })
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
        // A stock CheckBox draws its box with the *system* theme, which in dark mode is a
        // white square on a near-black panel. Flat + the palette's own colours is the one
        // way to keep it in the same world as everything round it.
        _startWithWindows.FlatAppearance.BorderColor = _colors.BorderStrong;
        _startWithWindows.FlatAppearance.CheckedBackColor = _colors.Accent;

        foreach (var field in new[] { _composerField, _shareField, _castField, _portField })
            field.ApplyTheme(_colors);

        // White, not `surface`: the card under it is painted white in both themes so the
        // code stays scannable, and a Zoom-mode PictureBox letterboxes — in dark mode the
        // bands above and below the code were two black stripes across a white card.
        _qr.BackColor = Color.White;
        _qrCard.BackColor = _colors.Bg;
        _qrCard.Invalidate();
        _statusCard.BackColor = _colors.Surface;
        _statusCard.Invalidate();
        _thread.BackColor = _colors.Bg;
        _aiState.BackColor = _colors.Bg;
        _statsView.ApplyTheme(_colors);

        foreach (var panel in new[] { _devicePanel, _sharingSection, _assistantSection, _advancedSection })
            panel.ApplyTheme(_colors);

        foreach (var button in new[]
                 { _copy, _send, _attach, _showPair, _showThread, _showStats, _changeShare, _openShare,
                   _copyCast, _openCast, _aiGet, _aiOpen, _aiCheck, _rotate })
            button.ApplyTheme(_colors);

        foreach (var bubble in _thread.Controls.OfType<Bubble>()) bubble.ApplyTheme(_colors);

        RefreshAi();
        Invalidate(true);

        if (IsHandleCreated) ApplyTitleBarTheme();
    }

    /// <summary>The ruled ground the panels rest on — the phone's <c>hudCanvas</c>. Drawn
    /// behind the whole window rather than per panel, so they read as objects on one
    /// surface instead of each carrying its own texture.</summary>
    protected override void OnPaintBackground(PaintEventArgs e)
    {
        base.OnPaintBackground(e);
        Shapes.DrawGrid(e.Graphics, ClientRectangle, _colors.Grid, LogicalToDeviceUnits(GridCell));
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

    /// <summary>A phone was turned away. Shown in the device panel rather than as a
    /// dialog: nothing is required of the user, and §1 puts this window's budget at calm.
    /// Held for the same few seconds the tray icon holds its error state, then the panel
    /// goes back to whatever is actually true.</summary>
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

        var ready = _ai.State == AiHealth.Ready;
        var installed = AgentPlatformSetup.InstalledExe(_config) is not null;
        // Nothing to set up once it is answering, and the column collapses with the
        // button so the row doesn't keep a hole where it was.
        _aiGet.Visible = !ready;
        _aiGet.Text = installed ? "Start" : "Set up";
        _aiRow.ColumnStyles[2].Width = ready ? 0 : LogicalToDeviceUnits(88);

        // Ready carries no detail, and the useful thing to say then is which model the
        // phone's Assistant tab is about to be talking to. Not ready means the backend
        // is missing, and the honest thing to say is what it is and that this window can
        // fetch it — AiHealth's own detail is a diagnostic, not an instruction.
        _aiDetail.Text = ready
            ? $"Answering as {_config.AgentPlatform.Model}. One conversation, shared by this PC and the phone."
            : installed
                ? $"{_ai.Detail} Start runs agent-platform from {AgentPlatformSetup.InstallDirectory}."
                : "The assistant needs agent-platform running on this PC. Set up downloads its "
                  + "latest release from GitHub and starts it — nothing else to install.";

        // The detail line is one, two or three caption lines depending on which of those
        // it is, and the section is sized from the measured text rather than from a
        // constant, so it has to be told the text moved.
        _assistantSection.Remeasure();
    }

    /// <summary>
    /// Install agent-platform if it isn't here, start it, and wait for it to answer.
    /// One button for the whole thing: the alternative is a link to a releases page and
    /// a paragraph about unzipping, which is where this feature used to stop.
    /// </summary>
    private async Task SetUpAiAsync()
    {
        _aiGet.Enabled = false;
        try
        {
            var exe = AgentPlatformSetup.InstalledExe(_config);
            if (exe is null)
            {
                _aiDetail.Text = "Downloading the latest agent-platform from GitHub…";
                exe = await AgentPlatformSetup.InstallAsync(_config);
            }

            _aiDetail.Text = "Starting agent-platform…";
            AgentPlatformSetup.Start(exe);
            await WaitForAiAsync();
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or InvalidOperationException
                                       or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            // Offer the manual path rather than only the failure: the repo page is where
            // somebody would have had to start anyway.
            if (TokenDialog.Show(this,
                    "Could not set up the assistant",
                    $"{ex.Message}\n\nYou can install agent-platform yourself from its GitHub page, "
                    + "then press Check.",
                    confirmText: "Open GitHub",
                    cancelText: "Close"))
                OpenPath(AgentPlatformSetup.RepoUrl);
        }
        finally
        {
            if (!IsDisposed)
            {
                _aiGet.Enabled = true;
                RefreshAi();
            }
        }
    }

    /// <summary>A freshly started daemon binds its port a moment after the process
    /// exists, so one probe would always report failure. Gives up after 20s and leaves
    /// the row saying what the last probe found.</summary>
    private async Task WaitForAiAsync()
    {
        for (var attempt = 0; attempt < 20 && !IsDisposed; attempt++)
        {
            await _ai.CheckAsync(userAsked: true);
            if (_ai.State == AiHealth.Ready) return;
            await Task.Delay(1000);
        }
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
        var markSize = LogicalToDeviceUnits(32);
        var gap = LogicalToDeviceUnits(12);

        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        var top = bounds.Y + (bounds.Height - markSize) / 2;
        BrandMark.Draw(g, new RectangleF(bounds.X, top, markSize, markSize), _colors.Accent);

        var nameSize = TextRenderer.MeasureText(ServerInfo.Name, Fonts.Title, Size.Empty, TextFormatFlags.NoPadding);
        var textLeft = bounds.X + markSize + gap;
        var nameTop = bounds.Y + (bounds.Height - nameSize.Height) / 2;
        TextRenderer.DrawText(g, ServerInfo.Name, Fonts.Title, new Point(textLeft, nameTop),
            _colors.TextPrimary, TextFormatFlags.NoPadding);

        // The version is a reading, not prose: tracked mono, sat on the wordmark's own
        // baseline rather than nudged down by a hand-tuned three pixels.
        var tracking = LogicalToDeviceUnits((int)HudText.LabelTracking);
        var versionSize = HudText.Measure(ServerInfo.Version, Fonts.Label, tracking);
        HudText.Draw(g, ServerInfo.Version, Fonts.Label,
            new Point(textLeft + nameSize.Width + gap, nameTop + nameSize.Height - versionSize.Height - 2),
            _colors.TextSecondary, tracking);
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
    private const int DeviceRowHeight = 54;

    private const int MaxDeviceRows = 4;

    /// <summary>How tall the rows inside the device panel come to. The panel adds its
    /// own padding round this.</summary>
    private int StatusCardHeight() =>
        LogicalToDeviceUnits(Math.Max(1, Math.Min(_devices.Snapshot().Count, MaxDeviceRows)) * DeviceRowHeight);

    /// <summary>A phone appearing or leaving changes how tall this card has to be, and
    /// a repaint alone would just clip the new row.</summary>
    private void RefreshDevices()
    {
        if (IsDisposed) return;
        var height = StatusCardHeight();
        if (_statusCard.Height != height)
        {
            _statusCard.Height = height;
            _devicePanel.Remeasure();
        }
        _statusCard.Invalidate();
    }

    /// <summary>Connection state as the same dot + label pairing the Android top bar
    /// uses (docs/design-system.md §3/§7) — one status vocabulary across both apps.</summary>
    private void DrawStatus(Graphics g, Rectangle bounds)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        // No chrome of its own any more: the HudPanel holding these rows draws the
        // chamfered face, the hairline and the corner brackets, so this draws only rows.
        var card = bounds;
        var rowHeight = LogicalToDeviceUnits(DeviceRowHeight);

        // A refusal outranks everything else for a few seconds. It is the direct
        // consequence of the button directly below this card, and until now the only
        // thing that reacted was a 16px tray icon the user is not looking at while
        // they're looking at this window.
        var rejected = _rejectedPeer;
        if (rejected is not null)
        {
            DrawDeviceRow(g, card, rowHeight, Glyphs.CellPhone, _colors.Danger,
                "A phone was refused", $"{rejected} — its pairing token is not valid here", _colors.Danger);
            return;
        }

        var devices = _devices.Snapshot();
        if (devices.Count == 0)
        {
            DrawDeviceRow(g, card, rowHeight, Glyphs.CellPhone, _colors.TextSecondary,
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
            var row = new Rectangle(card.X, y, card.Width, rowHeight);
            DrawDeviceRow(g, row, rowHeight, Glyphs.CellPhone,
                device.Connected ? _colors.Success : _colors.TextSecondary,
                device.Name,
                device.Connected ? $"Connected · {device.Address}" : $"Last seen {LastSeen(device.LastSeen)}",
                _colors.TextPrimary);
            y += rowHeight;
        }
    }

    /// <summary>A phone glyph, a status dot on it, and the two lines of text. Shared by
    /// the device rows and by the two states that have no device to name.</summary>
    private void DrawDeviceRow(
        Graphics g, Rectangle row, int height, string glyph, Color dotColor,
        string headline, string detail, Color headlineColor)
    {
        var iconPx = LogicalToDeviceUnits(20);
        var iconY = row.Y + (height - iconPx) / 2;
        var left = row.X + LogicalToDeviceUnits(2);
        using (var phone = Glyphs.Render(glyph, iconPx, _colors.TextSecondary))
        {
            if (phone is not null) g.DrawImage(phone, left, iconY, iconPx, iconPx);
        }

        // On the phone's bottom corner rather than out on its own: the dot is a property
        // of that device, and a column of free-floating dots reads as a list of states
        // with the devices as their labels, which is backwards. Lit rather than flat —
        // the phone's glowDot, so "this is happening now" is a light and not a full stop.
        var dotCentre = new PointF(
            left + iconPx - LogicalToDeviceUnits(2), iconY + iconPx - LogicalToDeviceUnits(2));
        using (var ring = new SolidBrush(_colors.Surface))
        {
            var r = LogicalToDeviceUnits(6);
            g.FillEllipse(ring, dotCentre.X - r, dotCentre.Y - r, r * 2, r * 2);
        }
        Shapes.DrawGlowDot(g, dotCentre, dotColor, LogicalToDeviceUnits(4), _colors.Glow);

        // The name in prose, the line under it in the instrument register: an address, a
        // clock time and "last seen" are readings, and the phone sets them in mono too.
        var textLeft = left + iconPx + LogicalToDeviceUnits(14);
        var textTop = row.Y + LogicalToDeviceUnits(11);
        const TextFormatFlags flags = TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix;
        TextRenderer.DrawText(g, headline, Fonts.Body, new Point(textLeft, textTop), headlineColor, flags);
        TextRenderer.DrawText(g, detail, Fonts.Mono,
            new Point(textLeft, textTop + LogicalToDeviceUnits(19)), _colors.TextSecondary, flags);
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

    /// <summary>The frame round the QR: the same chamfered, bracketed panel as everything
    /// else, filled light in both themes — a QR code on a near-black ground is not a code
    /// any scanner will read.</summary>
    private void DrawQrCard(Graphics g, Rectangle bounds)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new Rectangle(bounds.X, bounds.Y, bounds.Width - 1, bounds.Height - 1);
        var cut = LogicalToDeviceUnits(Shapes.CutLarge);
        using (var path = Shapes.Panel(rect, cut))
        {
            using var fill = new SolidBrush(Color.White);
            using var pen = new Pen(_colors.Border);
            g.FillPath(fill, path);
            g.DrawPath(pen, path);
        }
        Shapes.DrawBrackets(g, rect, _colors.Accent, cut, LogicalToDeviceUnits(2));
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
        // A free-text box can hold anything, so the range the spinner used to enforce is
        // enforced here instead, and the box is written back with what was actually kept.
        if (!int.TryParse(_port.Text, out var port) || port < 1024 || port > 65535)
        {
            _port.Text = _config.Port.ToString();
            return;
        }

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
        _shareValue.Text = _config.ResolvedShareRoot();
        _tips.SetToolTip(_shareValue, _shareValue.Text);
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
        Show(View.Pair);
    }

    private static void OpenPath(string path) =>
        Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });

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
            _tips.Dispose();
            _fadeTimer.Dispose();
            _rejectedRevertTimer.Dispose();
            _qr.Image?.Dispose();
            Icon?.Dispose(); // Form doesn't own icons handed to it.
        }
        base.Dispose(disposing);
    }
}
