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

/// <summary>Notification-area icon and menu: the app's only persistent UI.</summary>
public sealed class TrayIcon : IDisposable
{
    private readonly ServerConfig _config;
    private readonly ConnectionState _connectionState;
    private readonly Action _onExit;
    private readonly NotifyIcon _icon;
    private Icon _idleIcon;
    private Icon _connectedIcon;
    private Icon _errorIcon;

    // NotifyIcon has no window handle of its own to marshal through; this invisible
    // control exists solely so ConnectionState's Kestrel-thread events can hop back
    // onto the UI thread before touching _icon.
    // Fully qualified: the PortalRemote.Control namespace (ConnectionState lives
    // there) shadows System.Windows.Forms.Control by simple name in this file.
    private readonly System.Windows.Forms.Control _sync = new();
    private readonly System.Windows.Forms.Timer _errorRevertTimer = new() { Interval = 3000 };

    private QrForm? _qrForm;

    public TrayIcon(ServerConfig config, ConnectionState connectionState, Action onExit)
    {
        _config = config;
        _connectionState = connectionState;
        _onExit = onExit;

        _ = _sync.Handle; // force handle creation so BeginInvoke works immediately

        // Three states — see docs/design-system.md §7: idle (outline), connected
        // (filled, accent), error (filled, danger). Pre-rendered once and swapped by
        // reference rather than redrawn on every state change; RebuildIcons() redraws
        // all three if the Windows light/dark setting changes underneath us.
        (_idleIcon, _connectedIcon, _errorIcon) = BuildIcons();

        // The tray icon lives for the app's whole session — unlike QrForm, which
        // just re-checks on its next Refresh, it needs to react live.
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;

        _errorRevertTimer.Tick += (_, _) =>
        {
            _errorRevertTimer.Stop();
            ApplyConnectionState();
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add("Show pairing QR", null, (_, _) => ShowQr());
        menu.Items.Add("Copy address", null, (_, _) => CopyAddress());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open shared folder", null, (_, _) => OpenShareFolder());
        menu.Items.Add("Rotate pairing token", null, (_, _) => RotateToken());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => _onExit());

        _icon = new NotifyIcon
        {
            Icon = _idleIcon,
            ContextMenuStrip = menu,
            Visible = true
        };
        _icon.DoubleClick += (_, _) => ShowQr();

        _connectionState.Changed += () => _sync.BeginInvoke(new Action(ApplyConnectionState));
        _connectionState.AuthRejected += () => _sync.BeginInvoke(new Action(ShowError));

        ApplyConnectionState();
    }

    private void ApplyConnectionState()
    {
        _errorRevertTimer.Stop();
        var connected = _connectionState.IsConnected;
        _icon.Icon = connected ? _connectedIcon : _idleIcon;
        _icon.Text = connected
            ? $"{ServerInfo.Name} — connected"
            : $"{ServerInfo.Name} — {PairingService.HttpBase(_config)}";
    }

    private void ShowError()
    {
        _icon.Icon = _errorIcon;
        _icon.Text = $"{ServerInfo.Name} — pairing rejected";
        _errorRevertTimer.Stop();
        _errorRevertTimer.Start();
    }

    private (Icon Idle, Icon Connected, Icon Error) BuildIcons()
    {
        var colors = SystemTheme.Colors;
        return (
            CreateIcon(filled: false, colors.TextSecondary),
            CreateIcon(filled: true, colors.Accent),
            CreateIcon(filled: true, colors.Danger));
    }

    private void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category != UserPreferenceCategory.General) return;
        _sync.BeginInvoke(new Action(RebuildIcons));
    }

    /// <summary>Redraw all three icon states for the current Windows light/dark
    /// setting and refresh QrForm if it's open. The tray icon is the one desktop
    /// surface that's alive for the whole session, so — unlike QrForm, which just
    /// re-checks on its next Refresh — it has to react to the setting changing live.</summary>
    private void RebuildIcons()
    {
        var (idle, connected, error) = BuildIcons();
        var (oldIdle, oldConnected, oldError) = (_idleIcon, _connectedIcon, _errorIcon);

        _idleIcon = idle;
        _connectedIcon = connected;
        _errorIcon = error;
        ApplyConnectionState();

        oldIdle.Dispose();
        oldConnected.Dispose();
        oldError.Dispose();

        if (_qrForm is { IsDisposed: false }) _qrForm.Refresh(_config);
    }

    /// <summary>
    /// Draw the tray icon at runtime so the app ships without an .ico asset.
    /// A rounded monitor glyph; filled for connected/error, outline for idle.
    /// </summary>
    private static Icon CreateIcon(bool filled, Color color)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            if (filled)
            {
                using var background = new SolidBrush(color);
                g.FillEllipse(background, 0, 0, 31, 31);

                using var screen = new SolidBrush(Color.White);
                g.FillRectangle(screen, 8, 9, 16, 11);
                g.FillRectangle(screen, 14, 20, 4, 3);
                g.FillRectangle(screen, 11, 23, 10, 2);

                using var inner = new SolidBrush(color);
                g.FillRectangle(inner, 10, 11, 12, 7);
            }
            else
            {
                using var stroke = new Pen(color, 2f);
                g.DrawEllipse(stroke, 1, 1, 29, 29);
                g.DrawRectangle(stroke, 9, 10, 15, 10);

                using var glyph = new SolidBrush(color);
                g.FillRectangle(glyph, 14, 20, 4, 3);
                g.FillRectangle(glyph, 11, 23, 10, 2);
            }
        }

        // GetHicon hands back an unmanaged handle; clone into a managed icon so the
        // handle can be freed immediately rather than leaking for the app's lifetime.
        var handle = bitmap.GetHicon();
        try
        {
            using var temp = Icon.FromHandle(handle);
            return (Icon)temp.Clone();
        }
        finally
        {
            NativeMethods.DestroyIcon(handle);
        }
    }

    public void ShowQr()
    {
        if (_qrForm is null || _qrForm.IsDisposed)
            _qrForm = new QrForm(_config);
        else
            _qrForm.Refresh(_config);

        _qrForm.ShowAnimated();
        _qrForm.WindowState = FormWindowState.Normal;
        _qrForm.BringToFront();
        _qrForm.Activate();
    }

    public void Notify(string title, string message) =>
        _icon.ShowBalloonTip(4000, title, message, ToolTipIcon.Info);

    private void CopyAddress()
    {
        try
        {
            Clipboard.SetText(PairingService.HttpBase(_config));
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // Clipboard busy; nothing worth surfacing.
        }
    }

    private void OpenShareFolder()
    {
        var path = _config.ResolvedShareRoot();
        Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
    }

    private void RotateToken()
    {
        var answer = MessageBox.Show(
            "Generate a new pairing token?\n\nEvery paired phone will have to scan the QR code again.",
            ServerInfo.Name, MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);
        if (answer != DialogResult.OK) return;

        _config.RotateToken();
        if (_qrForm is { IsDisposed: false }) _qrForm.Refresh(_config);
        Notify(ServerInfo.Name, "Pairing token rotated. Re-pair your phone.");
    }

    public void Dispose()
    {
        SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
        _errorRevertTimer.Dispose();
        _icon.Visible = false;
        _icon.Dispose();
        _qrForm?.Dispose();
        _idleIcon.Dispose();
        _connectedIcon.Dispose();
        _errorIcon.Dispose();
        _sync.Dispose();
    }
}

internal static partial class NativeMethods
{
    [System.Runtime.InteropServices.LibraryImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    internal static partial bool DestroyIcon(IntPtr handle);

    [System.Runtime.InteropServices.LibraryImport("dwmapi.dll")]
    internal static partial int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int pvAttribute, int cbAttribute);
}
