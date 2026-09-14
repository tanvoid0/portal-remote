package com.portalremote.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalremote.data.AppSettings
import com.portalremote.data.SavedHost
import com.portalremote.net.Protocol
import com.portalremote.net.RemoteMonitor
import com.portalremote.net.RemoteWindow
import com.portalremote.net.ScreenApi
import com.portalremote.ui.theme.AutoRotate
import com.portalremote.ui.theme.Haptics
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.PortalRemoteTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.max

/**
 * Capture presets — the mirror trades frame rate against sharpness and there's no
 * setting that's right for both "watch a video play" and "read a line of code", so
 * this exposes the ends rather than a pile of sliders.
 */
/** [icon] rides along on the enum so the preset reads the same on the mirror's chip
 *  row and in Settings — one trade (frames against detail), one set of glyphs. */
enum class MirrorPreset(
    val label: String,
    val icon: ImageVector,
    val fps: Int,
    val width: Int,
    val quality: Int,
) {
    SMOOTH("Smooth", Icons.Filled.Speed, fps = 15, width = 960, quality = 50),
    SHARP("Sharp", Icons.Filled.Hd, fps = 8, width = 1600, quality = 78),

    /** Everything the server will give: its own ceilings are 30fps and 3840px. What
     *  actually arrives is lower — BitBlt + JPEG of a wide monitor runs ~55ms, so the
     *  PC's capture cost caps this near 18fps regardless of what's asked for. */
    MAX("Max", Icons.Filled.HighQuality, fps = 30, width = 1920, quality = 85),
    ;

    companion object {
        /** Falls back to the default rather than throwing: the stored name comes from
         *  a previous install's enum, which may not have had this entry. */
        fun from(name: String): MirrorPreset = entries.firstOrNull { it.name == name } ?: SMOOTH
    }
}

// Tap slop is shared with the trackpad (see `PadGesture.kt`) — the same finger doing
// the same thing on either surface has to mean the same thing.

private enum class TouchMode { WAITING, POINT, DRAG, TWO_FINGER, THREE_FINGER, RAIL }

/** Width of the right-edge scroll rail — same figure as the trackpad's, so a drag
 *  there covers the same distance per notch on both surfaces. Only live once zoomed:
 *  at 1x the same touch has to be free to click whatever's actually drawn at that
 *  edge, and two fingers already scroll there without a rail. Zoomed in, 2-finger
 *  is spent on panning, so scrolling needs a way in that isn't a finger count. */
/** The letterbox around the mirrored frame. Not quite #000: a true black next to an
 *  OLED-off pixel makes the frame's edge disappear on the phones that have one. */
private val MIRROR_MOUNT = Color(0xFF07090C)

private val MIRROR_RAIL_WIDTH = 44.dp

/** What a two-finger gesture turned out to mean, decided once and then held. */
internal enum class TwoFingerIntent { UNDECIDED, ZOOM, PAN, SCROLL, SWIPE }

/** Movement (px) a two-finger gesture needs before it commits to zoom/pan/scroll. */
private const val GESTURE_INTENT_SLOP = 16f

/**
 * Decides what a two-finger gesture means from its accumulated travel so far. Pure
 * and top-level so the decision can be checked without two simulated fingers — see
 * `TwoFingerIntentTest`.
 *
 * Whichever of [pinchTravel]/[panTravel] crosses [GESTURE_INTENT_SLOP] *first* used
 * to win outright — but a real vertical swipe still jitters the finger spread a
 * little as it goes, enough to cross the slop on pinch before the deliberate
 * pan/scroll signal did, locking the whole gesture into a zoom that never visibly
 * moves (spread barely changed, so the target clamps back to ~1x and nothing looks
 * like it happened). Comparing which total is *larger*, once either has crossed the
 * slop, means noise can only win if it's actually the bigger signal.
 */
internal fun classifyTwoFingerIntent(
    pinchTravel: Float,
    panTravel: Float,
    zoomed: Boolean,
    /** The fingers are travelling more sideways than up/down. At 1x that is the
     *  trackpad's back/forward swipe rather than a scroll — the same two fingers going
     *  the same way have to mean the same thing on both surfaces, which is the whole
     *  reason this parameter exists. Zoomed in it changes nothing: panning the picture
     *  owns both axes. */
    horizontal: Boolean = false,
): TwoFingerIntent = when {
    pinchTravel < GESTURE_INTENT_SLOP && panTravel < GESTURE_INTENT_SLOP -> TwoFingerIntent.UNDECIDED
    pinchTravel > panTravel -> TwoFingerIntent.ZOOM
    // Panning is only meaningful once there's content off screen; at 1x the same
    // gesture scrolls the desktop.
    zoomed -> TwoFingerIntent.PAN
    horizontal -> TwoFingerIntent.SWIPE
    else -> TwoFingerIntent.SCROLL
}

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScreen(
    host: SavedHost,
    settings: AppSettings,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onPresetChange: (MirrorPreset) -> Unit,
    send: (JSONObject) -> Unit,
) {
    val api = remember { ScreenApi() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    // Same wheel as the trackpad's, so a notch is the same distance on both surfaces —
    // and momentum comes with it. Horizontal is live here: nothing on the mirror
    // competes for a two-finger sideways drag at 1x.
    val wheel = remember(settings.naturalScroll, haptics) {
        WheelScroll(scope) { dx, dy, coasting ->
            // Same sign correction as the trackpad's: a positive wheel delta scrolls up
            // on Windows, which is content following a finger that went down.
            val direction = if (settings.naturalScroll) 1 else -1
            send(Protocol.scroll(dy = direction * dy * WHEEL_DELTA, dx = direction * dx * WHEEL_DELTA))
            if (!coasting) haptics.tick()
        }
    }

    var monitors by remember { mutableStateOf<List<RemoteMonitor>>(emptyList()) }
    var monitor by remember { mutableStateOf<Int?>(null) }
    // One app instead of a whole display. Wins over [monitor] while set; the whole
    // RemoteWindow rather than its handle so the chip can say which app it is.
    var window by remember { mutableStateOf<RemoteWindow?>(null) }
    var pickingWindow by remember { mutableStateOf(false) }
    // Keyed on the stored name so the persisted preset also lands when it arrives
    // after first composition (DataStore reads are async).
    var preset by remember(settings.mirrorPreset) {
        mutableStateOf(MirrorPreset.from(settings.mirrorPreset))
    }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    // Pinch-zoom is the answer to "I can't read that", but nothing on a bare video
    // frame says so. One line, gone the moment a finger lands — see the same pattern
    // on the trackpad.
    var touched by remember { mutableStateOf(false) }
    // Raw touch passthrough instead of the emulated cursor — see
    // touchPassthroughGestures. Off by default: it's the PC's own touch-aware apps
    // that make sense of the gesture in this mode, which most of what a mouse-driven
    // desktop shows isn't.
    var touchMode by remember { mutableStateOf(false) }

    // Held as MutableState rather than `by` delegates: the gesture handler runs
    // outside composition and needs the state objects themselves.
    val zoom = remember { mutableStateOf(1f) }
    val pan = remember { mutableStateOf(Offset.Zero) }

    // The app is locked portrait (see AndroidManifest) everywhere except here: this is
    // the one screen whose content is itself a wide picture. Unconditional because this
    // composable is only in the tree while the Screen tab is the visible one, which is
    // exactly the lifetime the unlock should have.
    AutoRotate()

    // Turning the phone sideways in front of a desktop is a request for the whole
    // display, so it gets the whole display: in landscape the title row, the nav
    // capsule and the chip strip all step out (the same full screen the chip toggles),
    // and turning back puts them there again. A landscape mirror inside the shell is
    // the worst of both — roughly half of a ~400dp-tall window spent on chrome around
    // a picture that just got wider.
    //
    // No guard against the first pass cancelling a full screen entered by hand: while
    // full screen the nav bar is hidden, so this tab cannot be left and re-entered
    // without leaving it first.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape) { onFullscreen(landscape) }

    // Touch mode forwards fingers 1:1 — a leftover pinch-zoom from before the switch
    // would offset every contact from where the finger actually is.
    LaunchedEffect(touchMode) {
        if (touchMode) { zoom.value = 1f; pan.value = Offset.Zero }
    }

    LaunchedEffect(attempt) {
        runCatching { api.monitors(host) }.onSuccess { list ->
            monitors = list
            if (monitor == null) monitor = list.firstOrNull { it.primary }?.index
        }
    }

    // repeatOnLifecycle, not a bare collect: the mirror is the one screen that costs
    // the PC real work per frame, so backgrounding the app must actually stop it
    // rather than leave the server capturing for a phone in someone's pocket.
    LaunchedEffect(monitor, window, preset, attempt) {
        // A different monitor or window is a different aspect ratio, so a pan/zoom
        // carried over from the last one would point at the wrong part of the desktop.
        zoom.value = 1f
        pan.value = Offset.Zero
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            error = null
            api.frames(host, monitor, window?.handle, preset.fps, preset.width, preset.quality)
                .conflate()
                .catch { e -> error = e.message ?: "Mirror stopped" }
                .collect { frame = it }
        }
    }

    // One layout for both modes: the chips row is either the last item in the column
    // or the contents of the floating panel. Rendering the two modes as two different
    // trees would mean the stream, the zoom and the pan all get torn down and rebuilt
    // every time the user goes full screen, which is the one moment they're looking
    // hardest at the picture.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Flat black in both themes, and the one surface in the app that
                    // ignores the palette: this is the letterbox around a picture, not a
                    // panel the picture sits on, and a pale mount in light mode both
                    // changes the apparent colour of the desktop inside it and stops the
                    // frame having an edge at all. Same rule as a cinema screen — see
                    // §7's ScreenScreen entry.
                    .background(MIRROR_MOUNT),
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
                            // Keyed on naturalScroll too: the gesture loop reads it once,
                            // so without the key a scroll-direction change wouldn't take
                            // effect until the handler was restarted for some other reason.
                            .pointerInput(
                                monitor, window, settings.naturalScroll, haptics, settings.momentum, touchMode,
                            ) {
                                if (touchMode) {
                                    touchPassthroughGestures(monitor, window?.handle, send) { touched = true }
                                } else {
                                    mirrorGestures(
                                        monitor, window?.handle, zoom, pan, haptics, wheel,
                                        MomentumLevel.from(settings.momentum), scope, send,
                                    ) {
                                        touched = true
                                    }
                                }
                            }
                            .graphicsLayer {
                                scaleX = zoom.value
                                scaleY = zoom.value
                                translationX = pan.value.x
                                translationY = pan.value.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                    )
                }

                if (!touched && frame != null && error == null) MirrorGestureHint()

                // Typing while watching the screen you're typing into is the whole
                // point, so the keyboard lives here rather than on its own tab — the
                // Keyboard tab's own panel, floated over the mount so the picture keeps
                // its height. In full screen nothing above pads for the IME or the
                // cutout, so the overlay does it itself.
                if (typing) {
                    FloatingKeyboard(
                        send = send,
                        onClose = { typing = false },
                        modifier = if (fullscreen) Modifier.safeDrawingPadding() else Modifier,
                    )
                }
            }

            if (!fullscreen) {
                MirrorControls(
                    monitors = monitors,
                    monitor = monitor,
                    window = window,
                    preset = preset,
                    zoom = zoom.value,
                    typing = typing,
                    touchMode = touchMode,
                    fullscreen = false,
                    onMonitor = { monitor = it; window = null },
                    onPickWindow = { pickingWindow = true },
                    onPreset = { preset = it; onPresetChange(it) },
                    onResetZoom = { zoom.value = 1f; pan.value = Offset.Zero },
                    onToggleTyping = { typing = !typing },
                    onToggleTouchMode = { touchMode = !touchMode },
                    onToggleFullscreen = { onFullscreen(true) },
                )
            }
        }

        if (fullscreen) {
            FloatingMirrorControls(
                monitors = monitors,
                monitor = monitor,
                window = window,
                preset = preset,
                zoom = zoom.value,
                typing = typing,
                touchMode = touchMode,
                onMonitor = { monitor = it; window = null },
                onPickWindow = { pickingWindow = true },
                onPreset = { preset = it; onPresetChange(it) },
                onResetZoom = { zoom.value = 1f; pan.value = Offset.Zero },
                onToggleTyping = { typing = !typing },
                onToggleTouchMode = { touchMode = !touchMode },
                onExitFullscreen = { onFullscreen(false) },
            )
        }
    }

    if (pickingWindow) {
        ModalBottomSheet(onDismissRequest = { pickingWindow = false }) {
            WindowPickerSheet(
                load = { api.windows(host) },
                selected = window?.handle,
                onSelect = { window = it; pickingWindow = false },
            )
        }
    }
}

/**
 * The open windows on the PC, fetched fresh each time the sheet opens — the list
 * changes every time something is launched or closed, so a cached one would be
 * wrong the moment it matters. A sheet rather than more chips: the row already
 * holds nine, and this list is as long as the taskbar.
 */
@Composable
private fun WindowPickerSheet(
    load: suspend () -> List<RemoteWindow>,
    selected: Long?,
    onSelect: (RemoteWindow?) -> Unit,
) {
    var windows by remember { mutableStateOf<List<RemoteWindow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        windows = null
        error = null
        runCatching { load() }
            .onSuccess { windows = it }
            .onFailure { error = it.message ?: "Couldn't list windows" }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            "Mirror",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // The way back to the whole display lives here, not only on the monitor chips:
        // with a single monitor there are no monitor chips at all.
        ListItem(
            headlineContent = { Text("Whole display") },
            leadingContent = { Icon(Icons.Filled.Monitor, contentDescription = null) },
            colors = pickerRowColors(selected == null),
            modifier = Modifier.clickable { onSelect(null) },
        )
        when {
            error != null -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                TextButton(onClick = { attempt++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Try again")
                }
            }

            windows == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }

            else -> LazyColumn {
                items(windows!!, key = { it.handle }) { w ->
                    ListItem(
                        headlineContent = { Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(w.process, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.WebAsset, contentDescription = null) },
                        colors = pickerRowColors(selected == w.handle),
                        modifier = Modifier.clickable { onSelect(w) },
                    )
                }
            }
        }
    }
}

/** The picked row tinted the way a selected chip is, so the sheet and the chip row
 *  say "this one" the same way. */
@Composable
private fun pickerRowColors(selected: Boolean) = ListItemDefaults.colors(
    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
)

/**
 * The controls, off the picture: a barely-there button in the corner that opens the
 * same chip row over the bottom of the frame. Full screen exists so the frame is the
 * only thing on the display, so the controls can't take a permanent strip of it —
 * but they also can't be a hidden gesture, because "how do I change monitor" would
 * then have no answer on screen at all.
 *
 * Not a tap-anywhere reveal: a tap on this screen is a click on the PC, and spending
 * that gesture on chrome would make the mirror unusable for the thing it's for.
 */
@Composable
private fun FloatingMirrorControls(
    monitors: List<RemoteMonitor>,
    monitor: Int?,
    window: RemoteWindow?,
    preset: MirrorPreset,
    zoom: Float,
    typing: Boolean,
    touchMode: Boolean,
    onMonitor: (Int?) -> Unit,
    onPickWindow: () -> Unit,
    onPreset: (MirrorPreset) -> Unit,
    onResetZoom: () -> Unit,
    onToggleTyping: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    // No pointer handler on this Box itself, so everything that isn't the button or
    // the panel falls straight through to the frame's gesture loop underneath.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        IconButton(
            onClick = { open = !open },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(
                if (open) Icons.Filled.Close else Icons.Filled.Tune,
                contentDescription = if (open) "Hide controls" else "Show controls",
                tint = Color.White,
            )
        }

        if (open) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                // Same shape and edge as the floating keyboard beside it: two floating
                // panels on one screen get one chrome (§5).
                shape = MaterialTheme.shapes.large,
                // Near-opaque, unlike the button: chips have to stay readable against
                // whatever happens to be on the desktop behind them.
                color = PortalRemoteTheme.extendedColors.surfaceRaised.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, PortalRemoteTheme.hud.edge),
            ) {
                MirrorControls(
                    monitors = monitors,
                    monitor = monitor,
                    window = window,
                    preset = preset,
                    zoom = zoom,
                    typing = typing,
                    touchMode = touchMode,
                    fullscreen = true,
                    onMonitor = onMonitor,
                    onPickWindow = onPickWindow,
                    onPreset = onPreset,
                    onResetZoom = onResetZoom,
                    // The keyboard lands where this panel is; two floating panels on
                    // one edge is one too many, and the chips are not needed mid-type.
                    onToggleTyping = { open = false; onToggleTyping() },
                    onToggleTouchMode = onToggleTouchMode,
                    onToggleFullscreen = onExitFullscreen,
                )
            }
        }
    }
}

/**
 * [KeyboardPanel] floated over the picture rather than parked under it: a fixed strip
 * under the frame took a third of it in portrait, and in landscape there is no under.
 * Dragged by its header, so it can be moved off whatever is being read, and closed
 * from there too — the chip row that opened it is under the IME by now. The key grid
 * is folded away by default: with the IME up the mount is barely taller than the grid.
 */
@Composable
private fun FloatingKeyboard(
    send: (JSONObject) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var keys by rememberSaveable { mutableStateOf(false) }
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Keeps the handle inside the mount — a panel dragged off screen has no way back.
    // Applied at draw time as well as per drag, because the mount shrinks under the
    // panel whenever the IME comes up and a stored offset from before is then off the
    // top, header and all.
    fun Density.clamped(o: IntOffset): IntOffset {
        val pad = 8.dp.roundToPx()
        val maxX = ((area.width - size.width) / 2 - pad).coerceAtLeast(0)
        val maxY = (area.height - size.height - 2 * pad).coerceAtLeast(0)
        return IntOffset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, 0))
    }

    // No pointer handler on this Box, so a touch beside the panel still reaches the
    // frame's gesture loop underneath — same as the floating controls.
    Box(modifier = modifier.fillMaxSize().onSizeChanged { area = it }) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { clamped(offset) }
                .padding(8.dp)
                // Wide enough that the drawn keyboard's 13 caps are keys and not
                // pixels on a sideways phone; portrait is narrower than this anyway.
                .widthIn(max = 560.dp)
                .onSizeChanged { size = it },
            shape = MaterialTheme.shapes.large,
            // Near-opaque, like the floating controls: keys have to stay readable
            // against whatever happens to be on the desktop behind them.
            color = PortalRemoteTheme.extendedColors.surfaceRaised.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, PortalRemoteTheme.hud.edge),
        ) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                offset = clamped(
                                    IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt()),
                                )
                            }
                        },
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Typing to the PC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    IconButton(onClick = { keys = !keys }) {
                        Icon(
                            Icons.Filled.KeyboardAlt,
                            contentDescription = if (keys) "Hide keys" else "Show keys",
                            tint = if (keys) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop typing")
                    }
                }
                KeyboardPanel(
                    send = send,
                    showKeys = keys,
                    keyHeight = 40.dp,
                    // Scrolls under a fixed header: a sideways phone with the IME up
                    // leaves ~190dp of mount, which is less than the field and phrases
                    // alone, and the header has to stay put or the panel can't be
                    // moved or closed.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 6.dp),
                )
            }
        }
    }
}

/** Says the two gestures that aren't guessable from a picture of a desktop, then gets
 *  out of the way for good on the first touch. */
@Composable
private fun MirrorGestureHint() {
    Text(
        "Pinch to zoom · two fingers scroll, hold to keep going\n" +
            "Swipe two to go back · three for desktops",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Absolute-pointing gesture handler. Unlike the trackpad's, a touch here starts by
 * moving the cursor under the finger — otherwise the first tap after looking at the
 * screen would click wherever the pointer happened to be left.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mirrorGestures(
    monitor: Int?,
    window: Long?,
    zoom: MutableState<Float>,
    pan: MutableState<Offset>,
    haptics: Haptics,
    wheel: WheelScroll,
    momentum: MomentumLevel,
    /** Where hold-to-keep-scrolling runs. Outlives no gesture — it is cancelled on
     *  every release — but it has to outlive this loop's *event*, which is why it
     *  can't just be launched from here. */
    scope: CoroutineScope,
    send: (JSONObject) -> Unit,
    onTouch: () -> Unit,
) {
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onTouch()
        // Any touch catches a coasting scroll, the way a hand stops a spinning wheel.
        wheel.stop()
        // Fed the centroid while a two-finger scroll is running, so the release knows
        // whether the fingers were thrown or parked.
        val velocity = VelocityTracker()
        val downTimeMs = System.currentTimeMillis()
        val area: IntSize = size

        // Undo the zoom transform by hand: a touch arrives in view coordinates, but
        // the desktop fraction the server wants is relative to the *content*.
        fun pointAt(x: Float, y: Float) {
            val fraction = mirrorFraction(Offset(x, y), pan.value, zoom.value, area)
            send(Protocol.mouseMoveNorm(fraction.x, fraction.y, monitor, window))
        }

        // The rail is decided by where the finger *landed*, once, same as the
        // trackpad's — a drag that wanders out of the strip keeps scrolling, and one
        // that wanders in stays whatever it already was.
        val onRail = zoom.value > 1f && down.position.x >= area.width - MIRROR_RAIL_WIDTH.toPx()
        var mode = if (onRail) TouchMode.RAIL else TouchMode.WAITING
        // A rail touch isn't pointing at anything on the desktop — moving the cursor
        // under it would drag the pointer to the edge of the screen for no reason.
        if (!onRail) pointAt(down.position.x, down.position.y)

        var totalMove = 0f
        var pinchTravel = 0f
        var panTravel = 0f
        var twoFinger = TwoFingerIntent.UNDECIDED
        var pointerCount = 1
        // The axis a three-finger swipe committed to, and whether it has already fired.
        // A swipe says its piece once, however far the fingers carry on — and a release
        // after one must not also click, the same rule the pad has.
        var swipeAxis: Axis? = null
        var swipeFired = false

        // Hold-to-keep-scrolling, the same mechanic the trackpad has had: a phone-height
        // frame is a dozen notches of travel, so without it reading a long page here
        // means repeating the same two-finger swipe until the page ends. Displacement
        // from where the scroll started, and when it last actually changed, are what the
        // rate curve runs on — see startHoldToScroll.
        var scrolledX = 0f
        var scrolledY = 0f
        var scrollOffset = 0f
        var scrollLastMoveMs = down.uptimeMillis
        var holdJob: Job? = null
        fun armHold() {
            holdJob?.cancel()
            holdJob = startHoldToScroll(
                scope, wheel, haptics,
                offset = { scrollOffset },
                lastMoveMs = { scrollLastMoveMs },
            )
        }
        if (onRail) armHold()

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
                haptics.press()
                send(Protocol.mouseClick("left", down = true))
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            pointerCount = maxOf(pointerCount, pressed.size)

            if (pressed.isEmpty()) {
                holdJob?.cancel()
                // Both fingers often lift in this very event rather than a prior
                // move — each change's positionChange() is still valid here even
                // though `pressed` just went false. Skipping it drops the last chunk
                // of a fast flick from totalMove, and a real two-finger scroll can
                // land under TAP_SLOP and fire a right-click instead — see the same
                // fix in TrackpadScreen's gesture loop.
                if (mode == TouchMode.TWO_FINGER || mode == TouchMode.THREE_FINGER) {
                    totalMove += event.changes
                        .map { it.positionChange().getDistance() }
                        .average().toFloat()
                }
                if (mode == TouchMode.RAIL || twoFinger == TwoFingerIntent.SCROLL) {
                    val v = velocity.calculateVelocity()
                    wheel.fling(v.x, v.y, momentum)
                }
                when {
                    // A tap on the rail is not a click — the strip is a scrollbar.
                    mode == TouchMode.RAIL -> Unit
                    mode == TouchMode.DRAG -> {
                        haptics.tick()
                        send(Protocol.mouseClick("left", down = false))
                    }
                    // A swipe that already fired has said what it meant; a trailing
                    // tap-shaped release must not also click.
                    swipeFired -> Unit
                    // Same three keys the pad's three-finger tap sends.
                    totalMove < TAP_SLOP && pointerCount >= 3 -> {
                        haptics.tap()
                        send(Protocol.combo("f5"))
                    }
                    totalMove < TAP_SLOP && pointerCount >= 2 -> {
                        haptics.tap()
                        send(Protocol.mouseClick("right"))
                    }
                    totalMove < TAP_SLOP -> {
                        haptics.tap()
                        send(Protocol.mouseClick("left"))
                    }
                }
                break
            }

            if (mode != TouchMode.DRAG && mode != TouchMode.RAIL) {
                if (pressed.size >= 3 && mode != TouchMode.THREE_FINGER) {
                    // Fingers land milliseconds apart, so a three-finger swipe is briefly
                    // a two-finger one. Everything the two-finger head of the gesture
                    // decided is thrown away here, or the stray first 20ms picks the
                    // meaning — the same reset the pad does on a pointer-count change.
                    mode = TouchMode.THREE_FINGER
                    holdJob?.cancel()
                    holdJob = null
                    twoFinger = TwoFingerIntent.UNDECIDED
                    scrolledX = 0f
                    scrolledY = 0f
                    swipeAxis = null
                } else if (pointerCount >= 2 && mode != TouchMode.THREE_FINGER) {
                    // Never back down from three: lifting the third finger at the end of
                    // a desktop swipe is not the start of a page-back.
                    mode = TouchMode.TWO_FINGER
                }
            }

            when (mode) {
                TouchMode.RAIL -> {
                    val primary: PointerInputChange =
                        event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    // Before consuming: positionChange() is defined to return Offset.Zero
                    // once the change is consumed.
                    val dy = primary.positionChange().y
                    primary.consume()
                    velocity.addPosition(primary.uptimeMillis, primary.position)
                    scrollOffset = primary.position.y - down.position.y
                    // Sub-pixel jitter from a resting finger must not count as movement,
                    // or the hold never gets to start.
                    if (kotlin.math.abs(dy) >= 1f) scrollLastMoveMs = primary.uptimeMillis
                    wheel.by(dy = dy)
                }

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
                        // Banked while undecided so a gesture that turns out to be a
                        // scroll can replay everything the classification swallowed.
                        scrolledX += centroidShift.x
                        scrolledY += centroidShift.y
                        twoFinger = classifyTwoFingerIntent(
                            pinchTravel,
                            panTravel,
                            zoomed = zoom.value > 1f,
                            horizontal = kotlin.math.abs(scrolledX) > kotlin.math.abs(scrolledY),
                        )
                        if (twoFinger == TwoFingerIntent.SCROLL) {
                            velocity.addPosition(a.uptimeMillis, centroid)
                            wheel.by(dx = scrolledX, dy = scrolledY)
                            scrollOffset = scrolledY
                            scrollLastMoveMs = a.uptimeMillis
                            armHold()
                        }
                        // Whatever this event carried is already banked above — in
                        // `scrolledX/Y` for a scroll or swipe, in the travel totals for a
                        // pinch — so the branches below would replay it. One frame is
                        // nothing to a zoom or a pan and everything to a scroll, which is
                        // why the replay above exists.
                        continue
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

                        // Both axes, unlike the trackpad — the pad spends its
                        // horizontal two-finger gesture on back/forward, but nothing
                        // competes for it here, and a wide timeline or spreadsheet is
                        // exactly what the mirror gets pointed at.
                        // Two fingers sideways at 1x is back/forward, exactly as on the
                        // trackpad. Horizontal *scrolling* loses its gesture here, which
                        // is the price of the two surfaces agreeing on what a sideways
                        // swipe means — and back/forward is what a hand reaches for
                        // orders of magnitude more often.
                        TwoFingerIntent.SWIPE -> {
                            scrolledX += centroidShift.x
                            if (!swipeFired && kotlin.math.abs(scrolledX) >= SWIPE_TRIGGER) {
                                swipeFired = true
                                haptics.press()
                                send(
                                    Protocol.combo(
                                        *swipeShortcut(
                                            GestureMode.TWO_FINGER, Axis.HORIZONTAL, scrolledX,
                                        ).toTypedArray(),
                                    ),
                                )
                            }
                        }

                        TwoFingerIntent.SCROLL -> {
                            velocity.addPosition(a.uptimeMillis, centroid)
                            scrolledX += centroidShift.x
                            scrolledY += centroidShift.y
                            scrollOffset = scrolledY
                            if (kotlin.math.abs(centroidShift.y) >= 1f) {
                                scrollLastMoveMs = a.uptimeMillis
                            }
                            wheel.by(dx = centroidShift.x, dy = centroidShift.y)
                        }
                    }
                }

                TouchMode.THREE_FINGER -> {
                    event.changes.forEach { it.consume() }
                    // The centroid, not one finger: three fingers never lift together,
                    // and tracking whichever is first reads the lift as a direction
                    // change. positionChange() is Offset.Zero on a consumed change, so
                    // the shift is taken from the positions themselves.
                    val shift = Offset(
                        pressed.map { it.position.x - it.previousPosition.x }.average().toFloat(),
                        pressed.map { it.position.y - it.previousPosition.y }.average().toFloat(),
                    )
                    totalMove += shift.getDistance()
                    if (!swipeFired) {
                        scrolledX += shift.x
                        scrolledY += shift.y
                        if (swipeAxis == null &&
                            max(kotlin.math.abs(scrolledX), kotlin.math.abs(scrolledY)) >= AXIS_LOCK_SLOP
                        ) {
                            swipeAxis = if (kotlin.math.abs(scrolledX) > kotlin.math.abs(scrolledY)) {
                                Axis.HORIZONTAL
                            } else {
                                Axis.VERTICAL
                            }
                        }
                        val locked = swipeAxis
                        val travel = if (locked == Axis.HORIZONTAL) scrolledX else scrolledY
                        if (locked != null && kotlin.math.abs(travel) >= SWIPE_TRIGGER) {
                            swipeFired = true
                            // The result shows up on the *other* screen, so the phone
                            // has to say something itself — the app's heaviest haptic,
                            // same as on the pad.
                            haptics.press()
                            send(
                                Protocol.combo(
                                    *swipeShortcut(
                                        GestureMode.THREE_FINGER, locked, travel,
                                    ).toTypedArray(),
                                ),
                            )
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

/** Windows' real touch-injection API takes contact ids 0..9, owned by the caller for
 *  a finger's whole contact; Android's own pointer ids are arbitrary and can repeat
 *  across gestures, so they can't be forwarded as-is. */
private const val MAX_TOUCH_CONTACTS = 10

/**
 * Raw multi-touch passthrough: every finger's down/move/up goes to Windows' actual
 * touch digitizer (see `WinInput.Touch`), not the emulated cursor [mirrorGestures]
 * drives. No tap/drag/pinch/scroll interpretation here — the PC's own touch-aware
 * apps (Windows Ink, native pinch-zoom) are what get to interpret the gesture, which
 * is the entire point of this mode. Assumes the caller has already pinned zoom at 1x:
 * pinching the *view* while also forwarding the same two fingers as a real touch
 * would have the phone and the PC fighting over what the gesture means.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchPassthroughGestures(
    monitor: Int?,
    window: Long?,
    send: (JSONObject) -> Unit,
    onTouch: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onTouch()

        val slots = mutableMapOf<Long, Int>()
        val freeSlots = ArrayDeque((0 until MAX_TOUCH_CONTACTS).toList())

        while (true) {
            val event = awaitPointerEvent()
            val contacts = mutableListOf<Protocol.TouchContact>()

            for (change in event.changes) {
                val down = change.changedToDown()
                val up = change.changedToUp()
                val key = change.id.value
                val slot = when {
                    down -> freeSlots.removeFirstOrNull()?.also { slots[key] = it }
                    else -> slots[key]
                    // Either a finger past the 10-contact ceiling, or a stray change
                    // for a pointer this loop never saw arrive — both silently
                    // dropped rather than sent with a made-up slot.
                } ?: continue

                change.consume()
                contacts += Protocol.TouchContact(
                    slot,
                    (change.position.x / size.width).coerceIn(0f, 1f),
                    (change.position.y / size.height).coerceIn(0f, 1f),
                    if (down) Protocol.TouchPhase.DOWN
                    else if (up) Protocol.TouchPhase.UP
                    else Protocol.TouchPhase.MOVE,
                )
                if (up) { slots.remove(key); freeSlots.addLast(slot) }
            }

            if (contacts.isNotEmpty()) send(Protocol.touch(contacts, monitor, window))
            if (event.changes.all { !it.pressed }) break
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
        TextButton(onClick = onRetry) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text("Retry")
        }
    }
}

/** Monitor picker (only when there's a choice to make), the app chip, plus the quality preset. */
@Composable
private fun MirrorControls(
    monitors: List<RemoteMonitor>,
    monitor: Int?,
    window: RemoteWindow?,
    preset: MirrorPreset,
    zoom: Float,
    typing: Boolean,
    touchMode: Boolean,
    fullscreen: Boolean,
    onMonitor: (Int?) -> Unit,
    onPickWindow: () -> Unit,
    onPreset: (MirrorPreset) -> Unit,
    onResetZoom: () -> Unit,
    onToggleTyping: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Three unrelated groups of chips share this one scrolling row — which monitor,
        // how good it looks, and what a touch does. Every chip carries its group's
        // glyph so the row reads as three runs rather than as nine similar pills.
        if (monitors.size > 1) {
            monitors.forEach { display ->
                FilterChip(
                    selected = window == null && monitor == display.index,
                    onClick = { onMonitor(display.index) },
                    leadingIcon = { ChipIcon(Icons.Filled.Monitor) },
                    label = { Text(display.label) },
                )
            }
            FilterChip(
                selected = window == null && monitor == ALL_MONITORS,
                onClick = { onMonitor(ALL_MONITORS) },
                leadingIcon = { ChipIcon(Icons.Filled.SelectAll) },
                label = { Text("All") },
            )
        }
        // One app rather than a display. Names the app while one is picked so the row
        // says what the frame is showing; tapping it again opens the list to change.
        FilterChip(
            selected = window != null,
            onClick = onPickWindow,
            leadingIcon = { ChipIcon(Icons.Filled.WebAsset) },
            label = { Text(window?.process?.ifEmpty { null } ?: "App") },
        )
        MirrorPreset.entries.forEach { option ->
            FilterChip(
                selected = preset == option,
                onClick = { onPreset(option) },
                leadingIcon = { ChipIcon(option.icon) },
                label = { Text(option.label) },
            )
        }
        FilterChip(
            selected = typing,
            onClick = onToggleTyping,
            leadingIcon = { ChipIcon(Icons.Filled.Keyboard) },
            label = { Text("Type") },
        )
        FilterChip(
            selected = touchMode,
            onClick = onToggleTouchMode,
            leadingIcon = { ChipIcon(Icons.Filled.TouchApp) },
            label = { Text("Touch") },
        )
        FilterChip(
            selected = fullscreen,
            onClick = onToggleFullscreen,
            leadingIcon = {
                ChipIcon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen)
            },
            label = { Text(if (fullscreen) "Exit" else "Full screen") },
        )
        // Pinching back to exactly 1x is fiddly, so offer the way out explicitly —
        // and only while there's something to undo.
        if (zoom > 1f) {
            FilterChip(
                selected = false,
                onClick = onResetZoom,
                leadingIcon = { ChipIcon(Icons.Filled.FitScreen) },
                label = { Text("${"%.1f".format(zoom)}× · reset") },
            )
        }
    }
}

/** Chip leading icons at Material3's own 18dp, which `Icon`'s 24dp default overshoots
 *  — nine chips on one scrolling row can't spend 6dp each on nothing. Shared with
 *  Share and Settings (same arrangement as `FilesScreen`'s `EmptyState`) so every chip
 *  in the app measures its glyph the same way. */
@Composable
internal fun ChipIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
    )
}

/** The server reads any negative monitor index as "the whole virtual desktop". */
private const val ALL_MONITORS = -1
