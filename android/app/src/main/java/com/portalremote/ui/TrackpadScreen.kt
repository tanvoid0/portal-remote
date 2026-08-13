package com.portalremote.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.portalremote.data.AppSettings
import com.portalremote.ui.theme.Haptics
import com.portalremote.ui.theme.HudPanelShape
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/** Width of the right-edge scroll rail. Wide enough to hit without looking (§9's 48dp
 *  floor is about controls; this is a strip along a full-height edge, so 44dp with the
 *  screen edge behind it is comfortably past the same target). */
private val SCROLL_RAIL_WIDTH = 44.dp

/**
 * Hold-to-keep-scrolling — shared by the rail and two-finger vertical scroll (see
 * [startHoldToScroll]).
 *
 * Either surface is 1:1 while the finger moves, which is right for a nudge and useless
 * for a long document: a phone-height pad is maybe twelve notches of travel, so
 * reaching the bottom of anything means repeating the same swipe over and over. So once
 * the finger has been still for [RAIL_HOLD_MS] it switches to rate control —
 * displacement from where the gesture started becomes a speed, the way holding a
 * scrollbar's track does. Moving again hands control straight back to 1:1.
 *
 * Rate is squared, not linear, so the first third of the travel is a slow crawl you
 * can stop on a line, and full tilt is reserved for the far end.
 */
private const val RAIL_HOLD_MS = 120L
private const val RAIL_TICK_MS = 16L

/** Displacement from where the gesture started below which holding does nothing. A
 *  finger parked where it landed is a finger resting on the pad, not a request to
 *  scroll forever. */
private const val RAIL_DEAD_ZONE = 20f

/** Displacement past the dead zone at which the rate hits [RAIL_MAX_PX_PER_SEC]. */
private const val RAIL_FULL_SPEED = 160f

/** Top auto-scroll rate, in the finger-travel pixels [WheelScroll] takes — about 26
 *  notches a second, which is a fast but still readable page-flick. */
private const val RAIL_MAX_PX_PER_SEC = 1600f

/**
 * Finger travel to emit for one tick of auto-scroll, given how far the held finger sits
 * from where its gesture began. Pure and separate from the job below so the curve — dead
 * zone, squared ramp, ceiling — can be checked without a clock; see `HoldScrollTest`.
 */
internal fun holdScrollStep(offset: Float, tickMs: Long = RAIL_TICK_MS): Float {
    val over = abs(offset) - RAIL_DEAD_ZONE
    if (over <= 0f) return 0f
    val t = (over / RAIL_FULL_SPEED).coerceAtMost(1f)
    return sign(offset) * t * t * RAIL_MAX_PX_PER_SEC * (tickMs / 1000f)
}

/**
 * The hold-to-keep-scrolling mechanic itself, factored out so the rail and the
 * two-finger vertical scroll share one rate curve instead of two that could drift
 * apart. [offset] is displacement from wherever the caller considers the gesture's
 * start — the rail's distance from touch-down, or the two-finger case's cumulative
 * pan since the axis locked; [lastMoveMs] is the last time that offset actually
 * changed, which is what "held still" means here.
 */
private fun startHoldToScroll(
    scope: CoroutineScope,
    wheel: WheelScroll,
    haptics: Haptics,
    offset: () -> Float,
    lastMoveMs: () -> Long,
): Job = scope.launch {
    var running = false
    while (isActive) {
        delay(RAIL_TICK_MS)
        // Pointer uptime, not wall clock: [Finger.uptimeMs] is what stamps `lastMoveMs`,
        // and wall clock jumps whenever the network corrects it.
        val holding = SystemClock.uptimeMillis() - lastMoveMs() >= RAIL_HOLD_MS
        val step = if (holding) holdScrollStep(offset()) else 0f
        if (step == 0f) {
            running = false
            continue
        }
        // One bump as it takes over, then silence: this is continuous motion, and
        // Haptics.kt's rule is that continuous motion gets nothing.
        if (!running) { running = true; haptics.tick() }
        // `coasting` because it isn't a finger: it suppresses the per-notch tick and
        // leaves the scroll echo re-stamping, which is what says the auto-scroll is
        // still running.
        wheel.by(dy = step, coasting = true)
    }
}

/**
 * The pad's full gesture vocabulary, for the list in Settings.
 *
 * Declared here, beside the handlers that implement it, because a mapping written down
 * in another file is a mapping that goes stale the first time this one changes. The
 * legend drawn on the pad itself stays three abridged lines and disappears on the first
 * touch — this is the place that spells all of it out, and until now three-finger
 * anything was written down only in `docs/design-system.md`, which is not a thing a
 * user can open.
 */
internal val TrackpadGestures: List<Pair<String, String>> = listOf(
    "Drag" to "Move the pointer",
    "Tap" to "Left click",
    "Tap with two fingers" to "Right click",
    "Tap with three fingers" to "Reload the page (F5)",
    "Hold, then drag" to "Click and drag — select text, move a window",
    "Two fingers up or down" to "Scroll — hold instead of releasing to keep going. " +
        "The strip down the right edge does the same",
    "Two fingers left or right" to "Back and forward",
    "Three fingers left or right" to "Previous and next virtual desktop",
    "Three fingers up" to "Task view",
    "Three fingers down" to "Show the desktop",
)

/** Compose's own pointer type, as [PadGesture] wants it. The delta is read here — the
 *  one place that still has an unconsumed change to read it from. */
private fun PointerInputChange.toFinger() = Finger(
    id = id.value,
    position = position,
    delta = positionChange(),
    uptimeMs = uptimeMillis,
    previousUptimeMs = previousUptimeMillis,
    pressed = pressed,
)

/** How long the gesture echo holds before fading — long enough to catch in peripheral
 *  vision, short enough that the pad is clean again before the next gesture. */
private const val ECHO_HOLD_MS = 450L

/** A resolved action, shown briefly in the middle of the pad. [stamp] makes two
 *  consecutive identical actions distinct, so the second one restarts the timer. */
private data class GestureEcho(val icon: ImageVector, val label: String, val stamp: Int)

/**
 * What a fired shortcut looks like on the pad. Derived from the keys the gesture
 * already sends rather than passed alongside them, so a gesture added later cannot
 * ship with a caption that says something the PC isn't doing.
 */
private fun echoFor(keys: List<String>): Pair<ImageVector, String> = when {
    keys == listOf("browser_back") -> Icons.AutoMirrored.Filled.ArrowBack to "Back"
    keys == listOf("browser_forward") -> Icons.AutoMirrored.Filled.ArrowForward to "Forward"
    keys == listOf("f5") -> Icons.Filled.Refresh to "Reload"
    "tab" in keys -> Icons.Filled.GridView to "Task view"
    "d" in keys -> Icons.Filled.DesktopWindows to "Show desktop"
    "left" in keys -> Icons.Filled.Dashboard to "Previous desktop"
    else -> Icons.Filled.Dashboard to "Next desktop"
}

/**
 * Touch surface + explicit click buttons, matching how physical trackpads with
 * separate buttons behave: dragging on the pad moves the cursor, the buttons
 * below click independently so users can hold one and drag with another finger
 * to select/drag desktop content.
 */
@Composable
fun TrackpadScreen(
    settings: AppSettings,
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Int) -> Unit,
    onClick: (button: String, down: Boolean?) -> Unit,
    /** A key combo the multi-finger gestures stand for — see [swipeShortcut]. */
    onShortcut: (keys: List<String>) -> Unit,
    onText: (String) -> Unit,
    onTap: (key: String) -> Unit,
    /** Fired the moment the type bar below gets focus — the shell uses this to jump
     *  to the Keyboard tab, which is where that focus actually belongs. */
    onFocusKeyboard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A trigger, not a place to actually type: focusing it hands off to the
        // Keyboard tab's own field immediately, before the IME has a chance to open
        // here. Not auto-focused itself — that would fire the handoff the instant
        // this tab is shown, which is not what landing on Trackpad means.
        KeyCaptureField(
            onText = onText,
            onTap = onTap,
            autoFocus = false,
            label = "Tap to type",
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocusKeyboard() },
        )
        TrackpadSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            settings = settings,
            onMove = onMove,
            onScroll = onScroll,
            onShortcut = onShortcut,
            onTapLeft = { onClick("left", null) },
            onTapRight = { onClick("right", null) },
            onDragStart = { onClick("left", true) },
            onDragEnd = { onClick("left", false) },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ClickButton(
                label = "Left",
                modifier = Modifier.weight(1f),
                onDown = { onClick("left", true) },
                onUp = { onClick("left", false) },
            )
            ClickButton(
                label = "Right",
                modifier = Modifier.weight(1f),
                onDown = { onClick("right", true) },
                onUp = { onClick("right", false) },
            )
        }
    }
}

@Composable
private fun TrackpadSurface(
    modifier: Modifier,
    settings: AppSettings,
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Int) -> Unit,
    onShortcut: (keys: List<String>) -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val tapScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var railActive by remember { mutableStateOf(false) }
    val railGlow by animateFloatAsState(if (railActive) 1f else 0f, Motion.pressSpec(), label = "rail")
    // The hold-to-keep-scrolling jobs (rail, and two-finger vertical scroll once the
    // axis locks). Held out here rather than inside the pointer handler so leaving the
    // screen mid-drag stops them — they're the only things in this file that keep
    // sending after the finger is no longer being read.
    var railJob by remember { mutableStateOf<Job?>(null) }
    var twoFingerHoldJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { railJob?.cancel(); twoFingerHoldJob?.cancel() } }
    // The gesture legend is onboarding, not chrome: once a finger has landed on the
    // pad the user has the idea, and a permanent two-line caption in the middle of
    // the surface is the most-looked-at clutter on the most-used screen.
    var used by remember { mutableStateOf(false) }

    // 100ms scale pulse confirming a resolved tap — see docs/design-system.md §7.
    // Drag/scroll never touch this: the pad tracks the finger 1:1 with no animation.
    fun pulse() {
        scope.launch {
            tapScale.animateTo(Motion.PressScale, Motion.pressSpec())
            tapScale.animateTo(1f, Motion.pressSpec())
        }
    }

    // Gesture feedback. Both of these fire on *resolved* actions only — never while a
    // finger is being tracked — so §2's "silence at high frequency" still holds for the
    // thing it was written about: pointer movement stays completely undecorated.
    val reducedMotion = Motion.reducedMotionEnabled(LocalContext.current)
    var echo by remember { mutableStateOf<GestureEcho?>(null) }
    var echoStamp by remember { mutableIntStateOf(0) }
    val echoAlpha = remember { Animatable(0f) }
    val echoScale = remember { Animatable(1f) }
    var rippleAt by remember { mutableStateOf(Offset.Zero) }
    val ripple = remember { Animatable(1f) }
    // Read here, not in the draw lambda — MaterialTheme is composition-scoped.
    val rippleColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun showEcho(icon: ImageVector, label: String) {
        echoStamp++
        echo = GestureEcho(icon, label, echoStamp)
    }

    /** A click landed here — expanding ring at the touch point. The pad's own scale
     *  pulse says *that* a click happened; this says *where*, which is the part a
     *  glance can't recover from a surface with no cursor on it. */
    fun showRipple(at: Offset) {
        rippleAt = at
        scope.launch {
            ripple.snapTo(0f)
            ripple.animateTo(1f, if (reducedMotion) snap() else tween(260, easing = Motion.EaseOut))
        }
    }

    // The pad scrolls vertically only — two-finger horizontal is the back/forward swipe
    // — so the shared wheel's dx is unused here. Momentum comes with it: a scroll that
    // stops dead at the fingertip is the loudest "this is a touchscreen, not a trackpad"
    // tell the pad has.
    val wheel = remember(settings.naturalScroll, haptics) {
        WheelScroll(scope) { _, dy, coasting ->
            val direction = if (settings.naturalScroll) -1 else 1
            onScroll(direction * dy * WHEEL_DELTA)
            // One tick per notch under a finger, so scrolling has detents like a real
            // wheel. Never while coasting: a 4000px/s flick is ~60 notches a second,
            // which is a rattle rather than feedback.
            if (!coasting) haptics.tick()
            // Re-stamped per notch, which holds the arrow up for as long as the scroll
            // runs rather than flashing it once per detent.
            showEcho(
                if (direction * dy > 0) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                "Scroll",
            )
        }
    }

    LaunchedEffect(echo) {
        if (echo == null) return@LaunchedEffect
        // Only spring in when arriving from nothing: a re-stamp mid-hold (scrolling on,
        // swiping twice) should extend the echo, not restart its entrance under the eye.
        if (echoAlpha.value == 0f) {
            echoScale.snapTo(0.85f)
            launch { echoScale.animateTo(1f, if (reducedMotion) snap() else Motion.pairingSuccessSpec()) }
        }
        echoAlpha.animateTo(1f, if (reducedMotion) snap() else tween(90, easing = Motion.EaseOut))
        delay(ECHO_HOLD_MS)
        echoAlpha.animateTo(0f, if (reducedMotion) snap() else tween(180, easing = Motion.EaseOut))
        // Reaching here means nothing re-stamped: a change would have cancelled this.
        echo = null
    }

    // The pad is the app's one full-bleed control, so it gets the panel treatment at
    // full size: the chamfer, the edge, and reticle marks at the corners. The marks are
    // doing a job here that they only decorate elsewhere — this surface has no content
    // of its own to show where it begins and ends.
    val hud = PortalRemoteTheme.hud
    Box(
        modifier = modifier
            .clip(HudPanelShape)
            .graphicsLayer { scaleX = tapScale.value; scaleY = tapScale.value }
            .background(hud.sunken)
            // The pad had no edge at all: in light mode its fill and the screen behind it
            // were both white, so the surface a finger is supposed to aim at was defined
            // by nothing. `borderStrong` is the 3:1 control boundary WCAG 1.4.11 asks
            // for (§3).
            .border(1.dp, PortalRemoteTheme.extendedColors.borderStrong, HudPanelShape)
            .drawBehind {
                val arm = 22.dp.toPx()
                val inset = 10.dp.toPx()
                val weight = 1.5.dp.toPx()
                val mark = hud.live.copy(alpha = 0.45f)
                // Top-left and bottom-right only, matching the two corners the chamfer
                // leaves square — the same asymmetry every panel in the app has.
                drawLine(mark, Offset(inset, inset), Offset(inset + arm, inset), weight)
                drawLine(mark, Offset(inset, inset), Offset(inset, inset + arm), weight)
                drawLine(
                    mark,
                    Offset(size.width - inset, size.height - inset),
                    Offset(size.width - inset - arm, size.height - inset),
                    weight,
                )
                drawLine(
                    mark,
                    Offset(size.width - inset, size.height - inset),
                    Offset(size.width - inset, size.height - inset - arm),
                    weight,
                )
            }
            // Above the surface, below the rail and the echo. Radius and alpha only —
            // no layout, nothing to invalidate but this one draw.
            .drawWithContent {
                drawContent()
                val t = ripple.value
                if (t < 1f) {
                    drawCircle(
                        color = rippleColor,
                        radius = 12.dp.toPx() + t * 52.dp.toPx(),
                        center = rippleAt,
                        alpha = 0.22f * (1f - t),
                    )
                }
            }
            // Keyed on the settings the loop below reads: it captures them once when
            // it starts, so without these keys a change wouldn't apply until the
            // handler happened to restart. `haptics` is a new instance whenever the
            // preference flips, so it belongs in the keys for the same reason.
            .pointerInput(
                settings.pointerSpeed,
                settings.naturalScroll,
                settings.precisionPointer,
                settings.momentum,
                haptics,
            ) {
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

                awaitEachGesture {
                    railJob?.cancel()
                    twoFingerHoldJob?.cancel()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    used = true
                    // Catching a coasting scroll is how you stop one, the same as
                    // slapping a spinning wheel — and it must happen on *any* touch,
                    // before this gesture is even classified.
                    wheel.stop()
                    // All the deciding lives in here; this loop only feeds it pointers
                    // and turns what it decides into sound, light and socket traffic.
                    // `size` is read per gesture, not once: it is stale across a rotation.
                    val gesture = PadGesture(
                        railStartX = size.width - SCROLL_RAIL_WIDTH.toPx(),
                        longPressTimeoutMs = longPressTimeoutMs,
                        pointerSpeed = settings.pointerSpeed,
                        precisionPointer = settings.precisionPointer,
                    )

                    fun perform(actions: List<PadAction>) {
                        for (action in actions) when (action) {
                            is PadAction.Move -> onMove(action.dx, action.dy)
                            is PadAction.Scroll -> wheel.by(dy = action.dy)
                            is PadAction.Click -> {
                                haptics.tap(); pulse(); showRipple(down.position)
                                if (action.button == "left") onTapLeft() else onTapRight()
                            }
                            is PadAction.Shortcut -> {
                                // A three-finger tap is a tap and feels like one; a swipe
                                // commits invisibly — the result shows up on the *other*
                                // screen — so it gets the app's heaviest haptic, same as a
                                // hold becoming a drag.
                                if (action.keys == listOf("f5")) { haptics.tap(); pulse() }
                                else haptics.press()
                                echoFor(action.keys).let { (icon, label) -> showEcho(icon, label) }
                                onShortcut(action.keys)
                            }
                            PadAction.DragStart -> {
                                // The gesture just changed meaning under a finger that
                                // hasn't moved — the strongest haptic in the app earns its
                                // place here, and it's also how the user knows the hold
                                // "took" without watching the desktop.
                                haptics.press()
                                showEcho(Icons.Filled.OpenWith, "Dragging")
                                onDragStart()
                            }
                            PadAction.DragEnd -> { haptics.tick(); onDragEnd() }
                            PadAction.RailStart -> {
                                // Landing on the rail means this gesture will scroll rather
                                // than move — the one thing about the pad you can't see
                                // while looking at the PC, so it's worth a bump.
                                railActive = true
                                haptics.tick()
                                railJob = startHoldToScroll(
                                    scope, wheel, haptics,
                                    offset = { gesture.railOffset },
                                    lastMoveMs = { gesture.railLastMoveMs },
                                )
                            }
                            PadAction.HoldScrollArm -> {
                                twoFingerHoldJob = startHoldToScroll(
                                    scope, wheel, haptics,
                                    offset = { gesture.panOffset },
                                    lastMoveMs = { gesture.panLastMoveMs },
                                )
                            }
                            is PadAction.End -> {
                                railActive = false
                                railJob?.cancel()
                                twoFingerHoldJob?.cancel()
                                action.flingVelocityY?.let {
                                    wheel.fling(
                                        velocityX = 0f,
                                        velocityY = it,
                                        level = MomentumLevel.from(settings.momentum),
                                    )
                                }
                            }
                        }
                    }

                    perform(gesture.down(down.toFinger()))

                    while (true) {
                        val event = if (gesture.mode == GestureMode.WAITING) {
                            withTimeoutOrNull(gesture.waitBudgetMs(SystemClock.uptimeMillis())) {
                                awaitPointerEvent()
                            }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // Long-press timeout elapsed with the finger essentially
                            // still: begin a click-and-drag instead of a plain move.
                            perform(gesture.longPress())
                            continue
                        }

                        // Snapshot every delta *before* consuming anything:
                        // positionChange() is defined to return Offset.Zero on a change
                        // that has been consumed, and consuming first is what silently
                        // flattened every multi-finger gesture to zero travel.
                        val fingers = event.changes.map { it.toFinger() }
                        event.changes.forEach { it.consume() }
                        perform(gesture.event(fingers))
                        if (gesture.finished) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The scroll rail. Drawn, not interactive — the parent's gesture handler owns
        // it; this only has to say "this edge is different" without pulling the eye
        // away from the pad it sits on.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(SCROLL_RAIL_WIDTH)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.04f + 0.08f * railGlow)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            )
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = "Scroll",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = 0.3f + 0.45f * railGlow),
            )
        }

        // What just went to the PC. Centred in the pad and gone in half a second — it
        // is confirmation, not a status readout, so nothing here persists.
        echo?.let { current ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = SCROLL_RAIL_WIDTH)
                    .graphicsLayer {
                        alpha = echoAlpha.value
                        scaleX = echoScale.value
                        scaleY = echoScale.value
                    },
            ) {
                Icon(
                    current.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    current.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (!used) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Centered in the pad, not in the pad + rail.
                modifier = Modifier.padding(end = SCROLL_RAIL_WIDTH),
            ) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
                Text(
                    "Drag to move · tap to click · hold to drag\n" +
                        "Right edge or two fingers to scroll\n" +
                        "Swipe two to go back · three for desktops",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A button that reports press and release separately so it can be held. Scales down
 * on press and back on release, per the standard §6 button-feedback spec — driven
 * directly off this button's own down/up tracking rather than an interactionSource,
 * since it already bypasses the normal click gesture to support press-and-hold. */
@Composable
private fun ClickButton(
    label: String,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current

    Button(
        onClick = {},
        modifier = modifier
            .height(56.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            // Press and release each get their own haptic: this button can be *held*
            // while another finger drags, so "still down" is real state the user has
            // to be able to feel.
            .pointerInput(haptics) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptics.tap()
                    onDown()
                    scope.launch { scale.animateTo(Motion.PressScale, Motion.pressSpec()) }
                    waitForUpOrCancellation()
                    haptics.tick()
                    onUp()
                    scope.launch { scale.animateTo(1f, Motion.pressSpec()) }
                }
            },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
