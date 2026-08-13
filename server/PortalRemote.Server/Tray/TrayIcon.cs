using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
using Microsoft.Win32;
using PortalRemote.Config;
using PortalRemote.Control;
using PortalRemote.Files;
using PortalRemote.Pairing;
using PortalRemote.Share;
using PortalRemote.Theme;

namespace PortalRemote.Tray;

/// <summary>Notification-area icon and menu: the app's only persistent UI.</summary>
public sealed class TrayIcon : IDisposable
{
    /// <summary>Ctrl+Alt+V for "send clipboard to phone" — deliberately next door to
    /// Ctrl+V, and one of the few three-key combinations Windows and the usual apps
    /// leave alone (Ctrl+Shift+V is paste-as-plain-text in most of them).</summary>
    private const uint VkV = 0x56;

    private const string ShareHotkeyLabel = "Ctrl+Alt+V";

    private readonly ServerConfig _config;
    private readonly ConnectionState _connectionState;
    private readonly PairApproval _approval;
    private readonly ShareHub _share;
    private readonly Action _onExit;
    private readonly NotifyIcon _icon;
    private Icon _idleIcon;
    private Icon _connectedIcon;
    private Icon _errorIcon;

    /// <summary>What the last balloon was about, so clicking it can open the right
    /// thing. Cleared on click — a balloon that has already been dismissed shouldn't
    /// launch something an hour later.</summary>
    private ShareItem? _lastShare;

    private readonly Hotkey? _shareHotkey;

    // NotifyIcon has no window handle of its own to marshal through; this invisible
    // control exists solely so ConnectionState's Kestrel-thread events can hop back
    // onto the UI thread before touching _icon.
    // Fully qualified: the PortalRemote.Control namespace (ConnectionState lives
    // there) shadows System.Windows.Forms.Control by simple name in this file.
    private readonly System.Windows.Forms.Control _sync = new();
    private readonly System.Windows.Forms.Timer _errorRevertTimer = new() { Interval = 3000 };

    private MainForm? _window;

    public TrayIcon(
        ServerConfig config,
        ConnectionState connectionState,
        PairApproval approval,
        ShareHub share,
        Action onExit)
    {
        _config = config;
        _connectionState = connectionState;
        _approval = approval;
        _share = share;
        _onExit = onExit;

        // The tray owns the only window, so it's the only thing that can put an
        // owned, foreground dialog on screen — see AskToPair.
        _approval.Prompt = AskToPair;

        _ = _sync.Handle; // force handle creation so BeginInvoke works immediately

        // Pre-rendered once and swapped by reference rather than redrawn on every
        // state change; RebuildIcons() redraws all three if the Windows light/dark
        // setting changes underneath us.
        (_idleIcon, _connectedIcon, _errorIcon) = BuildIcons();

        // The tray icon lives for the app's whole session — unlike MainForm, which
        // just re-checks on its next Refresh, it needs to react live.
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;

        _errorRevertTimer.Tick += (_, _) =>
        {
            _errorRevertTimer.Stop();
            ApplyConnectionState();
        };

        // Created before the menu so the menu item can show the shortcut only if it
        // actually got it. Registered even with no phone paired yet: the failure
        // worth knowing about is "another app owns this combination", and that
        // answer doesn't change once they pair.
        _shareHotkey = new Hotkey(
            Hotkey.ModControl | Hotkey.ModAlt, VkV, () => _sync.BeginInvoke(new Action(SendClipboardToPhone)));

        var menu = new ContextMenuStrip();
        // Pairing, settings and status all live in the window now, so the menu is
        // just the two things worth doing without opening it, plus Exit.
        var open = menu.Items.Add("Open Portal Remote", null, (_, _) => ShowWindow());
        open.Font = new Font(menu.Font, FontStyle.Bold); // the double-click default
        var sendClipboard = menu.Items.Add("Send clipboard to phone", null, (_, _) => SendClipboardToPhone());
        if (_shareHotkey.Registered) sendClipboard.Text += $"   ({ShareHotkeyLabel})";
        menu.Items.Add("Copy address", null, (_, _) => CopyAddress());
        menu.Items.Add("Open shared folder", null, (_, _) => OpenShareFolder());
        menu.Items.Add("Fix network access…", null, (_, _) => FixNetworkAccess());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => _onExit());

        _icon = new NotifyIcon
        {
            Icon = _idleIcon,
            ContextMenuStrip = menu,
            Visible = true
        };
        _icon.DoubleClick += (_, _) => ShowWindow();
        _icon.BalloonTipClicked += (_, _) => OpenLastShare();

        _connectionState.Changed += () => _sync.BeginInvoke(new Action(ApplyConnectionState));
        _connectionState.AuthRejected += peer => _sync.BeginInvoke(new Action(() => ShowError(peer)));
        _share.Received += item => _sync.BeginInvoke(new Action(() => OnShareReceived(item)));

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

    private void ShowError(string peer)
    {
        _icon.Icon = _errorIcon;
        // NotifyIcon.Text is capped at 63 chars, so the address goes last where it can
        // be cut without losing what happened.
        _icon.Text = Truncate($"{ServerInfo.Name} — pairing rejected: {peer}", 63);
        _errorRevertTimer.Stop();
        _errorRevertTimer.Start();
    }

    private static string Truncate(string text, int max) =>
        text.Length <= max ? text : text[..(max - 1)] + "…";

    private (Icon Idle, Icon Connected, Icon Error) BuildIcons()
    {
        var colors = SystemTheme.Colors;
        // Three states — see docs/design-system.md §7/§11. Idle is the bare mark in
        // text-secondary; connected/error knock it out of a filled status-colored
        // disc, so the state reads as color at 16px without changing the silhouette.
        return (
            BrandMark.CreateIcon(32, colors.TextSecondary),
            BrandMark.CreateIcon(32, Color.White, badge: colors.Accent),
            BrandMark.CreateIcon(32, Color.White, badge: colors.Danger));
    }

    private void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category != UserPreferenceCategory.General) return;
        _sync.BeginInvoke(new Action(RebuildIcons));
    }

    /// <summary>Redraw all three icon states for the current Windows light/dark
    /// setting and refresh the window if it's open. The tray icon is the one desktop
    /// surface that's alive for the whole session, so — unlike MainForm, which just
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

        if (_window is { IsDisposed: false }) _window.Refresh(_config);
    }

    public void ShowWindow()
    {
        if (_window is null || _window.IsDisposed)
            _window = new MainForm(_config, _connectionState);
        else
            _window.Refresh(_config);

        _window.ShowAnimated();
        _window.WindowState = FormWindowState.Normal;
        _window.BringToFront();
        _window.Activate();
    }

    /// <summary>
    /// The one thing standing between a phone on the LAN and this PC's pairing
    /// token. Runs on the UI thread (PairApproval marshals it there).
    ///
    /// The window is opened first on purpose: with only a tray icon the process
    /// has no taskbar button, so an unowned dialog can end up behind whatever the
    /// user is looking at — which is exactly the dialog you don't want missed.
    /// Defaults to No.
    /// </summary>
    private bool AskToPair(string device, string remoteIp)
    {
        ShowWindow();
        return TokenDialog.Show(
            _window,
            "Let this phone control this PC?",
            $"{device} ({remoteIp}) is asking to pair.\n\n"
            + "Only allow this if it is the phone in your hand right now. Pairing gives "
            + "it the keyboard, the mouse, the screen and the shared folder.",
            confirmText: "Allow",
            cancelText: "Deny");
    }

    /// <summary>
    /// The whole "my phone can't see this PC" support call, as one menu item.
    /// Checks the two causes the PC can actually see, offers to fix the one that is
    /// fixable from here, and names the one that isn't — a VPN has to be dealt with
    /// in the VPN's own app, and it may be the phone's VPN rather than this PC's.
    /// </summary>
    private void FixNetworkAccess()
    {
        ShowWindow();

        var vpn = Reachability.ActiveVpnName();
        var needsRule = Reachability.FirewallNeedsSetup();

        if (!needsRule && vpn is null)
        {
            TokenDialog.Show(
                _window,
                "Nothing is blocking it here",
                "Windows Firewall already allows Portal Remote, and no VPN is running on this PC.\n\n"
                + "If your phone still cannot connect, check that it is on the same Wi-Fi network "
                + "and that it does not have a VPN of its own switched on.");
            return;
        }

        if (vpn is not null)
        {
            TokenDialog.Show(
                _window,
                "A VPN is in the way",
                $"A VPN is active on this PC (\"{vpn}\").\n\n"
                + "VPNs route traffic away from your local network, which can stop a phone on the "
                + "same Wi-Fi from reaching this PC. Allow local network access in the VPN app, or "
                + "turn it off while using Portal Remote.\n\n"
                + "A VPN switched on the phone does the same thing, so check there too.");
        }

        if (!needsRule) return;

        var add = TokenDialog.Show(
            _window,
            "Add a firewall rule?",
            "Windows Firewall has no rule allowing Portal Remote to accept connections "
            + "from your phone.\n\nWindows will ask you to approve this as an administrator.",
            confirmText: "Add rule",
            cancelText: "Not now");
        if (!add) return;

        var added = Reachability.RequestFirewallSetup();
        TokenDialog.Show(
            _window,
            added ? "Allowed through the firewall" : "The rule was not added",
            added
                ? "Portal Remote is now allowed through Windows Firewall. Try connecting from your phone again."
                : "You can add it yourself in Windows Security under Firewall & network "
                  + "protection → Allow an app through firewall.");
    }

    public void Notify(string title, string message) =>
        _icon.ShowBalloonTip(4000, title, message, ToolTipIcon.Info);

    /// <summary>
    /// A share landed from the phone. The point of the feature is that the thing is
    /// *already where you need it* by the time you look up, so this puts it on the
    /// clipboard first and only then says so — the balloon is a receipt, not a step.
    /// </summary>
    private void OnShareReceived(ShareItem item)
    {
        _lastShare = item;

        switch (item.Kind)
        {
            case ShareKind.Text:
            case ShareKind.Link:
                var copied = TrySetClipboard(() => Clipboard.SetText(item.Text ?? string.Empty));
                Notify(
                    $"{item.From} shared a {(item.Kind == ShareKind.Link ? "link" : "note")}",
                    item.Preview() + (item.Kind == ShareKind.Link
                        ? "\nClick to open"
                        : copied ? "\nCopied to clipboard" : string.Empty));
                break;

            case ShareKind.Image:
                TrySetClipboard(() =>
                {
                    // Loaded through a stream and disposed before the file is left
                    // alone: Image.FromFile keeps the file locked for the image's
                    // lifetime, which would block the user renaming or deleting it.
                    using var stream = File.OpenRead(InboxFile(item));
                    using var image = System.Drawing.Image.FromStream(stream);
                    Clipboard.SetImage(image);
                });
                Notify($"{item.From} shared an image", $"{item.FileName}\nCopied to clipboard — click to show it");
                break;

            default:
                Notify($"{item.From} shared a file", $"{item.FileName}\nClick to show it in the Inbox");
                break;
        }
    }

    /// <summary>
    /// Open what the last balloon was about. Links go to the default browser; files
    /// are only ever *revealed* in Explorer, never launched — a paired phone can put
    /// any file in the Inbox, and one click of a balloon should not be able to run it.
    /// </summary>
    private void OpenLastShare()
    {
        var item = _lastShare;
        _lastShare = null;
        if (item is null) return;

        if (item.Kind == ShareKind.Link &&
            Uri.TryCreate(item.Text?.Trim(), UriKind.Absolute, out var url) &&
            (url.Scheme == Uri.UriSchemeHttp || url.Scheme == Uri.UriSchemeHttps))
        {
            Process.Start(new ProcessStartInfo { FileName = url.AbsoluteUri, UseShellExecute = true });
            return;
        }

        if (item.FileName is null) return;
        Process.Start(new ProcessStartInfo
        {
            FileName = "explorer.exe",
            Arguments = $"/select,\"{InboxFile(item)}\"",
            UseShellExecute = true
        });
    }

    private string InboxFile(ShareItem item) =>
        Path.Combine(_share.InboxPath(), FilePaths.SafeFileName(item.FileName ?? string.Empty));

    /// <summary>
    /// Send whatever is on this PC's clipboard to every connected phone — the other
    /// half of the feature, and the one that needs a shortcut, since you reach it
    /// from inside whatever app you just copied from.
    /// </summary>
    private void SendClipboardToPhone()
    {
        if (!_share.HasClients)
        {
            Notify(ServerInfo.Name, "No phone connected. Open Portal Remote on your phone and try again.");
            return;
        }

        ShareItem? item = null;
        try
        {
            if (Clipboard.ContainsImage())
            {
                using var image = Clipboard.GetImage();
                if (image is not null) item = SaveClipboardImage(image);
            }
            else if (Clipboard.ContainsText())
            {
                var text = Clipboard.GetText();
                if (!string.IsNullOrWhiteSpace(text))
                {
                    item = new ShareItem(
                        ShareKind.ForText(text), text, null, text.Length,
                        Environment.MachineName, DateTimeOffset.Now);
                }
            }
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            Notify(ServerInfo.Name, "Another program is using the clipboard. Try again.");
            return;
        }

        if (item is null)
        {
            Notify(ServerInfo.Name, "Nothing on the clipboard to send.");
            return;
        }

        // Fire and forget: SendToPhonesAsync swallows per-socket failures itself, and
        // blocking the UI thread on a LAN round-trip would freeze the tray menu.
        _ = _share.SendToPhonesAsync(item);
        Notify(ServerInfo.Name, $"Sent to your phone: {item.Preview(60)}");
    }

    private ShareItem SaveClipboardImage(System.Drawing.Image image)
    {
        using var buffer = new MemoryStream();
        image.Save(buffer, ImageFormat.Png);
        buffer.Position = 0;

        // Blocking on the UI thread: this is a few megabytes from memory to a local
        // file, and splitting the tray menu handler across an await to save a
        // millisecond isn't worth the extra state.
        var name = _share
            .SaveToInboxAsync($"clipboard-{DateTime.Now:yyyyMMdd-HHmmss}.png", buffer)
            .GetAwaiter().GetResult();

        return new ShareItem(
            ShareKind.Image, null, name, buffer.Length, Environment.MachineName, DateTimeOffset.Now);
    }

    /// <summary>Clipboard writes fail when another process holds it open; none of
    /// them are worth interrupting the user over.</summary>
    private static bool TrySetClipboard(Action set)
    {
        try
        {
            set();
            return true;
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.ExternalException
                                       or IOException or ArgumentException)
        {
            return false;
        }
    }

    private void CopyAddress() => TrySetClipboard(() => Clipboard.SetText(PairingService.HttpBase(_config)));

    private void OpenShareFolder()
    {
        var path = _config.ResolvedShareRoot();
        Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
    }

    public void Dispose()
    {
        SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
        _shareHotkey?.Dispose();
        // Nothing left to own a dialog once the window goes; further pair requests
        // are refused rather than prompting against a disposed form.
        _approval.Prompt = null;
        _errorRevertTimer.Dispose();
        _icon.Visible = false;
        _icon.Dispose();
        _window?.Dispose();
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
