using System.Drawing;
using System.Windows.Forms;

namespace PortalRemote.Theme;

/// <summary>
/// A modal built from the token palette, replacing <see cref="MessageBox"/> on the
/// surfaces this app draws itself.
///
/// The stock box does not follow an app's light/dark choice — in dark mode it opens as
/// a slab of light chrome in the middle of the one window docs/design-system.md §12
/// calls "the one designed surface", and it is the surface the destructive actions
/// (rotate the token, allow a phone to pair) live on. It also can't be given the
/// heading/body type scale of §4 or the corner radius of §5.
///
/// Deliberately small: an owner-centred form, a wrapped message, one or two
/// <see cref="TokenButton"/>s, Enter and Esc bound to them. No icons, no custom
/// layout engine — everything past that is what makes a dialog class grow into a
/// framework.
/// </summary>
public static class TokenDialog
{
    private const int Width = 420;
    private const int SidePadding = 24;
    private const int ButtonWidth = 104;
    private const int ButtonHeight = 34; // §9's 28px minimum, matching TokenButton elsewhere

    /// <summary>
    /// Show <paramref name="message"/> over <paramref name="owner"/>. Returns true when
    /// the confirming button was chosen; with <paramref name="cancelText"/> null there
    /// is only an acknowledgement and the result is always true.
    ///
    /// <paramref name="destructive"/> draws the confirming button in `danger` rather
    /// than `accent` — the difference between "Open" and "every paired phone stops
    /// working" should not be carried by the label alone.
    /// </summary>
    public static bool Show(
        IWin32Window? owner,
        string title,
        string message,
        string confirmText = "OK",
        string? cancelText = null,
        bool destructive = false)
    {
        var colors = SystemTheme.Colors;

        using var form = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = owner is null ? FormStartPosition.CenterScreen : FormStartPosition.CenterParent,
            MinimizeBox = false,
            MaximizeBox = false,
            ShowInTaskbar = false,
            BackColor = colors.Surface,
            Padding = new Padding(SidePadding, 20, SidePadding, 20),
        };

        var heading = new Label
        {
            Text = title,
            Font = Fonts.Heading,
            ForeColor = colors.TextPrimary,
            AutoSize = false,
            Dock = DockStyle.Top,
            Height = form.LogicalToDeviceUnits(28),
        };

        var body = new Label
        {
            Text = message,
            Font = Fonts.Body,
            ForeColor = colors.TextSecondary,
            AutoSize = false,
            Dock = DockStyle.Fill,
        };

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            Height = form.LogicalToDeviceUnits(ButtonHeight + 12),
            Padding = new Padding(0, 12, 0, 0),
        };

        // Added right-to-left, so the confirming button is the rightmost — the position
        // Windows puts the default action in.
        var confirm = new TokenButton
        {
            Text = confirmText,
            Size = form.LogicalToDeviceUnits(new Size(ButtonWidth, ButtonHeight)),
            DialogResult = DialogResult.OK,
        };
        confirm.ApplyTheme(colors);
        if (destructive)
        {
            // Straight onto the button rather than a `Destructive` flag on TokenButton:
            // this is the only place in the app that needs it, and one property set here
            // is smaller than a third mode on a class used on six other surfaces.
            confirm.BackColor = colors.Danger;
            confirm.ForeColor = colors.Surface;
        }
        buttons.Controls.Add(confirm);

        TokenButton? cancel = null;
        if (cancelText is not null)
        {
            cancel = new TokenButton
            {
                Text = cancelText,
                Secondary = true,
                Size = form.LogicalToDeviceUnits(new Size(ButtonWidth, ButtonHeight)),
                DialogResult = DialogResult.Cancel,
            };
            cancel.ApplyTheme(colors);
            buttons.Controls.Add(cancel);
        }

        form.Controls.Add(body);
        form.Controls.Add(buttons);
        form.Controls.Add(heading);

        // Measured, not guessed: these messages are two to six lines depending on the
        // Windows version's font (§4), and a fixed height would clip the long ones.
        using (var graphics = form.CreateGraphics())
        {
            var available = form.LogicalToDeviceUnits(Width) - form.Padding.Horizontal;
            var messageHeight = graphics.MeasureString(message, Fonts.Body, available).Height;
            form.ClientSize = new Size(
                form.LogicalToDeviceUnits(Width),
                heading.Height + (int)messageHeight + buttons.Height + form.Padding.Vertical + 12);
        }

        // When there is something to decline, the keyboard declines: Enter and Esc both
        // cancel, and confirming takes a deliberate click. Every two-button caller here
        // is either destructive (rotate the token) or a security gate (allow a phone to
        // pair), and the stock box this replaced already defaulted the pairing prompt to
        // No — losing that to a stray Enter would be a real regression, not a style one.
        // A one-button dialog has nothing to get wrong, so Enter dismisses it.
        form.AcceptButton = (Button?)cancel ?? confirm;
        form.CancelButton = (Button?)cancel ?? confirm;

        form.HandleCreated += (_, _) =>
        {
            var dark = SystemTheme.IsDark() ? 1 : 0;
            // Same call MainForm makes: without it the title bar keeps light chrome
            // around a dark dialog, which is the exact thing this class exists to stop.
            Tray.NativeMethods.DwmSetWindowAttribute(form.Handle, 20, ref dark, sizeof(int));
            var round = 2; // DWMWCP_ROUND, §5's radius scale; no-ops before Windows 11
            Tray.NativeMethods.DwmSetWindowAttribute(form.Handle, 33, ref round, sizeof(int));
        };

        return form.ShowDialog(owner) == DialogResult.OK;
    }
}
