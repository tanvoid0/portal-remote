package com.portalremote.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalremote.net.DiskUsage
import com.portalremote.net.PcStats
import com.portalremote.net.ProcessLoad
import com.portalremote.net.formatBytes
import com.portalremote.net.formatRate
import com.portalremote.net.formatUptime
import com.portalremote.ui.theme.HudMeter
import com.portalremote.ui.theme.HudPanel
import com.portalremote.ui.theme.HudPulse
import com.portalremote.ui.theme.HudReading
import com.portalremote.ui.theme.HudType
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.glowArc
import com.portalremote.ui.theme.glowDot
import com.portalremote.ui.theme.glowLine
import com.portalremote.ui.theme.hudLoadColor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A live look at the PC itself — the densest use of the instrument kit in the app, and
 * the screen the rest of it was derived from.
 *
 * Everything is driven by [PcStats.fromPush], one sample a second, and only while this
 * screen is on top: it subscribes on composition and releases on disposal, and the PC
 * stops sampling itself when the last watcher leaves.
 *
 * **Motion budget.** docs/design-system.md §2 rule 2 buys silence on the surfaces you
 * drive the PC through; this is the opposite case — a screen whose entire content is a
 * number that changes every second, where a value stepping once a second reads as a
 * broken meter rather than a live one. So readings tween between samples, and exactly one
 * thing loops: the sweep on the CPU dial, which is what makes the panel look *powered*
 * rather than painted. All of it collapses to a snap under the system "remove animations"
 * setting (§9), sweep included.
 */
@Composable
fun StatsScreen(
    stats: PcStats?,
    history: List<PcStats>,
    onWatch: (Boolean) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    // Subscribes on first composition, releases when the tab is left — including by the
    // shell's own tab switch, which recomposes this call site out of the tree.
    DisposableEffect(Unit) {
        onWatch(true)
        onDispose { onWatch(false) }
    }

    // No canvas of its own: the app root already draws the ruled ground, and a second
    // grid over the first is a moiré.
    Box(modifier = Modifier.fillMaxSize()) {
        if (stats == null) {
            LinkPending()
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .padding(bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MachineStrip(stats)
            DialRow(stats)
            LoadHistory(history)
            if (stats.cores.size > 1) CoreMatrix(stats.cores)
            NetworkPanel(stats, history)
            if (stats.disks.isNotEmpty()) StoragePanel(stats.disks)
            if (stats.top.isNotEmpty()) ProcessPanel(stats.top)
        }
    }
}

/** Waiting for the first sample. A spinner would be the app's generic chrome; this is
 *  the panel saying it hasn't been fed yet, in the panel's own language. */
@Composable
private fun LinkPending() {
    val hud = PortalRemoteTheme.hud
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val pulse by rememberInfiniteTransition(label = "link").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "link-pulse",
    )
    val alpha = if (reduced) 0.7f else 0.3f + 0.6f * pulse

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val inset = 4.dp.toPx()
                glowArc(
                    color = hud.live.copy(alpha = alpha),
                    startAngle = -90f,
                    sweepAngle = 300f,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    width = 2.dp.toPx(),
                    glow = hud.glow,
                )
            }
            Text(
                "ESTABLISHING LINK",
                style = HudType.Label,
                color = hud.textDim,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

/** What this machine is, on one line: name, health, uptime. */
@Composable
private fun MachineStrip(stats: PcStats) {
    val hud = PortalRemoteTheme.hud
    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stats.cpuName.uppercase(),
                    style = HudType.Label,
                    color = hud.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${stats.os.uppercase()}  ·  UP ${formatUptime(stats.uptimeMs).uppercase()}",
                    style = HudType.Label.copy(fontSize = 9.sp),
                    color = hud.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HudPulse()
            Text("LIVE", style = HudType.Label, color = hud.live)
        }
    }
}

/** The two dials, side by side and the same height. */
@Composable
private fun DialRow(stats: PcStats) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReactorDial(
            percent = stats.cpu,
            title = "CPU",
            color = hudLoadColor(stats.cpu),
            // The average hides one core pegged while the rest idle, which is exactly
            // what a stuck single-threaded process looks like. The peak is the tell.
            caption = stats.cores.maxOrNull()?.let { "PEAK CORE ${it.toInt()}%" } ?: "",
            sweep = true,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ReactorDial(
            percent = stats.memFraction * 100f,
            title = "MEMORY",
            color = PortalRemoteTheme.hud.second,
            caption = "${formatBytes(stats.memUsed)} / ${formatBytes(stats.memTotal)}".uppercase(),
            sweep = false,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** How many ticks make up a dial's outer ring. Enough that the ring reads as a scale
 *  rather than as a dashed circle, few enough that each one is a distinct mark. */
private const val DIAL_TICKS = 44

/** A 270° sweep with the gap at the bottom — the shape every physical gauge uses,
 *  because the gap is where the label goes. */
private const val DIAL_START = 135f
private const val DIAL_SWEEP = 270f

/**
 * A reading as a lit ring: a tick scale, a bloomed arc over it, and a head that glows.
 *
 * [sweep] adds the slow radar pass — on the CPU dial only. Two things sweeping at
 * different phases stops reading as one instrument and starts reading as a screensaver.
 */
@Composable
private fun ReactorDial(
    percent: Float,
    title: String,
    color: Color,
    caption: String,
    sweep: Boolean,
    modifier: Modifier = Modifier,
) {
    val hud = PortalRemoteTheme.hud
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val value by animateFloatAsState(
        targetValue = (percent / 100f).coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else tween(700, easing = Motion.EaseOut),
        label = "dial",
    )
    // 7 seconds a revolution: slow enough to read as a scan rather than a spinner, and
    // slow enough that a glance never catches it in the same place twice.
    val sweepAngle by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "sweep-angle",
    )

    HudPanel(title = title, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    val ringInset = 16.dp.toPx()
                    val arcInset = ringInset + stroke / 2
                    val arcSize = Size(size.width - arcInset * 2, size.height - arcInset * 2)
                    val arcTopLeft = Offset(arcInset, arcInset)

                    if (sweep && !reduced && hud.glow > 0.5f) drawSweep(sweepAngle, color, arcInset)

                    drawTicks(value, color, hud.grid)

                    // The unlit remainder, so the ring is a scale with a filled part
                    // rather than a line that stops in mid air.
                    drawArc(
                        hud.grid, DIAL_START, DIAL_SWEEP, false, arcTopLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    if (value > 0.004f) {
                        glowArc(color, DIAL_START, DIAL_SWEEP * value, arcTopLeft, arcSize, stroke, hud.glow)
                        val head = DIAL_START + DIAL_SWEEP * value
                        val radius = arcSize.width / 2
                        glowDot(
                            center = Offset(
                                center.x + radius * cos(Math.toRadians(head.toDouble())).toFloat(),
                                center.y + radius * sin(Math.toRadians(head.toDouble())).toFloat(),
                            ),
                            color = color,
                            radius = stroke / 2.4f,
                            glow = hud.glow,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${percent.toInt()}", style = HudType.Dial, color = hud.text)
                    Text("%", style = HudType.Label, color = hud.textDim)
                }
            }
            if (caption.isNotEmpty()) {
                Text(
                    caption,
                    style = HudType.Label.copy(fontSize = 9.sp),
                    color = hud.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** The outer tick scale: lit up to the reading, dim past it. */
private fun DrawScope.drawTicks(value: Float, color: Color, unlit: Color) {
    val outer = size.minDimension / 2
    val inner = outer - 7.dp.toPx()
    val lit = (DIAL_TICKS * value).toInt()
    for (i in 0 until DIAL_TICKS) {
        val angle = Math.toRadians((DIAL_START + DIAL_SWEEP * i / (DIAL_TICKS - 1f)).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val on = i <= lit
        drawLine(
            color = if (on) color.copy(alpha = 0.85f) else unlit,
            start = Offset(center.x + inner * cosA, center.y + inner * sinA),
            end = Offset(center.x + outer * cosA, center.y + outer * sinA),
            strokeWidth = if (on) 2.2.dp.toPx() else 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The radar pass: a short arc with a fading tail, rotated.
 *
 * Built from a dozen segments with falling alpha rather than a sweep gradient —
 * `Brush.sweepGradient` starts at three o'clock and can't be phase-shifted, so rotating
 * it means rotating the whole draw, and the tail has to trail the head whichever way
 * round the dial it is.
 *
 * Dark only. On the light palette a translucent wedge over white is a grey smear, which
 * is why the caller gates on the glow factor rather than this drawing something fainter.
 */
private fun DrawScope.drawSweep(angle: Float, color: Color, inset: Float) {
    val diameter = size.minDimension - inset * 2
    val topLeft = Offset(inset, inset)
    val arcSize = Size(diameter, diameter)
    val segments = 14
    rotate(angle) {
        for (i in 0 until segments) {
            drawArc(
                color = color.copy(alpha = 0.16f * (1f - i / segments.toFloat())),
                startAngle = -i * 5f,
                sweepAngle = -5.2f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
        }
    }
}

/** How many samples the charts have room for — matches `AppViewModel`'s ring buffer, so
 *  the axis is a full minute wide even while it is still filling. */
private const val HISTORY_CAPACITY = 60

/** CPU and memory over the last minute, on one shared 0–100 axis. */
@Composable
private fun LoadHistory(history: List<PcStats>) {
    val hud = PortalRemoteTheme.hud
    HudPanel(
        title = "LAST 60S",
        trailing = { Legend() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(122.dp)) {
            drawChartFrame(hud.grid)
            drawSeries(history.map { it.memFraction * 100f }, hud.second, fill = false, glow = hud.glow)
            drawSeries(history.map { it.cpu }, hud.live, fill = true, glow = hud.glow)
        }
    }
}

@Composable
private fun Legend() {
    val hud = PortalRemoteTheme.hud
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(6.dp)) { drawCircle(hud.live) }
        Text("CPU", style = HudType.Label, color = hud.textDim, modifier = Modifier.padding(start = 5.dp))
        Spacer(Modifier.width(12.dp))
        Canvas(modifier = Modifier.size(6.dp)) { drawCircle(hud.second) }
        Text("MEM", style = HudType.Label, color = hud.textDim, modifier = Modifier.padding(start = 5.dp))
    }
}

/** Quarter rules plus a brighter baseline. Without a scale behind it a line drifting
 *  across an empty box reads as 5% or 50% depending on nothing. */
private fun DrawScope.drawChartFrame(grid: Color) {
    for (i in 0..4) {
        val y = size.height * i / 4f
        drawLine(grid.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), 1f)
    }
    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
}

/**
 * One 0–100 series, right-aligned like every hardware monitor's own graph: the newest
 * sample is at the right edge and a history still filling leaves the left blank, rather
 * than stretching six points across the width — stretching makes the *scale* look like it
 * is changing when only the sample count is.
 */
private fun DrawScope.drawSeries(values: List<Float>, color: Color, fill: Boolean, glow: Float) {
    if (values.size < 2) return
    val step = size.width / (HISTORY_CAPACITY - 1)
    val startIndex = HISTORY_CAPACITY - values.size
    val path = Path()
    var last = Offset.Zero
    values.forEachIndexed { i, v ->
        val x = (startIndex + i) * step
        val y = size.height * (1f - (v / 100f).coerceIn(0f, 1f))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        last = Offset(x, y)
    }

    if (fill) {
        val filled = Path()
        filled.addPath(path)
        filled.lineTo(last.x, size.height)
        filled.lineTo(startIndex * step, size.height)
        filled.close()
        drawPath(filled, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))))
    }
    glowLine(path, color, 2.dp.toPx(), glow)
    // Where the trace is right now. On a chart that scrolls, this is the only point that
    // is a reading rather than a memory.
    glowDot(last, color, 2.5.dp.toPx(), glow)
}

/** How many cells make up one core's bar. */
private const val CORE_CELLS = 14

/** One column per logical processor, each a stack of lit cells — where a whole-machine
 *  average hides a single core pinned while the rest idle. */
@Composable
private fun CoreMatrix(cores: List<Float>) {
    val hud = PortalRemoteTheme.hud
    val reduced = Motion.reducedMotionEnabled(LocalContext.current)
    val loads = cores.map { hudLoadColor(it) }
    HudPanel(
        title = "CORES",
        trailing = { Text("${cores.size}", style = HudType.Label, color = hud.text) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        val animated = cores.map { pct ->
            animateFloatAsState(
                targetValue = (pct / 100f).coerceIn(0f, 1f),
                animationSpec = if (reduced) snap() else tween(450, easing = Motion.EaseOut),
                label = "core",
            ).value
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            val gap = 3.dp.toPx()
            val columnWidth = (size.width - gap * (cores.size - 1)) / cores.size
            val cellGap = 2.dp.toPx()
            val cellHeight = (size.height - cellGap * (CORE_CELLS - 1)) / CORE_CELLS
            animated.forEachIndexed { index, value ->
                val x = index * (columnWidth + gap)
                val litCells = (CORE_CELLS * value + 0.5f).toInt()
                val color = loads[index]
                for (cell in 0 until CORE_CELLS) {
                    val y = size.height - (cell + 1) * cellHeight - cell * cellGap
                    drawRect(
                        color = if (cell < litCells) color else hud.grid,
                        topLeft = Offset(x, y),
                        size = Size(columnWidth, cellHeight),
                    )
                }
                // One bloom pass over the lit stack rather than per cell — the glow is
                // for the column, and 32 columns × 14 cells of it would be 448 extra
                // draws a frame for something nobody can see cell by cell.
                if (litCells > 0 && hud.glow > 0f) {
                    val top = size.height - litCells * (cellHeight + cellGap)
                    drawRect(
                        color = color.copy(alpha = 0.18f * hud.glow),
                        topLeft = Offset(x - gap / 2, top),
                        size = Size(columnWidth + gap, size.height - top),
                    )
                }
            }
        }
    }
}

/** The smallest throughput the network chart will scale to. Below this the link is idle,
 *  and a chart that zooms in on 300 bytes of keepalive is drawing noise as traffic. */
private const val NETWORK_FLOOR_BYTES = 256L * 1024

/**
 * Throughput, as a mirrored trace: down above the centre line, up below it.
 *
 * Two series on one axis would need the reader to tell two colours apart before knowing
 * which direction is which; split about a centre line, the shape says it. The scale is
 * shared between the halves so they stay comparable.
 */
@Composable
private fun NetworkPanel(stats: PcStats, history: List<PcStats>) {
    val hud = PortalRemoteTheme.hud
    HudPanel(title = "NETWORK", modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HudReading("Down", formatRate(stats.netDown).uppercase(), Modifier.weight(1f), hud.live)
            HudReading("Up", formatRate(stats.netUp).uppercase(), Modifier.weight(1f), hud.second)
        }
        Spacer(Modifier.height(12.dp))
        val peak = max(
            history.maxOfOrNull { it.netDown } ?: 0L,
            history.maxOfOrNull { it.netUp } ?: 0L,
        ).coerceAtLeast(NETWORK_FLOOR_BYTES).toFloat()
        Canvas(modifier = Modifier.fillMaxWidth().height(76.dp)) {
            drawLine(hud.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            drawMirrored(history.map { it.netDown / peak }, hud.live, up = true, glow = hud.glow)
            drawMirrored(history.map { it.netUp / peak }, hud.second, up = false, glow = hud.glow)
        }
    }
}

/** One half of the network trace, drawn away from the centre line. */
private fun DrawScope.drawMirrored(values: List<Float>, color: Color, up: Boolean, glow: Float) {
    if (values.size < 2) return
    val mid = size.height / 2
    val step = size.width / (HISTORY_CAPACITY - 1)
    val startIndex = HISTORY_CAPACITY - values.size
    val path = Path()
    val area = Path()
    values.forEachIndexed { i, v ->
        val x = (startIndex + i) * step
        val extent = mid * v.coerceIn(0f, 1f)
        val y = if (up) mid - extent else mid + extent
        if (i == 0) {
            path.moveTo(x, y)
            area.moveTo(x, mid)
            area.lineTo(x, y)
        } else {
            path.lineTo(x, y)
            area.lineTo(x, y)
        }
    }
    area.lineTo((startIndex + values.size - 1) * step, mid)
    area.close()
    drawPath(area, color.copy(alpha = 0.16f))
    glowLine(path, color, 1.5.dp.toPx(), glow)
}

/** Every fixed drive, as a segmented meter each. */
@Composable
private fun StoragePanel(disks: List<DiskUsage>) {
    val hud = PortalRemoteTheme.hud
    HudPanel(title = "STORAGE", modifier = Modifier.fillMaxWidth()) {
        disks.forEachIndexed { index, disk ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    disk.label?.let { "${disk.name} $it" }?.uppercase() ?: disk.name,
                    style = HudType.Mono,
                    color = hud.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatBytes(disk.used)} / ${formatBytes(disk.total)}".uppercase(),
                    style = HudType.Label,
                    color = hud.textDim,
                )
            }
            Spacer(Modifier.height(6.dp))
            HudMeter(disk.fraction, color = hudLoadColor(disk.fraction * 100f))
        }
    }
}

/** The busiest processes — a glance at what is doing this to the machine, ranked by the
 *  PC (`StatMath.TopByCpu`); this only draws the order it was given. */
@Composable
private fun ProcessPanel(processes: List<ProcessLoad>) {
    val hud = PortalRemoteTheme.hud
    // Bars are relative to the busiest one rather than to 100: a process's percentage is
    // against a single core, so on a 32-thread machine the top of the list is routinely
    // past 100 and a fixed scale would peg every row.
    val ceiling = processes.maxOfOrNull { it.cpu }?.coerceAtLeast(1f) ?: 1f
    HudPanel(title = "TOP PROCESSES", modifier = Modifier.fillMaxWidth()) {
        processes.forEachIndexed { index, process ->
            if (index > 0) Spacer(Modifier.height(2.dp))
            val color = hudLoadColor(process.cpu)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            color = color.copy(alpha = 0.13f),
                            size = Size(size.width * (process.cpu / ceiling).coerceIn(0f, 1f), size.height),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        process.name.uppercase(),
                        style = HudType.Mono,
                        color = hud.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatBytes(process.mem).uppercase(),
                        style = HudType.Label,
                        color = hud.textDim,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text("${process.cpu.toInt()}%", style = HudType.Readout, color = color)
                }
            }
        }
    }
}
