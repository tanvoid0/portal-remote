package com.portalremote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalremote.data.SavedHost
import com.portalremote.net.Protocol
import com.portalremote.net.RemoteMonitor
import com.portalremote.net.ScreenApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Capture presets — the mirror trades frame rate against sharpness and there's no
 * setting that's right for both "watch a video play" and "read a line of code", so
 * this exposes the two ends rather than a pile of sliders.
 */
private enum class MirrorPreset(val label: String, val fps: Int, val width: Int, val quality: Int) {
    SMOOTH("Smooth", fps = 15, width = 960, quality = 50),
    SHARP("Sharp", fps = 8, width = 1600, quality = 78),
}

/** Total movement (px) below which a touch counts as a tap rather than a drag. */
private const val TAP_SLOP = 18f

/** Pixels of two-finger drag per wheel notch — same feel as the trackpad. */
private const val SCROLL_PX_PER_NOTCH = 60f

private enum class TouchMode { WAITING, POINT, DRAG, TWO_FINGER }

/** What a two-finger gesture turned out to mean, decided once and then held. */
private enum class TwoFingerIntent { UNDECIDED, ZOOM, PAN, SCROLL }

/** Movement (px) a two-finger gesture needs before it commits to zoom/pan/scroll. */
private const val GESTURE_INTENT_SLOP = 16f

/** 4x is enough to hit a close button on a 3440px-wide desktop; beyond that the
 *  JPEG's own resolution runs out before the zoom does. */
private const val MAX_ZOOM = 4f

/** Keep the zoomed image covering the view — no black gap at the edges. */
internal fun clampPan(pan: Offset, zoom: Float, area: IntSize) = Offset(
    pan.x.coerceIn(area.width * (1 - zoom), 0f),
    pan.y.coerceIn(area.height * (1 - zoom), 0f),
)

/**
 * Where a touch lands on the remote desktop, as 0..1 fractions: the inverse of the
 * pan/zoom transform the image is drawn with. Get this wrong and every click lands
 * somewhere other than where the user pointed, which is both invisible in code
 * review and impossible to notice until something destructive gets clicked.
 */
internal fun mirrorFraction(point: Offset, pan: Offset, zoom: Float, area: IntSize) = Offset(
    (point.x - pan.x) / zoom / area.width,
    (point.y - pan.y) / zoom / area.height,
)

/**
 * Live view of the PC's screen with direct pointing: the cursor goes wherever the
 * finger is, rather than being nudged relative to where it already was as on the
 * trackpad. Tap to click, hold to press-and-drag, two fingers to scroll or
 * right-click — the same gesture vocabulary as [TrackpadScreen], reimplemented
 * against absolute coordinates because "where the finger landed" is the whole point
 * of this screen and a delta would throw it away.
 */
@Composable
fun ScreenScreen(host: SavedHost, send: (JSONObject) -> Unit) {
    val api = remember { ScreenApi() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var monitors by remember { mutableStateOf<List<RemoteMonitor>>(emptyList()) }
    var monitor by remember { mutableStateOf<Int?>(null) }
    var preset by remember { mutableStateOf(MirrorPreset.SMOOTH) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }

    // Held as MutableState rather than `by` delegates: the gesture handler runs
    // outside composition and needs the state objects themselves.
    val zoom = remember { mutableStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(attempt) {
        runCatching { api.monitors(host) }.onSuccess { list ->
            monitors = list
            if (monitor == null) monitor = list.firstOrNull { it.primary }?.index
        }
    }

    // repeatOnLifecycle, not a bare collect: the mirror is the one screen that costs
    // the PC real work per frame, so backgrounding the app must actually stop it
    // rather than leave the server capturing for a phone in someone's pocket.
    LaunchedEffect(monitor, preset, attempt) {
        // A different monitor is a different aspect ratio, so a pan/zoom carried over
        // from the last one would point at the wrong part of the desktop.
        zoom.value = 1f
        pan.value = Offset.Zero
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            error = null
            api.frames(host, monitor, preset.fps, preset.width, preset.quality)
                .conflate()
                .catch { e -> error = e.message ?: "Mirror stopped" }
                .collect { frame = it }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = frame
            when {
                error != null -> MirrorMessage(error!!) { attempt++ }
                bitmap == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Remote screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        // The image fills this box exactly, so a touch position maps
                        // straight onto a fraction of the desktop with no letterbox
                        // maths and no chance of clicking a black bar.
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .clipToBounds()
                        // pointerInput sits *outside* graphicsLayer so touches arrive
                        // in untransformed view coordinates; the zoom is undone
                        // explicitly in pointAt() rather than implicitly here.
                        .pointerInput(monitor) { mirrorGestures(monitor, zoom, pan, send) }
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                            translationX = pan.value.x
                            translationY = pan.value.y
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                )
            }
        }

        // Typing while watching the screen you're typing into is the whole point, so
        // the capture field sits under the frame rather than on its own tab. Same
        // field as KeyboardScreen's — it is a keystroke buffer, not a document.
        if (typing) {
            KeyCaptureField(
                onText = { send(Protocol.text(it)) },
                onTap = { send(Protocol.tap(it)) },
                label = "Typing to the PC",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        MirrorControls(
            monitors = monitors,
            monitor = monitor,
            preset = preset,
            zoom = zoom.value,
            typing = typing,
            onMonitor = { monitor = it },
            onPreset = { preset = it },
            onResetZoom = { zoom.value = 1f; pan.value = Offset.Zero },
            onToggleTyping = { typing = !typing },
        )
    }
}

/**
 * Absolute-pointing gesture handler. Unlike the trackpad's, a touch here starts by
 * moving the cursor under the finger — otherwise the first tap after looking at the
 * screen would click wherever the pointer happened to be left.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mirrorGestures(
    monitor: Int?,
    zoom: MutableState<Float>,
    pan: MutableState<Offset>,
    send: (JSONObject) -> Unit,
) {
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTimeMs = System.currentTimeMillis()
        val area: IntSize = size

        // Undo the zoom transform by hand: a touch arrives in view coordinates, but
        // the desktop fraction the server wants is relative to the *content*.
        fun pointAt(x: Float, y: Float) {
            val fraction = mirrorFraction(Offset(x, y), pan.value, zoom.value, area)
            send(Protocol.mouseMoveNorm(fraction.x, fraction.y, monitor))
        }

        pointAt(down.position.x, down.position.y)

        var totalMove = 0f
        var scrollRemainder = 0f
        var pinchTravel = 0f
        var panTravel = 0f
        var twoFinger = TwoFingerIntent.UNDECIDED
        var mode = TouchMode.WAITING
        var pointerCount = 1

        while (true) {
            val elapsed = System.currentTimeMillis() - downTimeMs
            val waitBudget = (longPressTimeoutMs - elapsed).coerceAtLeast(0)

            val event = if (mode == TouchMode.WAITING) {
                withTimeoutOrNull(waitBudget) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // Held still past the long-press timeout: hold the left button so the
                // finger can now drag a window, a selection, a slider.
                mode = TouchMode.DRAG
                send(Protocol.mouseClick("left", down = true))
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            pointerCount = maxOf(pointerCount, pressed.size)

            if (pressed.isEmpty()) {
                when {
                    mode == TouchMode.DRAG -> send(Protocol.mouseClick("left", down = false))
                    totalMove < TAP_SLOP && pointerCount >= 2 -> send(Protocol.mouseClick("right"))
                    totalMove < TAP_SLOP -> send(Protocol.mouseClick("left"))
                }
                break
            }

            if (pointerCount >= 2 && mode != TouchMode.DRAG) mode = TouchMode.TWO_FINGER

            when (mode) {
                TouchMode.TWO_FINGER -> {
                    event.changes.forEach { it.consume() }
                    if (pressed.size < 2) continue

                    val (a, b) = pressed
                    val spread = (a.position - b.position).getDistance()
                    val previousSpread = (a.previousPosition - b.previousPosition).getDistance()
                    val centroid = (a.position + b.position) / 2f
                    val centroidShift = centroid - (a.previousPosition + b.previousPosition) / 2f

                    // Two-finger movement counts towards totalMove like any other, so
                    // the release path below can tell a two-finger *tap* (right-click)
                    // from the end of a scroll/pinch, which must click nothing.
                    totalMove += centroidShift.getDistance() +
                        kotlin.math.abs(spread - previousSpread)

                    // Decide once what a two-finger gesture means and stick with it —
                    // re-deciding per event makes a pinch judder between zooming and
                    // scrolling, since no two fingers move perfectly symmetrically.
                    if (twoFinger == TwoFingerIntent.UNDECIDED) {
                        pinchTravel += kotlin.math.abs(spread - previousSpread)
                        panTravel += centroidShift.getDistance()
                        twoFinger = when {
                            pinchTravel > GESTURE_INTENT_SLOP -> TwoFingerIntent.ZOOM
                            panTravel > GESTURE_INTENT_SLOP ->
                                // Panning is only meaningful once there's content off
                                // screen; at 1x the same gesture scrolls the desktop.
                                if (zoom.value > 1f) TwoFingerIntent.PAN else TwoFingerIntent.SCROLL
                            else -> TwoFingerIntent.UNDECIDED
                        }
                    }

                    when (twoFinger) {
                        TwoFingerIntent.UNDECIDED -> Unit

                        TwoFingerIntent.ZOOM -> {
                            if (previousSpread <= 0f) continue
                            val target = (zoom.value * (spread / previousSpread))
                                .coerceIn(1f, MAX_ZOOM)
                            // Keep whatever is under the fingers under the fingers.
                            pan.value = clampPan(
                                centroid - (centroid - pan.value) * (target / zoom.value),
                                target, area,
                            )
                            zoom.value = target
                        }

                        TwoFingerIntent.PAN ->
                            pan.value = clampPan(pan.value + centroidShift, zoom.value, area)

                        TwoFingerIntent.SCROLL -> {
                            scrollRemainder += centroidShift.y
                            val notches = (scrollRemainder / SCROLL_PX_PER_NOTCH).toInt()
                            if (notches != 0) {
                                scrollRemainder -= notches * SCROLL_PX_PER_NOTCH
                                // Natural scrolling: drag down -> content follows the finger.
                                send(Protocol.scroll(dy = -notches * 120))
                            }
                        }
                    }
                }

                TouchMode.WAITING, TouchMode.POINT, TouchMode.DRAG -> {
                    val primary: PointerInputChange =
                        event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    totalMove += primary.positionChange().getDistance()
                    primary.consume()

                    if (mode == TouchMode.WAITING) {
                        if (totalMove <= TAP_SLOP) continue
                        mode = TouchMode.POINT
                    }
                    pointAt(primary.position.x, primary.position.y)
                }
            }
        }
    }
}

@Composable
private fun MirrorMessage(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Monitor picker (only when there's a choice to make) plus the quality preset. */
@Composable
private fun MirrorControls(
    monitors: List<RemoteMonitor>,
    monitor: Int?,
    preset: MirrorPreset,
    zoom: Float,
    typing: Boolean,
    onMonitor: (Int?) -> Unit,
    onPreset: (MirrorPreset) -> Unit,
    onResetZoom: () -> Unit,
    onToggleTyping: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (monitors.size > 1) {
            monitors.forEach { display ->
                FilterChip(
                    selected = monitor == display.index,
                    onClick = { onMonitor(display.index) },
                    label = { Text(display.label) },
                )
            }
            FilterChip(
                selected = monitor == ALL_MONITORS,
                onClick = { onMonitor(ALL_MONITORS) },
                label = { Text("All") },
            )
        }
        MirrorPreset.entries.forEach { option ->
            FilterChip(
                selected = preset == option,
                onClick = { onPreset(option) },
                label = { Text(option.label) },
            )
        }
        FilterChip(
            selected = typing,
            onClick = onToggleTyping,
            leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
            label = { Text("Type") },
        )
        // Pinching back to exactly 1x is fiddly, so offer the way out explicitly —
        // and only while there's something to undo.
        if (zoom > 1f) {
            FilterChip(
                selected = false,
                onClick = onResetZoom,
                label = { Text("${"%.1f".format(zoom)}× · reset") },
            )
        }
    }
}

/** The server reads any negative monitor index as "the whole virtual desktop". */
private const val ALL_MONITORS = -1
