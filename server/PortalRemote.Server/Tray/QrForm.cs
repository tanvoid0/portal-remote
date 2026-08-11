using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using PortalRemote.Config;
using PortalRemote.Pairing;
using PortalRemote.Theme;

namespace PortalRemote.Tray;

/// <summary>Window showing the pairing QR code and the address to type manually.</summary>
public sealed class QrForm : Form
{
    private const int FadeInMs = 180;
    private const int FadeInTickMs = 15;

    private readonly ServerConfig _config;
    private readonly Label _heading;
    private readonly Panel _qrCard;
    private readonly PictureBox _qr;
    private readonly Label _address;
    private readonly Label _hint;
    private readonly TokenButton _copy;
    private readonly System.Windows.Forms.Timer _fadeTimer = new() { Interval = FadeInTickMs };
    private DateTime _fadeStart;
    private PaletteColors _colors = SystemTheme.Colors;

    public QrForm(ServerConfig config)
    {
        _config = config;

        Text = $"{ServerInfo.Name} — pair your phone";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.Manual;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(380, 520);

        _heading = new Label
        {
            Text = "Scan with the Portal Remote app",
            Font = Fonts.Heading,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 40
        };

        _qr = new PictureBox
        {
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Fill,
        };

        // "Surface-raised" card framing the QR code — see design-system.md §7.
        _qrCard = new Panel
        {
            Dock = DockStyle.Top,
            Height = 320,
            Padding = new Padding(12),
        };
        _qrCard.Paint += (_, e) => DrawCardBorder(e.Graphics, _qrCard.ClientRectangle);
        _qrCard.Controls.Add(_qr);

        _address = new Label
        {
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 60,
            Font = new Font(FontFamily.GenericMonospace, 9.5f),
        };

        _hint = new Label
        {
            Text = "Phone and PC must be on the same Wi-Fi.\n"
                 + "If it will not connect, allow port "
                 + $"{config.Port} through Windows Firewall on Private networks.",
            Font = Fonts.Caption,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 50,
        };

        _copy = new TokenButton
        {
            Text = "Copy address",
            Dock = DockStyle.Top,
            Height = 34,
        };
        _copy.Click += (_, _) => CopyAddress();

        // Docked controls stack in reverse order of addition.
        Controls.Add(_copy);
        Controls.Add(_hint);
        Controls.Add(_address);
        Controls.Add(_qrCard);
        Controls.Add(_heading);

        _fadeTimer.Tick += (_, _) => TickFade();

        Refresh(config);
    }

    /// <summary>Re-render for the current address/token, and re-apply the palette in
    /// case the Windows light/dark setting changed since this window was last shown.</summary>
    public void Refresh(ServerConfig config)
    {
        ApplyTheme();

        var payload = PairingService.PairUrl(config);
        var png = PairingService.QrPng(payload, pixelsPerModule: 10);

        _qr.Image?.Dispose();
        using var stream = new MemoryStream(png);
        _qr.Image = Image.FromStream(stream);

        _address.Text = $"{PairingService.HttpBase(config)}\n\nor enter the address manually";
    }

    private void ApplyTheme()
    {
        _colors = SystemTheme.Colors;

        BackColor = _colors.Surface;
        _heading.ForeColor = _colors.TextPrimary;
        _qr.BackColor = _colors.SurfaceRaised;
        _qrCard.BackColor = _colors.SurfaceRaised;
        _address.ForeColor = _colors.TextPrimary;
        _hint.ForeColor = _colors.TextSecondary;
        _copy.ApplyTheme(_colors);
        _qrCard.Invalidate();

        if (IsHandleCreated) ApplyTitleBarTheme();
    }

    /// <summary>Show anchored near the tray corner with a 180ms ease-out fade-in,
    /// instead of popping in centered — see design-system.md §6/§7.</summary>
    public void ShowAnimated()
    {
        var area = Screen.PrimaryScreen!.WorkingArea;
        const int margin = 12;
        Location = new Point(area.Right - Width - margin, area.Bottom - Height - margin);

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

    private void DrawCardBorder(Graphics g, Rectangle bounds)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new Rectangle(bounds.X, bounds.Y, bounds.Width - 1, bounds.Height - 1);
        using var path = RoundedRect.Path(rect, RoundedRect.RadiusCard);
        using var pen = new Pen(_colors.Border);
        g.DrawPath(pen, path);
    }

    private void CopyAddress()
    {
        try
        {
            Clipboard.SetText(PairingService.HttpBase(_config));
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // Another process holds the clipboard; not worth interrupting the user.
        }
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
            _fadeTimer.Dispose();
            _qr.Image?.Dispose();
        }
        base.Dispose(disposing);
    }
}
