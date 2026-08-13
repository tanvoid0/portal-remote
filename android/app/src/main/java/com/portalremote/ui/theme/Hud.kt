package com.portalremote.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The instrument language: the palette and drawing primitives every surface in this app
 * is built from — see docs/design-system.md §3.
 *
 * **The form is the identity, not the darkness.** This started as a fixed dark palette
 * for the stats dashboard, which made that one screen look designed and every other
 * screen look like a Material sample next to it. What actually carried the character
 * turned out to be the *geometry* — chamfered panels, bracketed corners, tick scales,
 * segmented meters, monospace figures on a ruled ground — none of which needs a black
 * background. So the palette became a pair, and the same components draw a lit panel on
 * dark and a printed one on light.
 *
 * The two modes are the same instrument under different lighting, which is why they
 * share every dimension, every shape and every type style, and differ only in colour and
 * in [HudColors.glow].
 */
data class HudColors(
    /** The screen canvas the panels rest on. */
    val background: Color,
    /** A panel face. */
    val panel: Color,
    /** A panel face one step in — a nested readout, a key cap, an input. */
    val sunken: Color,
    /** The hairline round a panel. */
    val edge: Color,
    /** Ruled ground, unlit segments, empty track. Texture, never content. */
    val grid: Color,
    /** The live hue: whatever is being measured or is currently true. Also the app's
     *  accent, so "the value" and "the selected thing" are the same colour — there is
     *  only ever one of them on a given surface. */
    val live: Color,
    /** The second series, and the second thing worth telling apart from the first. */
    val second: Color,
    val warn: Color,
    val alarm: Color,
    val text: Color,
    /** Labels, units, anything naming a number rather than being one. */
    val textDim: Color,
    /**
     * How hard the bloom passes render, 0–1.
     *
     * A glow is light *added* to a dark ground; on a light one the same passes are ink
     * spreading, which reads as a printing fault rather than as something lit. So on
     * light this drops to a trace — just enough to thicken a line's shoulder — and the
     * geometry does the work instead.
     */
    val glow: Float,
) {
    /** The mark at a panel's square corners. Bright enough to read as machined, not so
     *  bright it competes with the reading inside. */
    val bracket: Color get() = live
}

/**
 * The two accent hues, as a swappable set.
 *
 * Separated from the neutrals because they are the only part of the palette that is a
 * *taste*: everything else — how far a panel sits from its canvas, how quiet the grid
 * is, what "alarm" means — is a legibility decision this app has already made, and is
 * not the user's to break. Swapping an accent can't produce an unreadable screen,
 * because each pair below is picked at a luminance that clears §9 on both faces, and
 * `ContrastTest` checks every one of them.
 *
 * [warn] and [alarm] deliberately do not vary. They are status, not brand: amber has to
 * keep meaning "getting full" and red "this is wrong", whichever accent is on. That also
 * constrains the choices here — no accent may be an amber or a red, or the machine would
 * have two vocabularies for the same colour.
 */
enum class HudAccent(
    val label: String,
    internal val darkLive: Color,
    internal val darkSecond: Color,
    internal val lightLive: Color,
    internal val lightSecond: Color,
) {
    /** The default. Cyan reads as a signal where a mid blue reads as a link, and the
     *  violet beside it is far enough to separate two traces on one chart. */
    ICE("Ice", Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFF0E7490), Color(0xFF6D28D9)),

    /**
     * Magenta over blue. The loudest pair here, and the one that most reads as a screen
     * from a film rather than an instrument.
     *
     * The blue is a step deeper than it looks like it wants to be. Pink and sky at the
     * same weight are a striking pair and a useless one: they sit within a few percent
     * of each other in *luminance*, so two traces drawn in them are one shape to anyone
     * who doesn't separate hue — which `ContrastTest` catches, and which is the reason
     * the second hue here is darker than the first rather than merely different.
     */
    NEON("Neon", Color(0xFFF472B6), Color(0xFF0284C7), Color(0xFFBE185D), Color(0xFF0284C7)),

    /** Sky blue and teal — the calmest, and the closest to the blue this app shipped
     *  with before the kit existed. The light teal is a step brighter than its blue for
     *  the same reason [NEON]'s second is a step darker: two 700-weights are the same
     *  grey once the hue is taken away. */
    DEEP("Deep", Color(0xFF60A5FA), Color(0xFF2DD4BF), Color(0xFF1D4ED8), Color(0xFF0D9488)),

    /** Almost no hue at all: steel, with a single warm second. For a machine that
     *  should look like a tool rather than a toy. */
    STEEL("Steel", Color(0xFFCBD5E1), Color(0xFF94A3B8), Color(0xFF334155), Color(0xFF64748B));

    companion object {
        /** Parse a stored name; anything unrecognised falls back to the default rather
         *  than crashing an app whose only fault is being older than the setting. */
        fun from(name: String?): HudAccent = entries.firstOrNull { it.name == name } ?: ICE
    }
}

/** The dark neutrals: lit type on a black face. */
private val DarkNeutrals = HudColors(
    background = Color(0xFF04070E),
    panel = Color(0xFF0A1220),
    sunken = Color(0xFF111C2E),
    edge = Color(0xFF1D3149),
    grid = Color(0xFF17293F),
    live = Color.Unspecified,
    second = Color.Unspecified,
    warn = Color(0xFFFBBF24),
    alarm = Color(0xFFF87171),
    text = Color(0xFFE6F1FF),
    textDim = Color(0xFF8FA6C4),
    glow = 1f,
)

/** The palette for one accent on one face. */
fun hudColors(accent: HudAccent, dark: Boolean): HudColors {
    val base = if (dark) DarkNeutrals else LightNeutrals
    return base.copy(
        live = if (dark) accent.darkLive else accent.lightLive,
        second = if (dark) accent.darkSecond else accent.lightSecond,
    )
}

/** The default palettes, for previews, tests and anything composed outside the theme. */
val DarkHud = hudColors(HudAccent.ICE, dark = true)

/**
 * Light: the same instrument as a technical drawing — ink on paper, with the panels as
 * white plates on a cool ground and the ruled grid still under them.
 *
 * Every hue is the dark one walked down the ramp until it clears §9's thresholds on
 * white: cyan-400 → cyan-700, violet-400 → violet-700, amber-400 → amber-800. The look
 * survives because the *relationships* survive — live is still the brightest thing in a
 * panel, grid is still quieter than a label, warn and alarm still escalate.
 */
private val LightNeutrals = HudColors(
    // The neutrals sit further apart than a light theme usually needs them to. On dark,
    // a panel separates from the canvas by *emitting* — it is the brighter thing, and a
    // few percent is plenty. On light there is no such trick: white on near-white is one
    // undifferentiated field, and the first light build of this kit read as a single pale
    // sheet with some cyan on it. So the canvas is pushed down, the panel stays pure
    // white, and the edge is dark enough to be a drawn line rather than a suggestion.
    background = Color(0xFFE4EAF2),
    panel = Color(0xFFFFFFFF),
    sunken = Color(0xFFD3DDE9),
    edge = Color(0xFFB6C6D8),
    grid = Color(0xFFB9CBDD),
    live = Color.Unspecified,
    second = Color.Unspecified,
    warn = Color(0xFF92400E),
    alarm = Color(0xFFB91C1C),
    text = Color(0xFF0B1220),
    textDim = Color(0xFF4E5F79),
    glow = 0.22f,
)

val LightHud = hudColors(HudAccent.ICE, dark = false)

internal val LocalHudColors = staticCompositionLocalOf { DarkHud }

/**
 * Instrument type. Shared by both modes, because a typeface doesn't change when you turn
 * the lights on.
 */
object HudType {
    /** Names a reading. Uppercase at the call site and widely tracked — at this size
     *  tracking is what separates a label from the value under it without another
     *  colour. */
    val Label = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )

    /** A number that changes. Monospace so the digits don't reflow under the eye —
     *  proportional figures make a steady reading look like it is twitching. */
    val Readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )

    /** The big dial figures. */
    val Dial = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
    )

    /** Row text that is data but not a number — a process name, a drive label, a file. */
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    )
}

/**
 * Panels are chamfered, not rounded, and on two corners only — four reads as a stop
 * sign. This is the single most identifying shape in the app; the Material shape scale
 * (§5) is cut from the same 45° so a Button and a panel look related.
 */
val HudPanelShape = CutCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 0.dp)

/** Loaded → warning → alarm, at the thresholds the rest of the app uses to decide that
 *  something needs attention. */
@Composable
fun hudLoadColor(percent: Float): Color {
    val hud = PortalRemoteTheme.hud
    return when {
        percent >= 90f -> hud.alarm
        percent >= 70f -> hud.warn
        else -> hud.live
    }
}

/**
 * Bloom, faked with three passes.
 *
 * A glow is a blur, and blurring on Android below API 31 means a `RenderEffect` that
 * isn't there — so the same geometry is stroked three times: wide and nearly
 * transparent, then narrower and brighter, then the line itself. Two extra draw calls,
 * identical on every device the app runs on.
 *
 * [glow] scales the two outer passes so the light palette can drop them to a trace; at 0
 * this is exactly one clean stroke.
 */
fun DrawScope.glowLine(path: Path, color: Color, width: Float, glow: Float) {
    if (glow > 0f) {
        drawPath(path, color.copy(alpha = 0.07f * glow), style = Stroke(width * 4.5f, cap = StrokeCap.Round))
        drawPath(path, color.copy(alpha = 0.16f * glow), style = Stroke(width * 2.2f, cap = StrokeCap.Round))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

/** [glowLine], for an arc. */
fun DrawScope.glowArc(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    topLeft: Offset,
    size: Size,
    width: Float,
    glow: Float,
) {
    if (glow > 0f) {
        drawArc(
            color.copy(alpha = 0.07f * glow), startAngle, sweepAngle, false, topLeft, size,
            style = Stroke(width * 3f, cap = StrokeCap.Round),
        )
        drawArc(
            color.copy(alpha = 0.18f * glow), startAngle, sweepAngle, false, topLeft, size,
            style = Stroke(width * 1.8f, cap = StrokeCap.Round),
        )
    }
    drawArc(color, startAngle, sweepAngle, false, topLeft, size, style = Stroke(width, cap = StrokeCap.Round))
}

/** A lit point — the head of a sweep, the newest sample on a chart. */
fun DrawScope.glowDot(center: Offset, color: Color, radius: Float, glow: Float) {
    if (glow > 0f) {
        drawCircle(color.copy(alpha = 0.14f * glow), radius * 3.5f, center)
        drawCircle(color.copy(alpha = 0.30f * glow), radius * 2f, center)
    }
    drawCircle(color, radius, center)
}
