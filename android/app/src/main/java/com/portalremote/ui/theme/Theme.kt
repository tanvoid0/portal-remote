package com.portalremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

// Design tokens — see docs/design-system.md §3. Mirrored in the desktop app's
// Theme/Palette.cs; keep both in sync when a token changes.
//
// **Everything here is derived from [HudColors].** The palette used to live in this file
// as two independent lists of hex values, with the instrument colours bolted on beside
// them for one screen — which is exactly how the app came to look like two products. Now
// there is one source: Hud.kt defines the light and dark instrument palettes, and this
// file maps them onto the Material3 slots so that a stock `Button`, `Chip` or `Card`
// lands on the same colours a hand-drawn panel does.
//
// The accent is a cyan rather than the blue this app started with. At the luminance
// these screens run at, a mid blue reads as "a link" and a cyan reads as "a signal" —
// and every accented thing in this app is a signal: what is selected, what is live, what
// the value is.

// The accent-derived slots are computed rather than listed, so that adding a colour to
// [HudAccent] needs no edit here at all: a container is the accent laid over the panel at
// a fixed weight, and the text on it is the accent pushed to the far end of its own ramp.
// Hand-picking twelve hex values per accent is how a swappable palette stops being
// swappable after the second one.

/** A filled container behind accented content — a selected chip, a chat bubble. */
private fun container(accent: Color, panel: Color, dark: Boolean) =
    accent.copy(alpha = if (dark) 0.20f else 0.16f).compositeOver(panel)

/**
 * Readable content on [container] — the accent pushed towards the text colour until it
 * clears 4.5:1 against the tinted surface it sits on.
 *
 * On dark that means lifting it towards white; on light, sinking it towards black. The
 * light weight is heavier because a 16%-tinted white is still almost white, so the accent
 * at its own value only reached 4.3:1 — close enough to look fine and to fail the audit.
 */
private fun onContainer(accent: Color, text: Color, dark: Boolean) =
    accent.copy(alpha = if (dark) 0.92f else 0.78f).compositeOver(text)

/** The label on a *solid* accent fill. The accent hues are chosen light on dark faces
 *  and dark on light ones, so this only ever needs the two extremes. */
private fun onAccent(dark: Boolean) = if (dark) Color(0xFF04121A) else Color(0xFFFFFFFF)

/** Tokens with no Material3 ColorScheme slot of their own (§3). Kept as its own type
 *  because `ContrastTest` measures every pair in it, and because the desktop app mirrors
 *  the same names. */
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

/** The extended set, read off an instrument palette so the two can't drift. */
internal fun extendedFrom(hud: HudColors, dark: Boolean) = PortalExtendedColors(
    accent = hud.live,
    // Pressed is the accent moved one step *towards the face it sits on*, which is what
    // "pushed in" looks like on both — brighter on dark, deeper on light.
    accentPressed = hud.live.copy(alpha = 0.75f).compositeOver(if (dark) hud.text else hud.panel),
    surfaceRaised = hud.panel,
    surfaceMuted = hud.sunken,
    border = hud.edge,
    borderStrong = if (dark) Color(0xFF5C7089) else Color(0xFF64748B),
    // Success is status, not brand, and stays put with warn and alarm.
    success = if (dark) Color(0xFF4ADE80) else Color(0xFF166534),
    warning = hud.warn,
)

// internal, not private: `ContrastTest` measures every pair in here against the WCAG
// thresholds §9 records. A palette regresses by one hex digit at a time and no screenshot
// review catches it.
internal val LightExtendedColors = extendedFrom(LightHud, dark = false)
internal val DarkExtendedColors = extendedFrom(DarkHud, dark = true)

private val LocalPortalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * The Material3 scheme for one instrument palette.
 *
 * One function for both faces rather than the usual pair of hand-written schemes: every
 * slot here is either a neutral from [hud] or a derivation of its accent, so writing it
 * twice would only create two places for the same idea to drift apart.
 */
internal fun portalColorScheme(hud: HudColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val on = onAccent(dark)
    val accentContainer = container(hud.live, hud.panel, dark)
    val secondContainer = container(hud.second, hud.panel, dark)
    return base.copy(
        primary = hud.live,
        onPrimary = on,
        primaryContainer = accentContainer,
        onPrimaryContainer = onContainer(hud.live, hud.text, dark),
        inversePrimary = if (dark) hud.live.copy(alpha = 0.6f).compositeOver(hud.background) else hud.live,
        secondary = hud.live,
        onSecondary = on,
        // Selected chips and the trackpad's click buttons live here.
        secondaryContainer = accentContainer,
        onSecondaryContainer = onContainer(hud.live, hud.text, dark),
        tertiary = hud.second,
        onTertiary = on,
        tertiaryContainer = secondContainer,
        onTertiaryContainer = onContainer(hud.second, hud.text, dark),
        background = hud.background,
        onBackground = hud.text,
        surface = hud.panel,
        onSurface = hud.text,
        surfaceVariant = hud.sunken,
        onSurfaceVariant = hud.textDim,
        surfaceDim = hud.background,
        surfaceBright = hud.sunken,
        surfaceContainerLowest = hud.background,
        surfaceContainerLow = hud.panel,
        surfaceContainer = hud.panel,
        surfaceContainerHigh = hud.sunken,
        surfaceContainerHighest = hud.sunken,
        surfaceTint = hud.live,
        outline = if (dark) Color(0xFF5C7089) else Color(0xFF64748B),
        outlineVariant = hud.edge,
        error = hud.alarm,
        onError = if (dark) Color(0xFF2A0A0A) else Color.White,
        errorContainer = if (dark) Color(0xFF4C1D1D) else Color(0xFFFEE2E2),
        onErrorContainer = if (dark) Color(0xFFFECACA) else Color(0xFF7F1D1D),
        inverseSurface = if (dark) LightHud.panel else DarkHud.panel,
        inverseOnSurface = if (dark) LightHud.text else DarkHud.text,
        scrim = Color(0xFF000000),
    )
}

internal val LightColors: ColorScheme = portalColorScheme(LightHud, dark = false)
internal val DarkColors: ColorScheme = portalColorScheme(DarkHud, dark = true)

/**
 * The shape scale — §5.
 *
 * Cut, not rounded, so that every stock Material component in the app is cut from the
 * same 45° as the hand-drawn panels. This is the single highest-leverage line in the
 * theme: it is what makes a `FilterChip` on the mirror, a `Button` in Settings and a
 * readout panel on the dashboard look like parts of one machine, without touching any of
 * their call sites.
 *
 * The cuts are deliberately smaller than the radii they replace. A 45° cut removes about
 * 1.4× the visual mass of a round of the same size, so matching the old numbers would
 * have produced octagons.
 */
internal val PortalShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(5.dp),
    medium = CutCornerShape(8.dp),
    large = CutCornerShape(10.dp),
    extraLarge = CutCornerShape(14.dp),
)

@Composable
fun PortalRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Which accent pair to draw in — the user's choice, persisted in `AppSettings`. */
    accent: HudAccent = HudAccent.ICE,
    content: @Composable () -> Unit,
) {
    // Recomputed only when the face or the accent actually changes; the derivations
    // above are cheap, but they are not free and this sits above the whole app.
    val hud = remember(accent, darkTheme) { hudColors(accent, darkTheme) }
    val extended = remember(hud, darkTheme) { extendedFrom(hud, darkTheme) }
    val scheme = remember(hud, darkTheme) { portalColorScheme(hud, darkTheme) }
    CompositionLocalProvider(
        LocalPortalExtendedColors provides extended,
        LocalHudColors provides hud,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PortalTypography,
            shapes = PortalShapes,
            content = content,
        )
    }
}

/** Access as `PortalRemoteTheme.extendedColors.success` or `PortalRemoteTheme.hud.live`
 *  inside composables. */
object PortalRemoteTheme {
    val extendedColors: PortalExtendedColors
        @Composable get() = LocalPortalExtendedColors.current

    /** The instrument palette for the active theme — see [HudColors]. */
    val hud: HudColors
        @Composable get() = LocalHudColors.current
}
