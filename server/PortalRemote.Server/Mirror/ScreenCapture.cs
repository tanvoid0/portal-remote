using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using PortalRemote.Input;

// Namespace is Mirror, not Screen: WinForms already has a `Screen` type and a
// `PortalRemote.Screen` namespace shadows it in every file that uses both.
namespace PortalRemote.Mirror;

/// <summary>
/// Grabs the desktop as a JPEG. GDI <c>BitBlt</c> (via <see cref="Graphics.CopyFromScreen"/>)
/// rather than the Desktop Duplication API: it's ~40 lines instead of a DirectX
/// device/swapchain, and at the 8-15fps this streams at the difference doesn't show.
/// If frame rate ever needs to go higher, dxcam-style Desktop Duplication is the
/// upgrade path.
/// </summary>
public static partial class ScreenCapture
{
    /// <summary>Windows draws the cursor separately from the screen surface, so BitBlt
    /// never picks it up — it has to be composited in by hand.</summary>
    private const int DiNormal = 0x0003;

    private const int CursorShowing = 0x0001;

    private static readonly ImageCodecInfo JpegCodec =
        ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);

    /// <summary>GDI screen capture is not reentrant-friendly and several clients may
    /// stream at once; serialising costs nothing at these frame rates.</summary>
    private static readonly object CaptureLock = new();

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CursorInfo
    {
        public int Size;
        public int Flags;
        public nint Cursor;
        public POINT ScreenPos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IconInfo
    {
        public int IsIcon;
        public int XHotspot;
        public int YHotspot;
        public nint MaskBitmap;
        public nint ColorBitmap;
    }

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetCursorInfo(ref CursorInfo info);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetIconInfo(nint icon, out IconInfo info);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DrawIconEx(
        nint hdc, int x, int y, nint icon, int width, int height,
        uint step, nint brush, int flags);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteObject(nint obj);

    /// <summary>
    /// Capture one monitor (see <see cref="WinInput.BoundsFor"/>) and encode it as JPEG.
    /// <paramref name="maxWidth"/> caps the width — a 4K screen sent at native
    /// resolution is both slow to encode and pointless on a phone.
    /// </summary>
    public static byte[] Jpeg(int? monitor, int maxWidth, int quality, bool drawCursor = true)
    {
        var area = WinInput.BoundsFor(monitor);
        if (area.Width <= 0 || area.Height <= 0)
            throw new InvalidOperationException("capture area has no size");

        lock (CaptureLock)
        {
            // ponytail: a fresh Bitmap per frame. Reusing one across frames would need
            // a per-connection buffer and locking around it; revisit only if capture
            // shows up as a bottleneck above ~15fps.
            using var shot = new Bitmap(area.Width, area.Height, PixelFormat.Format32bppRgb);
            using (var g = Graphics.FromImage(shot))
            {
                g.CopyFromScreen(area.X, area.Y, 0, 0, area.Size, CopyPixelOperation.SourceCopy);
                if (drawCursor) CompositeCursor(g, area.X, area.Y);
            }

            using var scaled = Downscale(shot, maxWidth);
            return Encode(scaled ?? shot, quality);
        }
    }

    /// <summary>Returns null when the source already fits, so the caller can encode it directly.</summary>
    private static Bitmap? Downscale(Bitmap source, int maxWidth)
    {
        if (maxWidth <= 0 || source.Width <= maxWidth) return null;

        var width = maxWidth;
        var height = Math.Max(1, (int)Math.Round(source.Height * (double)maxWidth / source.Width));

        var target = new Bitmap(width, height, PixelFormat.Format32bppRgb);
        using var g = Graphics.FromImage(target);
        // Bilinear, not bicubic: at 10+ frames a second the quality difference is
        // invisible and the cost is not.
        g.InterpolationMode = InterpolationMode.Bilinear;
        g.PixelOffsetMode = PixelOffsetMode.HighSpeed;
        g.SmoothingMode = SmoothingMode.None;
        g.DrawImage(source, 0, 0, width, height);
        return target;
    }

    private static byte[] Encode(Bitmap bitmap, int quality)
    {
        using var parameters = new EncoderParameters(1);
        using var qualityParam = new EncoderParameter(Encoder.Quality, (long)quality);
        parameters.Param[0] = qualityParam;

        using var buffer = new MemoryStream(capacity: 128 * 1024);
        bitmap.Save(buffer, JpegCodec, parameters);
        return buffer.ToArray();
    }

    /// <summary>
    /// Draw the current mouse cursor into the capture at its screen position.
    /// Without this the mirror looks frozen while the pointer moves, and the user
    /// has no idea where a click would land.
    /// </summary>
    private static void CompositeCursor(Graphics g, int originX, int originY)
    {
        var info = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
        if (!GetCursorInfo(ref info) || (info.Flags & CursorShowing) == 0 || info.Cursor == 0) return;

        if (!GetIconInfo(info.Cursor, out var icon)) return;
        try
        {
            var hdc = g.GetHdc();
            try
            {
                DrawIconEx(
                    hdc,
                    info.ScreenPos.X - originX - icon.XHotspot,
                    info.ScreenPos.Y - originY - icon.YHotspot,
                    info.Cursor, 0, 0, 0, 0, DiNormal);
            }
            finally
            {
                g.ReleaseHdc(hdc);
            }
        }
        finally
        {
            // GetIconInfo hands back two freshly created bitmaps; leaking them once a
            // frame would exhaust the GDI object quota within minutes of streaming.
            if (icon.MaskBitmap != 0) DeleteObject(icon.MaskBitmap);
            if (icon.ColorBitmap != 0) DeleteObject(icon.ColorBitmap);
        }
    }
}
