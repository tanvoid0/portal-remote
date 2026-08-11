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

private val AccentLight = Color(0xFF2563EB)
private val AccentDark = Color(0xFF60A5FA)
private val AccentPressedLight = Color(0xFF1D4ED8)
private val AccentPressedDark = Color(0xFF3B82F6)

private val BgLight = Color(0xFFFAFAFA)
private val BgDark = Color(0xFF0F1420)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFF161B27)
private val SurfaceRaisedLight = Color(0xFFFFFFFF)
private val SurfaceRaisedDark = Color(0xFF1E2534)
private val BorderLight = Color(0xFFE4E4E7)
private val BorderDark = Color(0xFF262D3D)

private val TextPrimaryLight = Color(0xFF18181B)
private val TextPrimaryDark = Color(0xFFF4F4F5)
private val TextSecondaryLight = Color(0xFF71717A)
private val TextSecondaryDark = Color(0xFFA1A1AA)

private val SuccessLight = Color(0xFF16A34A)
private val SuccessDark = Color(0xFF4ADE80)
private val DangerLight = Color(0xFFDC2626)
private val DangerDark = Color(0xFFF87171)
private val WarningLight = Color(0xFFD97706)
private val WarningDark = Color(0xFFFBBF24)

/** Tokens with no Material3 ColorScheme slot of their own (§3). */
data class PortalExtendedColors(
    val accentPressed: Color,
    val surfaceRaised: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
)

private val LightExtendedColors = PortalExtendedColors(
    accentPressed = AccentPressedLight,
    surfaceRaised = SurfaceRaisedLight,
    border = BorderLight,
    success = SuccessLight,
    warning = WarningLight,
)

private val DarkExtendedColors = PortalExtendedColors(
    accentPressed = AccentPressedDark,
    surfaceRaised = SurfaceRaisedDark,
    border = BorderDark,
    success = SuccessDark,
    warning = WarningDark,
)

private val LocalPortalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

private val LightColors: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = SurfaceLight,
    secondary = AccentLight,
    onSecondary = SurfaceLight,
    secondaryContainer = SurfaceRaisedLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = WarningLight,
    onTertiary = SurfaceLight,
    background = BgLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceRaisedLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = SurfaceRaisedLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerHigh = SurfaceRaisedLight,
    surfaceContainerHighest = SurfaceRaisedLight,
    surfaceTint = AccentLight,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = DangerLight,
    onError = SurfaceLight,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = BgDark,
    secondary = AccentDark,
    onSecondary = BgDark,
    secondaryContainer = SurfaceRaisedDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = WarningDark,
    onTertiary = BgDark,
    background = BgDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceRaisedDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = SurfaceRaisedDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerHigh = SurfaceRaisedDark,
    surfaceContainerHighest = SurfaceRaisedDark,
    surfaceTint = AccentDark,
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = DangerDark,
    onError = BgDark,
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
