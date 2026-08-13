using System.Drawing;
using System.Drawing.Drawing2D;
using PortalRemote.Tray; // NativeMethods.DestroyIcon

namespace PortalRemote.Theme;

/// <summary>
/// The Portal Remote brand mark — see docs/design-system.md §11. A display with a
/// phone-shaped opening punched through it. Drawn on the same 24-unit grid as the
/// Android app's res/drawable/ic_portal_mark.xml; keep the two in sync.
///
/// Vector rather than a bundled bitmap so it stays crisp at every DPI and can be
/// recolored per state (tray idle/connected/error) without shipping three assets.
/// </summary>
public static class BrandMark
{
    private const float Grid = 24f;

    /// <summary>Fill the mark inside <paramref name="bounds"/>, centered and
    /// aspect-preserved, in a single color. The phone-shaped opening is left
    /// transparent, so whatever sits behind it shows through.</summary>
    public static void Draw(Graphics g, RectangleF bounds, Color color)
    {
        var previous = g.SmoothingMode;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        var scale = Math.Min(bounds.Width, bounds.Height) / Grid;
        using var transform = new Matrix();
        transform.Translate(
            bounds.X + (bounds.Width - Grid * scale) / 2f,
            bounds.Y + (bounds.Height - Grid * scale) / 2f);
        transform.Scale(scale, scale);

        using var brush = new SolidBrush(color);

        // Body and opening share one even-odd path so the opening is a real hole.
        using (var body = new GraphicsPath(FillMode.Alternate))
        {
            AddRounded(body, 1.5f, 3f, 21f, 15f, 3f);  // display
            AddRounded(body, 9.5f, 6f, 5f, 9f, 1.5f);  // phone-shaped portal
            body.Transform(transform);
            g.FillPath(brush, body);
        }

        // The stand is a second path: it overlaps the display body, and inside the
        // even-odd path above that overlap would punch a hole instead of merging.
        using (var stand = new GraphicsPath())
        {
            AddRounded(stand, 10.5f, 17.5f, 3f, 3f, 0f);     // neck
            AddRounded(stand, 7f, 20f, 10f, 2.5f, 1.25f);    // base
            stand.Transform(transform);
            g.FillPath(brush, stand);
        }

        g.SmoothingMode = previous;
    }

    /// <summary>The mark as a Windows icon: knocked out of a rounded-square tile in
    /// <paramref name="badge"/> — the same lockup as the Android launcher icon — or,
    /// when <paramref name="badge"/> is null, the bare mark in <paramref name="color"/>.</summary>
    public static Icon CreateIcon(int size, Color color, Color? badge = null)
    {
        using var bitmap = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            var box = new RectangleF(0, 0, size, size);
            if (badge is { } tileColor)
            {
                using var tile = new GraphicsPath();
                AddRounded(tile, 0.5f, 0.5f, size - 1f, size - 1f, size * 0.22f);
                using var fill = new SolidBrush(tileColor);
                g.FillPath(fill, tile);
                // Inset so the mark sits inside the tile rather than touching it.
                box = RectangleF.Inflate(box, -size * 0.12f, -size * 0.12f);
            }

            Draw(g, box, color);
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

    private static void AddRounded(GraphicsPath path, float x, float y, float w, float h, float r)
    {
        path.StartFigure();
        if (r <= 0)
        {
            path.AddRectangle(new RectangleF(x, y, w, h));
            path.CloseFigure();
            return;
        }

        var d = r * 2;
        path.AddArc(x, y, d, d, 180, 90);
        path.AddArc(x + w - d, y, d, d, 270, 90);
        path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
        path.AddArc(x, y + h - d, d, d, 90, 90);
        path.CloseFigure();
    }
}
