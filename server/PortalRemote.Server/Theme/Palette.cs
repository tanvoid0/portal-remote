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
    Color SurfaceMuted,
    Color Border,
    Color BorderStrong,
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
        Bg: Color.FromArgb(0xEA, 0xEE, 0xF6),
        Surface: Color.FromArgb(0xFF, 0xFF, 0xFF),
        SurfaceRaised: Color.FromArgb(0xFF, 0xFF, 0xFF),
        SurfaceMuted: Color.FromArgb(0xDD, 0xE4, 0xF0),
        Border: Color.FromArgb(0xD2, 0xDA, 0xE8),
        BorderStrong: Color.FromArgb(0x74, 0x80, 0x9A),
        TextPrimary: Color.FromArgb(0x0E, 0x15, 0x24),
        TextSecondary: Color.FromArgb(0x55, 0x60, 0x7A),
        Success: Color.FromArgb(0x16, 0x65, 0x34),
        Danger: Color.FromArgb(0xDC, 0x26, 0x26),
        Warning: Color.FromArgb(0x92, 0x40, 0x0E));

    public static readonly PaletteColors Dark = new(
        Accent: Color.FromArgb(0x60, 0xA5, 0xFA),
        AccentPressed: Color.FromArgb(0x3B, 0x82, 0xF6),
        Bg: Color.FromArgb(0x08, 0x0C, 0x18),
        Surface: Color.FromArgb(0x10, 0x17, 0x25),
        SurfaceRaised: Color.FromArgb(0x18, 0x20, 0x2F),
        SurfaceMuted: Color.FromArgb(0x1F, 0x28, 0x39),
        Border: Color.FromArgb(0x2A, 0x34, 0x46),
        BorderStrong: Color.FromArgb(0x64, 0x74, 0x8B),
        TextPrimary: Color.FromArgb(0xEE, 0xF2, 0xF8),
        TextSecondary: Color.FromArgb(0x9A, 0xA7, 0xBD),
        Success: Color.FromArgb(0x4A, 0xDE, 0x80),
        Danger: Color.FromArgb(0xF8, 0x71, 0x71),
        Warning: Color.FromArgb(0xFB, 0xBF, 0x24));
}
