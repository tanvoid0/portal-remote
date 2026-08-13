package com.portalremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Design tokens — see docs/design-system.md §3. Mirrored in the desktop app's
// Theme/Palette.cs; keep both in sync when a token changes.
//
// The neutrals are blue-tinted rather than pure grey (§3): the accent is a blue, and a
// neutral ramp with no hue in it reads as two unrelated palettes stacked — which is what
// "plain" was. Every surface here shares the accent's hue at 3–8% saturation, so the
// greys look like the shadow the blue casts rather than like default system chrome.

private val AccentLight = Color(0xFF2563EB)
private val AccentDark = Color(0xFF60A5FA)
private val AccentPressedLight = Color(0xFF1D4ED8)
private val AccentPressedDark = Color(0xFF3B82F6)

// `primaryContainer`/`onPrimaryContainer` have no entry in §3's token table, and left
// unset here they don't inherit the accent above — Compose's lightColorScheme()/
// darkColorScheme() default every unspecified slot to Material's own baseline palette,
// a fixed purple with no relation to the `primary` passed in. That is what the user
// chat bubble (this screen, ShareScreen's chat) rendered as until now: an off-brand
// color the §9 contrast audit never covered, because it isn't in §3 either. Every
// remaining container/inverse slot is set below for the same reason — a Snackbar
// (`inverseSurface`) and the TV remote's power button (`errorContainer`) were both
// still drawing Material baseline hues on an otherwise blue-tinted surface.
private val PrimaryContainerLight = Color(0xFFDBE7FF)
private val PrimaryContainerDark = Color(0xFF14264A)
private val OnPrimaryContainerLight = Color(0xFF16337A)
private val OnPrimaryContainerDark = Color(0xFFCFE0FF)

// A second hue, so the palette has somewhere to go that isn't the accent. Deliberately
// not wired to chips or nav (those stay accent-blue — one brand color for "selected"),
// it's here for badges and accent illustration; violet sits far enough from the blue to
// read as a different thing and close enough not to fight it.
private val TertiaryLight = Color(0xFF6D28D9)
private val TertiaryDark = Color(0xFFA78BFA)
private val TertiaryContainerLight = Color(0xFFEDE4FF)
private val TertiaryContainerDark = Color(0xFF34246B)
private val OnTertiaryContainerLight = Color(0xFF3A1C86)
private val OnTertiaryContainerDark = Color(0xFFE5DAFF)

private val BgLight = Color(0xFFEAEEF6)
private val BgDark = Color(0xFF080C18)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFF101725)
private val SurfaceRaisedLight = Color(0xFFFFFFFF)
private val SurfaceRaisedDark = Color(0xFF18202F)

// Sunken/filled fill, the token this palette was missing: key faces, the trackpad pad,
// input boxes, the PC's chat bubble. Light mode had no such value — `surfaceVariant` was
// mapped to `surface-raised`, i.e. pure white on a white card, so every one of those
// surfaces was invisible. It has to differ from *both* `surface` and `bg`, since things
// filled with it sit on either.
private val SurfaceMutedLight = Color(0xFFDDE4F0)
private val SurfaceMutedDark = Color(0xFF1F2839)

// Two edge tokens, not one. `border` is a hairline divider (decorative, deliberately
// quiet); `borderStrong` is the boundary of a *control* — an input box, an outlined
// button, the trackpad — which WCAG 1.4.11 puts at 3:1 against its background, a bar
// the old single `border` value missed by more than 2×. They map onto Material's own
// `outlineVariant`/`outline` split, which already means exactly this.
private val BorderLight = Color(0xFFD2DAE8)
private val BorderDark = Color(0xFF2A3446)
private val BorderStrongLight = Color(0xFF74809A)
private val BorderStrongDark = Color(0xFF64748B)

private val TextPrimaryLight = Color(0xFF0E1524)
private val TextPrimaryDark = Color(0xFFEEF2F8)
private val TextSecondaryLight = Color(0xFF55607A)
private val TextSecondaryDark = Color(0xFF9AA7BD)

// Light `success`/`warning` are a shade darker than the §3 table originally carried
// (`#16A34A`/`#D97706`): both measured 3.0–3.3:1 on light surfaces, which §9 had to
// write a standing "never set text in these" constraint around. At 700-weight they
// clear 5:1 and the constraint goes away.
private val SuccessLight = Color(0xFF166534)
private val SuccessDark = Color(0xFF4ADE80)
private val DangerLight = Color(0xFFDC2626)
private val DangerDark = Color(0xFFF87171)
private val WarningLight = Color(0xFF92400E)
private val WarningDark = Color(0xFFFBBF24)

/** Tokens with no Material3 ColorScheme slot of their own (§3). */
data class PortalExtendedColors(
    val accent: Color,
    val accentPressed: Color,
    val surfaceRaised: Color,
    val surfaceMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val success: Color,
    val warning: Color,
)

// internal, not private: `ContrastTest` measures every pair in here against the WCAG
// thresholds §9 records. A palette regresses by one hex digit at a time and no screenshot
// review catches it.
internal val LightExtendedColors = PortalExtendedColors(
    accent = AccentLight,
    accentPressed = AccentPressedLight,
    surfaceRaised = SurfaceRaisedLight,
    surfaceMuted = SurfaceMutedLight,
    border = BorderLight,
    borderStrong = BorderStrongLight,
    success = SuccessLight,
    warning = WarningLight,
)

internal val DarkExtendedColors = PortalExtendedColors(
    accent = AccentDark,
    accentPressed = AccentPressedDark,
    surfaceRaised = SurfaceRaisedDark,
    surfaceMuted = SurfaceMutedDark,
    border = BorderDark,
    borderStrong = BorderStrongDark,
    success = SuccessDark,
    warning = WarningDark,
)

private val LocalPortalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

internal val LightColors: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = Color(0xFF93B4FF),
    secondary = AccentPressedLight,
    onSecondary = Color.White,
    // Selected chips and the trackpad's click buttons live here. It was `surface-raised`
    // — white on white — so "selected" was carried by the check icon alone.
    secondaryContainer = Color(0xFFD6E4FE),
    onSecondaryContainer = OnPrimaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BgLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceMutedLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceDim = Color(0xFFDDE3EE),
    surfaceBright = SurfaceLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = Color(0xFFF4F7FC),
    surfaceContainerHigh = Color(0xFFEDF1F9),
    surfaceContainerHighest = Color(0xFFE5EBF5),
    surfaceTint = AccentLight,
    outline = BorderStrongLight,
    outlineVariant = BorderLight,
    error = DangerLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF1B2233),
    inverseOnSurface = BgLight,
    scrim = Color(0xFF0A0F1C),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = AccentPressedLight,
    secondary = AccentDark,
    onSecondary = Color(0xFF0A1A33),
    secondaryContainer = Color(0xFF24406E),
    onSecondaryContainer = Color(0xFFDCE9FF),
    tertiary = TertiaryDark,
    onTertiary = Color(0xFF2A1065),
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BgDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceDim = BgDark,
    surfaceBright = Color(0xFF232D40),
    surfaceContainerLowest = Color(0xFF05080F),
    surfaceContainerLow = Color(0xFF0E1421),
    surfaceContainer = Color(0xFF141C2B),
    surfaceContainerHigh = Color(0xFF1A2333),
    surfaceContainerHighest = Color(0xFF222C3E),
    surfaceTint = AccentDark,
    outline = BorderStrongDark,
    outlineVariant = BorderDark,
    error = DangerDark,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Color(0xFFE9EDF5),
    inverseOnSurface = SurfaceDark,
    scrim = Color(0xFF000000),
)

@Composable
fun PortalRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalPortalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PortalTypography,
            content = content,
        )
    }
}

/** Access as `PortalRemoteTheme.extendedColors.success` etc. inside composables. */
object PortalRemoteTheme {
    val extendedColors: PortalExtendedColors
        @Composable get() = LocalPortalExtendedColors.current
}
