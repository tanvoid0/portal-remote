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
/// ui/theme/Hud.kt; keep both in sync when a token changes.
///
/// These are the **Ice** accent on the phone's neutrals — the default, and the only one
/// this half has. The phone lets the user pick between four accent pairs; the tray app
/// deliberately does not follow that choice. It is a status surface someone glances at
/// a few times a week, the two halves are separately installed and can be different
/// versions, and a window that silently recoloured itself because of a setting on a
/// phone would be a mystery rather than a feature.
/// </summary>
public static class Palette
{
    public static readonly PaletteColors Light = new(
        Accent: Color.FromArgb(0x0E, 0x74, 0x90),
        AccentPressed: Color.FromArgb(0x15, 0x5E, 0x75),
        Bg: Color.FromArgb(0xE4, 0xEA, 0xF2),
        Surface: Color.FromArgb(0xFF, 0xFF, 0xFF),
        SurfaceRaised: Color.FromArgb(0xFF, 0xFF, 0xFF),
        SurfaceMuted: Color.FromArgb(0xD3, 0xDD, 0xE9),
        Border: Color.FromArgb(0xB6, 0xC6, 0xD8),
        BorderStrong: Color.FromArgb(0x64, 0x74, 0x8B),
        TextPrimary: Color.FromArgb(0x0B, 0x12, 0x20),
        TextSecondary: Color.FromArgb(0x4E, 0x5F, 0x79),
        Success: Color.FromArgb(0x16, 0x65, 0x34),
        Danger: Color.FromArgb(0xB9, 0x1C, 0x1C),
        Warning: Color.FromArgb(0x92, 0x40, 0x0E));

    public static readonly PaletteColors Dark = new(
        Accent: Color.FromArgb(0x22, 0xD3, 0xEE),
        AccentPressed: Color.FromArgb(0x67, 0xE8, 0xF9),
        Bg: Color.FromArgb(0x04, 0x07, 0x0E),
        Surface: Color.FromArgb(0x0A, 0x12, 0x20),
        SurfaceRaised: Color.FromArgb(0x0A, 0x12, 0x20),
        SurfaceMuted: Color.FromArgb(0x11, 0x1C, 0x2E),
        Border: Color.FromArgb(0x1D, 0x31, 0x49),
        BorderStrong: Color.FromArgb(0x5C, 0x70, 0x89),
        TextPrimary: Color.FromArgb(0xE6, 0xF1, 0xFF),
        TextSecondary: Color.FromArgb(0x8F, 0xA6, 0xC4),
        Success: Color.FromArgb(0x4A, 0xDE, 0x80),
        Danger: Color.FromArgb(0xF8, 0x71, 0x71),
        Warning: Color.FromArgb(0xFB, 0xBF, 0x24));
}
