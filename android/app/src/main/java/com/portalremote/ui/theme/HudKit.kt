package com.portalremote.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The components every screen is assembled from — see docs/design-system.md §7.
 *
 * These exist because the alternative is what this app had: fifteen call sites each
 * deciding their own corner radius, border and header treatment, which is how a design
 * system becomes a suggestion. A screen should be able to say "a panel, called this,
 * with these readings in it" and get the app's answer.
 */

/**
 * The ruled ground. Drawn behind a whole screen rather than per panel, so the panels
 * read as objects resting on one surface instead of each carrying its own texture.
 */
fun Modifier.hudGrid(color: Color, cell: Dp = 28.dp): Modifier = drawBehind {
    val step = cell.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

/** The screen canvas: the background colour plus its grid, in one modifier, because
 *  every full-screen surface in the app wants exactly both. */
@Composable
fun Modifier.hudCanvas(): Modifier {
    val hud = PortalRemoteTheme.hud
    // Half strength in both modes, with the difference carried by the two grid colours
    // rather than by two alphas here — otherwise the ruling is a number to re-tune every
    // time either neutral moves.
    return background(hud.background).hudGrid(hud.grid.copy(alpha = 0.5f))
}

/**
 * A panel: chamfered face, hairline edge, and a bracket at the two corners the chamfer
 * left square.
 *
 * The brackets are the trick that makes this cheap. Four sides of border is a box; a
 * short bright mark at the corner reads as something machined, and it costs two lines.
 *
 * [title] gets the §4 label treatment with a rule running out to [trailing], so a header
 * is one line rather than two things floating near each other.
 */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hud = PortalRemoteTheme.hud
    Box(
        modifier = modifier
            .background(hud.panel, HudPanelShape)
            .border(1.dp, hud.edge, HudPanelShape)
            .drawBehind {
                val arm = 14.dp.toPx()
                val weight = 1.5.dp.toPx()
                val mark = hud.bracket.copy(alpha = 0.55f)
                drawLine(mark, Offset(0f, 0f), Offset(arm, 0f), weight)
                drawLine(mark, Offset(0f, 0f), Offset(0f, arm), weight)
                drawLine(mark, Offset(size.width, size.height), Offset(size.width - arm, size.height), weight)
                drawLine(mark, Offset(size.width, size.height), Offset(size.width, size.height - arm), weight)
            }
            .padding(contentPadding),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (title != null) {
                HudSectionHeader(title, trailing)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** A label with a rule running out from it. Used inside panels and, on screens with no
 *  panel of their own, directly on the canvas to name a group of controls. */
@Composable
fun HudSectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    val hud = PortalRemoteTheme.hud
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title.uppercase(), style = HudType.Label, color = hud.textDim)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(1.dp)
                .background(hud.grid),
        )
        trailing?.invoke()
    }
}

/** A named reading: the label above, the figure below, in the hue that means something
 *  about it. The shape every number in this app is shown in. */
@Composable
fun HudReading(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = PortalRemoteTheme.hud.live,
) {
    Column(modifier = modifier) {
        Text(label.uppercase(), style = HudType.Label, color = PortalRemoteTheme.hud.textDim)
        Text(value, style = HudType.Readout, color = color, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** How many segments a meter is divided into. Enough to read as a scale, few enough that
 *  each one is a distinct mark at phone width. */
private const val METER_SEGMENTS = 32

/**
 * A bar that is a row of lit cells rather than a filled rectangle.
 *
 * Segmentation is doing real work, not decoration: a continuous bar at 61% and one at
 * 64% are the same picture, while "lit cells out of 32" is a number you can read off the
 * shape. It is also the one meter treatment that survives both palettes, because the
 * unlit cells stay visible on white where a 12%-alpha track would not.
 */
@Composable
fun HudMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = PortalRemoteTheme.hud.live,
    height: Dp = 9.dp,
) {
    val hud = PortalRemoteTheme.hud
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val value by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else tween(600, easing = Motion.EaseOut),
        label = "meter",
    )
    val track = hud.grid
    val glow = hud.glow
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val gap = 2.dp.toPx()
        val segment = (size.width - gap * (METER_SEGMENTS - 1)) / METER_SEGMENTS
        val lit = (METER_SEGMENTS * value + 0.5f).toInt()
        for (i in 0 until METER_SEGMENTS) {
            drawRect(
                color = if (i < lit) color else track,
                topLeft = Offset(i * (segment + gap), 0f),
                size = Size(segment, size.height),
            )
        }
        if (lit > 0 && glow > 0f) {
            drawRect(
                color = color.copy(alpha = 0.16f * glow),
                topLeft = Offset(0f, -2.dp.toPx()),
                size = Size(lit * (segment + gap), size.height + 4.dp.toPx()),
            )
        }
    }
}

/**
 * A live dot — "this is happening now", which is otherwise a word that has to be written
 * out. The canvas is deliberately larger than the dot: the bloom is three times its
 * radius, and clipped bloom reads as a smudge rather than a light.
 */
@Composable
fun HudPulse(color: Color = PortalRemoteTheme.hud.live, dot: Dp = 7.dp) {
    val glow = PortalRemoteTheme.hud.glow
    Canvas(modifier = Modifier.size(dot * 3)) {
        glowDot(center, color, dot.toPx() / 2, glow)
    }
}

/** How tall one bar's cell stack in [HudEqualizerBars] can grow. */
private const val EQ_MAX_ROWS = 10

/**
 * A row of vertical bars, each its own stack of lit cells — the stats dashboard's core
 * matrix turned sideways for something with a shape across frequency instead of one
 * fraction per column.
 *
 * Colour sweeps from [low] (left) to [high] (right) rather than one flat tint, so bass
 * and treble read as different things without a legend, the same trick the network
 * panel's mirrored up/down trace uses.
 */
@Composable
fun HudEqualizerBars(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    low: Color = PortalRemoteTheme.hud.live,
    high: Color = PortalRemoteTheme.hud.second,
    height: Dp = 40.dp,
) {
    val hud = PortalRemoteTheme.hud
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val animated = levels.mapIndexed { i, v ->
        animateFloatAsState(
            targetValue = v.coerceIn(0f, 1f),
            // Fast and linear, not §6's usual ease-out: this is a live reading that
            // ticks ~20 times a second, not a one-shot transition settling into place.
            animationSpec = if (reduced) snap() else tween(90),
            label = "eq-$i",
        ).value
    }
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (animated.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val cellGap = 2.dp.toPx()
        val cellHeight = 4.dp.toPx()
        val barWidth = (size.width - gap * (animated.size - 1)) / animated.size
        val cells = (size.height / (cellHeight + cellGap)).toInt().coerceIn(1, EQ_MAX_ROWS)
        val span = (animated.size - 1).coerceAtLeast(1)
        animated.forEachIndexed { index, value ->
            val color = lerp(low, high, index / span.toFloat())
            val x = index * (barWidth + gap)
            val lit = (cells * value + 0.5f).toInt()
            for (cell in 0 until cells) {
                val y = size.height - (cell + 1) * cellHeight - cell * cellGap
                drawRect(
                    color = if (cell < lit) color else hud.grid,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, cellHeight),
                )
            }
        }
    }
}
