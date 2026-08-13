package com.portalremote.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.portalremote.ui.theme.DarkColors
import com.portalremote.ui.theme.DarkExtendedColors
import com.portalremote.ui.theme.LightColors
import com.portalremote.ui.theme.LightExtendedColors
import com.portalremote.ui.theme.PortalExtendedColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The §9 contrast audit, as something that runs.
 *
 * It was a table of hand-computed ratios in docs/design-system.md, which is exactly the
 * kind of check that is right on the day it's written and silently wrong two palette
 * revisions later — the fault this file exists to catch is a token edited by one hex
 * digit, which no screenshot review notices and no other test touches.
 *
 * Thresholds are WCAG 2.1: 4.5:1 for body text, 3:1 for large text and for the visual
 * boundary of a control (1.4.11).
 */
class ContrastTest {

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG 2.1 relative luminance. Both colors must be opaque — every token is. */
    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Double) {
        val actual = ratio(fg, bg)
        assertTrue(
            actual >= min,
            "$label: %.2f:1, needs %.1f:1".format(actual, min),
        )
    }

    private fun checkTheme(name: String, s: ColorScheme, x: PortalExtendedColors) {
        // Body text on every surface it can land on.
        assertContrast("$name onSurface/surface", s.onSurface, s.surface, 4.5)
        assertContrast("$name onSurface/surfaceRaised", s.onSurface, x.surfaceRaised, 4.5)
        assertContrast("$name onSurface/surfaceMuted", s.onSurface, x.surfaceMuted, 4.5)
        assertContrast("$name onBackground/background", s.onBackground, s.background, 4.5)
        assertContrast("$name onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, 4.5)
        assertContrast("$name onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant, 4.5)
        assertContrast("$name onSurfaceVariant/background", s.onSurfaceVariant, s.background, 4.5)

        // Filled controls: the label against its own container.
        assertContrast("$name onPrimary/primary", s.onPrimary, s.primary, 4.5)
        assertContrast("$name onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
        assertContrast("$name onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
        assertContrast("$name onTertiary/tertiary", s.onTertiary, s.tertiary, 4.5)
        assertContrast("$name onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
        assertContrast("$name onError/error", s.onError, s.error, 4.5)
        assertContrast("$name onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
        assertContrast("$name inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface, 4.5)

        // Text set in an accent or a status color — the pairs §9 used to forbid.
        assertContrast("$name primary/surface", s.primary, s.surface, 4.5)
        assertContrast("$name error/surface", s.error, s.surface, 4.5)
        assertContrast("$name success/surface", x.success, s.surface, 4.5)
        assertContrast("$name success/background", x.success, s.background, 4.5)
        assertContrast("$name warning/surface", x.warning, s.surface, 4.5)
        assertContrast("$name warning/background", x.warning, s.background, 4.5)

        // 1.4.11: the boundary of a control against what's behind it. This is the one
        // the old single `border` token failed on every surface in both themes.
        assertContrast("$name outline/surface", s.outline, s.surface, 3.0)
        assertContrast("$name outline/background", s.outline, s.background, 3.0)
        assertContrast("$name outline/surfaceRaised", s.outline, x.surfaceRaised, 3.0)
        assertContrast("$name outline/surfaceMuted", s.outline, x.surfaceMuted, 3.0)
        assertContrast("$name borderStrong/surface", x.borderStrong, s.surface, 3.0)
        assertContrast("$name borderStrong/surfaceMuted", x.borderStrong, x.surfaceMuted, 3.0)

        // The status dot is graphical, so 3:1 — but it has to clear it on the two
        // surfaces it is actually drawn on (the title row, the desktop's status card).
        assertContrast("$name success/surfaceRaised", x.success, x.surfaceRaised, 3.0)
        assertContrast("$name error/surfaceRaised", s.error, x.surfaceRaised, 3.0)
        assertContrast("$name warning/surfaceRaised", x.warning, x.surfaceRaised, 3.0)
    }

    @Test
    fun lightThemeMeetsAA() = checkTheme("light", LightColors, LightExtendedColors)

    @Test
    fun darkThemeMeetsAA() = checkTheme("dark", DarkColors, DarkExtendedColors)

    /**
     * A surface has to differ from the one it sits on. Not a WCAG rule — 1.4.11 exempts
     * a boundary that is "not required to identify the component" — but it is the
     * defect this palette pass fixed: every light-mode surface token was `#FFFFFF`, so
     * cards, the trackpad and selected chips were shapes with no edge and no fill.
     */
    @Test
    fun surfacesAreDistinguishable() {
        listOf(
            Triple("light", LightColors, LightExtendedColors),
            Triple("dark", DarkColors, DarkExtendedColors),
        ).forEach { (name, s, x) ->
            listOf(
                "surface/background" to (s.surface to s.background),
                "surfaceMuted/surface" to (x.surfaceMuted to s.surface),
                "surfaceMuted/background" to (x.surfaceMuted to s.background),
                "secondaryContainer/surface" to (s.secondaryContainer to s.surface),
                "primaryContainer/surface" to (s.primaryContainer to s.surface),
            ).forEach { (label, pair) ->
                val actual = ratio(pair.first, pair.second)
                assertTrue(actual >= 1.06, "$name $label: %.3f:1, indistinguishable".format(actual))
            }
        }
    }
}
