package com.portalremote.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.portalremote.ui.theme.DarkColors
import com.portalremote.ui.theme.DarkExtendedColors
import com.portalremote.ui.theme.HudAccent
import com.portalremote.ui.theme.hudColors
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
     * The instrument palette, for **every accent on both faces**.
     *
     * This is the test that makes the accent swappable rather than merely configurable.
     * A colour picker is an invitation to add hues, and the failure it invites is a
     * pretty one that nobody can read — so the check runs the whole matrix rather than
     * the default, and a new [HudAccent] entry is covered the moment it is declared.
     *
     * The readings on these screens are large-and-bold figures or drawn traces, so 3:1
     * is the bar for the accents themselves; anything that is prose — a label, a process
     * name, a filename — is held to the full 4.5:1.
     */
    @Test
    fun everyAccentMeetsAAOnBothFaces() {
        HudAccent.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val hud = hudColors(accent, dark)
                val face = "${accent.name}/${if (dark) "dark" else "light"}"

                // Text, on both surfaces it can land on.
                assertContrast("$face text/background", hud.text, hud.background, 4.5)
                assertContrast("$face text/panel", hud.text, hud.panel, 4.5)
                assertContrast("$face dim/background", hud.textDim, hud.background, 4.5)
                assertContrast("$face dim/panel", hud.textDim, hud.panel, 4.5)

                // The hues that carry a reading.
                assertContrast("$face live/panel", hud.live, hud.panel, 3.0)
                assertContrast("$face second/panel", hud.second, hud.panel, 3.0)
                assertContrast("$face warn/panel", hud.warn, hud.panel, 3.0)
                assertContrast("$face alarm/panel", hud.alarm, hud.panel, 3.0)
                // Accented content also lands on the canvas, not only on a panel — a
                // section label, the trackpad's reticle marks.
                assertContrast("$face live/background", hud.live, hud.background, 3.0)

                // The two accents have to be told apart from *each other*: they are two
                // lines on one chart, and a legend the reader must consult is a failed
                // chart.
                val separation = ratio(hud.live, hud.second)
                assertTrue(
                    separation >= 1.25,
                    "$face live/second: %.2f:1, the two series are indistinguishable".format(separation),
                )

                // A solid accent fill has to carry a label.
                val onFill = if (dark) Color(0xFF04121A) else Color(0xFFFFFFFF)
                assertContrast("$face onAccent/live", onFill, hud.live, 3.0)

                // The panel has to lift off the canvas, and the grid has to stay behind
                // both — it is texture, and texture that competes with a trace is noise.
                assertTrue(
                    ratio(hud.panel, hud.background) >= 1.06,
                    "$face panel/background: indistinguishable",
                )
                assertTrue(
                    ratio(hud.grid, hud.panel) < ratio(hud.textDim, hud.panel),
                    "$face grid is louder than its own labels",
                )
            }
        }
    }

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
