using System.Drawing;
using System.Drawing.Drawing2D;

namespace PortalRemote.Theme;

/// <summary>Shared corner-radius scale and path builder — see docs/design-system.md §5.</summary>
public static class RoundedRect
{
    public const int RadiusSmall = 8;  // buttons, chips
    public const int RadiusCard = 12;  // cards, sheets

    public static GraphicsPath Path(Rectangle bounds, int radius)
    {
        var d = radius * 2;
        var path = new GraphicsPath();
        path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
        path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
        path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}
