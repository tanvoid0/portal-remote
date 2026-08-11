using System.Drawing;

namespace PortalRemote.Theme;

/// <summary>
/// One color scheme's worth of §3 tokens. A record (not a static class) so a
/// runtime theme picker — see <see cref="SystemTheme"/> — can hand back either
/// <see cref="Palette.Light"/> or <see cref="Palette.Dark"/> as a plain value.
/// </summary>
public sealed record PaletteColors(
    Color Accent,
    Color AccentPressed,
    Color Bg,
    Color Surface,
    Color SurfaceRaised,
    Color Border,
    Color TextPrimary,
    Color TextSecondary,
    Color Success,
    Color Danger,
    Color Warning);

/// <summary>
/// Color tokens — see docs/design-system.md §3. Mirrored in the Android app's
/// ui/theme/Theme.kt; keep both in sync when a token changes.
/// </summary>
public static class Palette
{
    public static readonly PaletteColors Light = new(
        Accent: Color.FromArgb(0x25, 0x63, 0xEB),
        AccentPressed: Color.FromArgb(0x1D, 0x4E, 0xD8),
        Bg: Color.FromArgb(0xFA, 0xFA, 0xFA),
        Surface: Color.FromArgb(0xFF, 0xFF, 0xFF),
        SurfaceRaised: Color.FromArgb(0xFF, 0xFF, 0xFF),
        Border: Color.FromArgb(0xE4, 0xE4, 0xE7),
        TextPrimary: Color.FromArgb(0x18, 0x18, 0x1B),
        TextSecondary: Color.FromArgb(0x71, 0x71, 0x7A),
        Success: Color.FromArgb(0x16, 0xA3, 0x4A),
        Danger: Color.FromArgb(0xDC, 0x26, 0x26),
        Warning: Color.FromArgb(0xD9, 0x77, 0x06));

    public static readonly PaletteColors Dark = new(
        Accent: Color.FromArgb(0x60, 0xA5, 0xFA),
        AccentPressed: Color.FromArgb(0x3B, 0x82, 0xF6),
        Bg: Color.FromArgb(0x0F, 0x14, 0x20),
        Surface: Color.FromArgb(0x16, 0x1B, 0x27),
        SurfaceRaised: Color.FromArgb(0x1E, 0x25, 0x34),
        Border: Color.FromArgb(0x26, 0x2D, 0x3D),
        TextPrimary: Color.FromArgb(0xF4, 0xF4, 0xF5),
        TextSecondary: Color.FromArgb(0xA1, 0xA1, 0xAA),
        Success: Color.FromArgb(0x4A, 0xDE, 0x80),
        Danger: Color.FromArgb(0xF8, 0x71, 0x71),
        Warning: Color.FromArgb(0xFB, 0xBF, 0x24));
}
