using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using Microsoft.Win32;
using PortalRemote.Config;
using PortalRemote.Control;
using PortalRemote.Pairing;
using PortalRemote.Theme;

namespace PortalRemote.Tray;

/// <summary>
/// The desktop app's one window: connection status, the pairing QR, and the
/// handful of settings that used to require hand-editing config.json. Two columns
/// — pairing on the left, everything about this PC on the right — so the whole
/// app is legible without scrolling, per docs/design-system.md §1 ("calm and
/// clarity, not motion") and §12.
/// </summary>
public sealed class MainForm : Form
{
    private const int FadeInMs = 180;
    private const int FadeInTickMs = 15;

    private readonly ServerConfig _config;
    private readonly ConnectionState _connectionState;

    private readonly Panel _header;
    private readonly Panel _statusCard;
    private readonly PictureBox _qr;
    private readonly Panel _qrCard;
    private readonly Label _pairHeading;
    private readonly Label _address;
    private readonly Label _hint;
    private readonly TokenButton _copy;

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

    public MainForm(ServerConfig config, ConnectionState connectionState)
    {
        _config = config;
        _connectionState = connectionState;

        Text = ServerInfo.Name;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(780, 620);
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

        // ---- left column: pairing -------------------------------------------------
        _pairHeading = new Label
        {
            Text = "Scan to pair a phone",
            Font = Fonts.Heading,
            Dock = DockStyle.Top,
            Height = 28,
        };

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
            Height = 56,
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "Or pick this PC from the list on the phone — that way asks permission "
                 + "here first. Phone and PC must be on the same Wi-Fi; if it will not "
                 + $"connect, allow port {config.RunningPort} through Windows Firewall on "
                 + "Private networks.",
        };

        _copy = new TokenButton { Text = "Copy address", Dock = DockStyle.Bottom, Height = 34 };
        _copy.Click += (_, _) => CopyAddress();

        var pairColumn = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 0, 12, 0) };
        // Docking is resolved in reverse order of addition — the control added last
        // claims its edge first — so the Fill goes in first and the bottom stack is
        // added upwards-last: address, hint, then the button that sits lowest.
        pairColumn.Controls.Add(_qrCard);
        pairColumn.Controls.Add(_pairHeading);
        pairColumn.Controls.Add(_address);
        pairColumn.Controls.Add(_hint);
        pairColumn.Controls.Add(_copy);

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
        _changeShare = new TokenButton { Text = "Change", Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _changeShare.Click += (_, _) => ChangeShareFolder();
        _openShare = new TokenButton { Text = "Open", Secondary = true, Dock = DockStyle.Fill };
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
        _copyCast = new TokenButton { Text = "Copy", Secondary = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 8, 0) };
        _copyCast.Click += (_, _) => CopyText(PairingService.ReceiverUrl(_config));
        // Opening it here makes *this* PC the cast target, which is the common case
        // and saves typing the address anywhere at all.
        _openCast = new TokenButton { Text = "Open", Secondary = true, Dock = DockStyle.Fill };
        _openCast.Click += (_, _) => OpenPath(PairingService.ReceiverUrl(_config));

        var castRow = TrailingButtonRow();
        castRow.Controls.Add(_castBox, 0, 0);
        castRow.Controls.Add(_copyCast, 1, 0);
        castRow.Controls.Add(_openCast, 2, 0);

        _startWithWindows = new CheckBox
        {
            Text = "Start Portal Remote when I sign in",
            Font = Fonts.Body,
            Dock = DockStyle.Fill,
            Checked = StartsWithWindows(),
        };
        // Wired after Checked is set so reading the current state doesn't write it back.
        _startWithWindows.CheckedChanged += (_, _) => SetStartWithWindows(_startWithWindows.Checked);

        _rotate = new TokenButton { Text = "Rotate pairing token", Secondary = true, Dock = DockStyle.Left, Width = 180 };
        _rotate.Click += (_, _) => RotateToken();
        _rotateNote = new Label
        {
            Font = Fonts.Caption,
            Dock = DockStyle.Fill,
            AutoSize = false,
            Text = "Locks out every paired phone until it scans the new code.",
        };

        var settingsColumn = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1 };
        settingsColumn.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        // Heights include AddRow's 6px bottom gap.
        AddRow(settingsColumn, _statusCard, 72);
        AddRow(settingsColumn, _settingsHeading, 32);
        AddRow(settingsColumn, portRow, 36);
        AddRow(settingsColumn, _portNote, 44); // two caption lines
        AddRow(settingsColumn, _shareLabel, 26);
        AddRow(settingsColumn, shareRow, 40);
        AddRow(settingsColumn, _castLabel, 26);
        AddRow(settingsColumn, castRow, 40);
        AddRow(settingsColumn, _startWithWindows, 34);
        AddRow(settingsColumn, _rotate, 40);
        AddRow(settingsColumn, _rotateNote, 28);
        // Soaks up whatever is left so the rows above stay top-aligned at any height.
        settingsColumn.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var body = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1 };
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 46));
        body.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 54));
        body.Controls.Add(pairColumn, 0, 0);
        body.Controls.Add(settingsColumn, 1, 0);

        Controls.Add(body);
        Controls.Add(_footer);
        Controls.Add(_header);

        _fadeTimer.Tick += (_, _) => TickFade();
        _rejectedRevertTimer.Tick += (_, _) =>
        {
            _rejectedRevertTimer.Stop();
            _rejectedPeer = null;
            _statusCard.Invalidate();
        };
        _connectionState.Changed += OnConnectionChanged;
        _connectionState.AuthRejected += OnAuthRejected;

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

    /// <summary>The two-button trailing columns in the shared-folder and cast rows.
    /// Absolute column styles need the same scaling as the rows above, or the buttons
    /// keep their 96dpi width while their labels grow past it.</summary>
    private TableLayoutPanel TrailingButtonRow()
    {
        var row = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(88)));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, LogicalToDeviceUnits(72)));
        return row;
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
    }

    private void ApplyTheme()
    {
        _colors = SystemTheme.Colors;

        BackColor = _colors.Surface;
        _header.BackColor = _colors.Surface;
        _header.Invalidate();

        foreach (var label in new[] { _pairHeading, _settingsHeading, _address, _portLabel, _shareLabel, _castLabel })
        {
            label.BackColor = _colors.Surface;
            label.ForeColor = _colors.TextPrimary;
        }

        foreach (var caption in new[] { _hint, _portNote, _rotateNote, _footer })
        {
            caption.BackColor = _colors.Surface;
            caption.ForeColor = _colors.TextSecondary;
        }

        _startWithWindows.BackColor = _colors.Surface;
        _startWithWindows.ForeColor = _colors.TextPrimary;

        _port.BackColor = _colors.SurfaceRaised;
        _port.ForeColor = _colors.TextPrimary;
        _shareBox.BackColor = _colors.SurfaceRaised;
        _shareBox.ForeColor = _colors.TextSecondary;
        _castBox.BackColor = _colors.SurfaceRaised;
        _castBox.ForeColor = _colors.TextSecondary;

        _qr.BackColor = _colors.SurfaceRaised;
        _qrCard.BackColor = _colors.SurfaceRaised;
        _qrCard.Invalidate();
        _statusCard.BackColor = _colors.Surface;
        _statusCard.Invalidate();

        _copy.ApplyTheme(_colors);
        _changeShare.ApplyTheme(_colors);
        _openShare.ApplyTheme(_colors);
        _copyCast.ApplyTheme(_colors);
        _openCast.ApplyTheme(_colors);
        _rotate.ApplyTheme(_colors);

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
        BeginInvoke(new Action(() => _statusCard.Invalidate()));
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

        var connected = _connectionState.IsConnected;
        // A refusal outranks the connection state for a few seconds. It is the direct
        // consequence of the button directly below this card, and until now the only
        // thing that reacted was a 16px tray icon the user is not looking at while
        // they're looking at this window.
        var rejected = _rejectedPeer;

        var dotColor = rejected is not null ? _colors.Danger
            : connected ? _colors.Success
            : _colors.TextSecondary;
        using (var dot = new SolidBrush(dotColor))
        {
            g.FillEllipse(dot, card.X + 16, card.Y + card.Height / 2 - 5, 10, 10);
        }

        var headline = rejected is not null ? "A phone was refused"
            : connected ? "Phone connected"
            : "Waiting for a phone";
        // Named, not guessed: the socket knows the address it accepted, which is the
        // only thing this PC actually knows about the phone. This line used to be
        // Environment.MachineName — the name of *this* PC, under a headline about the
        // phone.
        var detail = rejected is not null ? $"{rejected} — its pairing token is not valid here"
            : connected ? _connectionState.Peer ?? "Connected"
            : "Scan the code to pair one";

        using var primary = new SolidBrush(rejected is not null ? _colors.Danger : _colors.TextPrimary);
        using var secondary = new SolidBrush(_colors.TextSecondary);
        g.DrawString(headline, Fonts.Body, primary, card.X + 36, card.Y + 14);
        g.DrawString(detail, Fonts.Caption, secondary, card.X + 36, card.Y + 34);
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
            _connectionState.AuthRejected -= OnAuthRejected;
            _fadeTimer.Dispose();
            _rejectedRevertTimer.Dispose();
            _qr.Image?.Dispose();
            Icon?.Dispose(); // Form doesn't own icons handed to it.
        }
        base.Dispose(disposing);
    }
}
