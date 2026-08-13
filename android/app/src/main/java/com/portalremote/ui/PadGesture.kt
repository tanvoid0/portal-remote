package com.portalremote.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.max

/**
 * Mouse deltas are scaled down before sending because Windows applies pointer
 * acceleration to relative motion: a phone-swipe delta arrives at the desktop
 * amplified 2-4x (confirmed against a live server: dx=60 landed as 150-290px
 * depending on run). Without this, the pointer flies far past the finger.
 */
private const val MOVE_SENSITIVITY = 0.5f

/** Base scaling is what shipped before the settings screen; [com.portalremote.data.AppSettings.pointerSpeed]
 *  multiplies it, so 1.0x reproduces the old behaviour exactly. */
internal fun sensitivity(pointerSpeed: Float) = MOVE_SENSITIVITY * pointerSpeed

/** Total movement (px) below which a gesture counts as a tap rather than a drag. */
internal const val TAP_SLOP = 18f

/** Gain applied to a finger that is barely moving. A finger covers roughly 130 desktop
 *  pixels of a 3440px monitor shown at phone width, so at 1:1 the smallest movement a
 *  hand can make is still several pixels on the PC — this is what makes landing on a
 *  window edge or a 16px close button possible at all. */
private const val PRECISION_MIN_GAIN = 0.35f

/** Below this finger speed (px/ms) the pointer is fully damped — about 3px per frame
 *  at 60Hz, i.e. deliberate placement rather than travel. */
private const val PRECISION_SLOW = 0.05f

/** At and above this speed (px/ms, ~20px per frame) the gain is 1.0: crossing the
 *  screen must stay as fast as it was before precision existed. */
private const val PRECISION_FAST = 1.2f

/**
 * Pointer gain as a function of how fast the finger is moving — a small acceleration
 * curve, the same idea every desktop OS applies to a physical mouse. Damping only the
 * *slow* end is the whole point: fast travel is unchanged, so the pad doesn't feel
 * sluggish, but a careful nudge moves a third as far and a pixel becomes hittable.
 *
 * Pure and top-level so the curve can be checked without a device — see
 * `PrecisionGainTest`.
 */
internal fun precisionGain(speedPxPerMs: Float, enabled: Boolean): Float {
    if (!enabled) return 1f
    val t = ((speedPxPerMs - PRECISION_SLOW) / (PRECISION_FAST - PRECISION_SLOW)).coerceIn(0f, 1f)
    return PRECISION_MIN_GAIN + (1f - PRECISION_MIN_GAIN) * t
}

/** Centroid travel a multi-finger gesture must cover before it commits to an axis.
 *  Two fingers never move perfectly straight, so without this a horizontal swipe
 *  scrolls a little on its way out and a scroll drifts sideways into a page-back. */
internal const val AXIS_LOCK_SLOP = 24f

/** Travel along the locked axis that fires a swipe action — once per gesture, the
 *  way a physical trackpad fires a page-back once however far the fingers carry on. */
internal const val SWIPE_TRIGGER = 90f

internal enum class GestureMode { WAITING, MOVE, DRAG, TWO_FINGER, THREE_FINGER, RAIL }

internal enum class Axis { HORIZONTAL, VERTICAL }

/**
 * The shortcut a committed swipe stands for. One rule across both finger counts:
 * **you go the way you swipe** — left is back and the desktop to the left, right is
 * forward and the desktop to the right. That matches Windows' own precision-trackpad
 * desktop switching and the direction the back/forward arrows point, so nothing on
 * this screen disagrees with the PC it's driving.
 */
internal fun swipeShortcut(mode: GestureMode, axis: Axis, travel: Float): List<String> {
    val positive = travel > 0 // right on the X axis, down on the Y axis
    return when {
        // Vertical two-finger is the scroll, so this is only ever horizontal.
        mode == GestureMode.TWO_FINGER -> listOf(if (positive) "browser_forward" else "browser_back")
        axis == Axis.HORIZONTAL -> listOf("win", "ctrl", if (positive) "right" else "left")
        positive -> listOf("win", "d") // down: show the desktop
        else -> listOf("win", "tab") // up: Task View
    }
}

/**
 * One pointer as the trackpad's gesture loop needs to see it — the handful of fields
 * Compose's `PointerInputChange` carries that actually decide anything here.
 *
 * [delta] is passed in rather than derived because Compose's `positionChange()` returns
 * `Offset.Zero` once a change has been consumed, and the loop consumes everything it
 * reads. Taking the delta as data means the caller reads it once, before consuming, and
 * the ordering bug that silently zeroed every multi-finger gesture cannot come back.
 */
internal data class Finger(
    val id: Long,
    val position: Offset,
    val delta: Offset,
    val uptimeMs: Long,
    val previousUptimeMs: Long = uptimeMs,
    val pressed: Boolean = true,
)

/**
 * What the pad decided. Everything the surface does to the PC is one of these, so the
 * whole gesture vocabulary can be asserted from a JVM test — no device, no ten fingers.
 * Haptics, echoes and animations are deliberately *not* here: they are derived from
 * these actions by the composable, so feedback can never disagree with what was sent.
 */
internal sealed interface PadAction {
    /** Already scaled by pointer speed and precision gain, and already whole pixels. */
    data class Move(val dx: Int, val dy: Int) : PadAction

    /** Finger travel for [WheelScroll] — 1:1 with the finger, notches come later. */
    data class Scroll(val dy: Float) : PadAction

    data class Click(val button: String) : PadAction

    data class Shortcut(val keys: List<String>) : PadAction

    data object DragStart : PadAction

    data object DragEnd : PadAction

    /** The gesture landed on the scroll rail — arms hold-to-keep-scrolling. */
    data object RailStart : PadAction

    /** Two-finger scroll just committed to the vertical axis — arms the same hold. */
    data object HoldScrollArm : PadAction

    /** Every finger is up. [flingVelocityY] is null when there is nothing to coast. */
    data class End(val flingVelocityY: Float?) : PadAction
}

/**
 * The trackpad's gesture state machine, lifted out of the pointer-input loop so it can
 * be driven by synthetic events.
 *
 * It is fed one gesture: [down], then [event] per pointer event, plus [longPress] when
 * the caller's timeout expires. It owns no coroutines and no clock — timestamps arrive
 * on the [Finger]s — so a whole swipe, scroll, drag or tap is a list of function calls
 * returning a list of [PadAction]s.
 */
internal class PadGesture(
    /** X at which the scroll rail begins; `Float.MAX_VALUE` for a pad with no rail. */
    private val railStartX: Float = Float.MAX_VALUE,
    private val longPressTimeoutMs: Long = 500L,
    private val pointerSpeed: Float = 1f,
    private val precisionPointer: Boolean = false,
) {
    var mode: GestureMode = GestureMode.WAITING
        private set

    /** The axis a multi-finger gesture committed to, once it has travelled far enough. */
    var axis: Axis? = null
        private set

    /** True once every finger has lifted; further calls are no-ops. */
    var finished = false
        private set

    private var downId = 0L
    private var downPosition = Offset.Zero
    private var downUptimeMs = 0L
    private var totalMove = 0f
    private var pointerCount = 1
    private var panX = 0f
    private var panY = 0f
    private var swipeFired = false
    private var carryX = 0f
    private var carryY = 0f
    private val velocity = VelocityTracker()

    /** Rail displacement from touch-down, and when it last actually changed — the two
     *  inputs hold-to-keep-scrolling throttles on. */
    var railOffset = 0f
        private set
    var railLastMoveMs = 0L
        private set

    /** The same pair for two-finger vertical scroll, measured from the gesture's start. */
    val panOffset: Float get() = panY
    var panLastMoveMs = 0L
        private set

    fun down(finger: Finger): List<PadAction> {
        downId = finger.id
        downPosition = finger.position
        downUptimeMs = finger.uptimeMs
        railLastMoveMs = finger.uptimeMs
        panLastMoveMs = finger.uptimeMs
        // Decided by where the finger *landed*, once. A move gesture that wanders into
        // the strip stays a move gesture, and a rail drag that wanders out keeps
        // scrolling.
        return if (finger.position.x >= railStartX) {
            mode = GestureMode.RAIL
            listOf(PadAction.RailStart)
        } else {
            emptyList()
        }
    }

    /** How long the caller may wait for the next event before [longPress] applies. Only
     *  meaningful while WAITING — every other mode has already decided what it is. */
    fun waitBudgetMs(nowMs: Long): Long =
        (longPressTimeoutMs - (nowMs - downUptimeMs)).coerceAtLeast(0)

    /** The long-press timeout elapsed with the finger essentially still: this is a
     *  click-and-drag, not a move. */
    fun longPress(): List<PadAction> {
        if (finished || mode != GestureMode.WAITING) return emptyList()
        mode = GestureMode.DRAG
        return listOf(PadAction.DragStart)
    }

    fun event(fingers: List<Finger>): List<PadAction> {
        if (finished || fingers.isEmpty()) return emptyList()
        val out = mutableListOf<PadAction>()
        val pressed = fingers.filter { it.pressed }
        pointerCount = max(pointerCount, pressed.size)

        if (pressed.isEmpty()) return release(fingers)

        if (mode != GestureMode.DRAG && mode != GestureMode.RAIL) {
            val next = when {
                pressed.size >= 3 -> GestureMode.THREE_FINGER
                // Once a multi-finger gesture has committed — locked an axis, fired its
                // shortcut — a finger leaving must not reclassify it. Lifting the third
                // finger at the end of a desktop swipe is not the start of a page-back.
                axis != null || swipeFired -> mode
                pressed.size == 2 -> GestureMode.TWO_FINGER
                // Back to one finger with nothing decided: a stray contact — a palm, a
                // resting thumb — landed and left again. Take the pointer back rather
                // than freezing it for the rest of the stroke. [pointerCount] does not
                // rewind, so the release still resolves a two-finger tap as a right
                // click.
                mode == GestureMode.TWO_FINGER || mode == GestureMode.THREE_FINGER ->
                    GestureMode.MOVE
                else -> mode
            }
            if (next != mode) {
                // Fingers land milliseconds apart, so a three-finger swipe is briefly a
                // two-finger one. Start the axis lock over when the count changes, or
                // the stray head of the gesture decides its direction.
                mode = next
                panX = 0f; panY = 0f; axis = null
            }
        }

        when (mode) {
            GestureMode.RAIL -> {
                val primary = primaryOf(fingers)
                velocity.addPosition(primary.uptimeMs, primary.position)
                val dy = primary.delta.y
                railOffset = primary.position.y - downPosition.y
                // Any real movement hands control back to 1:1. Sub-pixel jitter from a
                // resting finger must not, or the hold never starts.
                if (abs(dy) >= 1f) railLastMoveMs = primary.uptimeMs
                if (dy != 0f) out += PadAction.Scroll(dy)
            }

            GestureMode.TWO_FINGER, GestureMode.THREE_FINGER ->
                out += multiFinger(pressed)

            GestureMode.WAITING, GestureMode.MOVE, GestureMode.DRAG ->
                out += singleFinger(primaryOf(fingers))
        }
        return out
    }

    /** The finger this gesture started with, or whatever is left if it has lifted. */
    private fun primaryOf(fingers: List<Finger>): Finger =
        fingers.firstOrNull { it.id == downId && it.pressed }
            ?: fingers.firstOrNull { it.pressed }
            ?: fingers.first()

    private fun release(fingers: List<Finger>): List<PadAction> {
        finished = true
        val out = mutableListOf<PadAction>()
        // Both fingers often lift in this very event rather than a prior move — Compose
        // still reports each one's final delta here even though nothing is `pressed`.
        // Dropping it costs the last chunk of a fast flick, and a real two-finger scroll
        // can land under TAP_SLOP and fire a right-click instead.
        if (mode == GestureMode.TWO_FINGER || mode == GestureMode.THREE_FINGER) {
            totalMove += fingers.map { it.delta.getDistance() }.average().toFloat()
        }
        // A scroll still travelling when the finger left keeps going, decaying, the way
        // a wheel does. Only the two gestures that actually scroll — a lifted swipe or a
        // pointer move has nothing to coast.
        val scrolling = mode == GestureMode.RAIL ||
            (mode == GestureMode.TWO_FINGER && axis == Axis.VERTICAL)
        out += PadAction.End(if (scrolling) velocity.calculateVelocity().y else null)
        when {
            // A tap on the rail is not a click: the strip is a scrollbar, and clicking
            // the thing you grab to scroll with is the surprise the zone exists to avoid.
            mode == GestureMode.RAIL -> {}
            mode == GestureMode.DRAG -> out += PadAction.DragEnd
            // A swipe that already fired has said what it meant; a trailing tap-shaped
            // release must not also click.
            swipeFired -> {}
            totalMove >= TAP_SLOP -> {}
            pointerCount == 1 -> out += PadAction.Click("left")
            pointerCount == 2 -> out += PadAction.Click("right")
            else -> out += PadAction.Shortcut(listOf("f5"))
        }
        return out
    }

    private fun multiFinger(pressed: List<Finger>): List<PadAction> {
        val out = mutableListOf<PadAction>()
        val dx = pressed.map { it.delta.x }.average().toFloat()
        val dy = pressed.map { it.delta.y }.average().toFloat()
        panX += dx
        panY += dy
        // The centroid, not one finger: two fingers rarely lift together, and tracking
        // whichever one happens to be first would read the lift as a direction change.
        velocity.addPosition(
            pressed.first().uptimeMs,
            Offset(
                pressed.map { it.position.x }.average().toFloat(),
                pressed.map { it.position.y }.average().toFloat(),
            ),
        )
        // Counts towards totalMove like one-finger movement does: without this a
        // two-finger *scroll* ends with totalMove at 0 and the release fires a
        // right-click.
        totalMove += pressed.map { it.delta.getDistance() }.average().toFloat()

        val committed = axis ?: run {
            if (max(abs(panX), abs(panY)) < AXIS_LOCK_SLOP) return out
            val locked = if (abs(panX) > abs(panY)) Axis.HORIZONTAL else Axis.VERTICAL
            axis = locked
            if (locked == Axis.VERTICAL && mode == GestureMode.TWO_FINGER) {
                // The lock swallowed up to AXIS_LOCK_SLOP px. Replay it, or every scroll
                // opens with a dead zone the finger can feel. `panY` is the whole
                // displacement including this event, so this replays everything before
                // it and the scroll below adds this one.
                if (panY - dy != 0f) out += PadAction.Scroll(panY - dy)
                // A swipe that stops and holds — rather than releasing — should keep
                // scrolling the way the rail does, not sit there until the finger moves.
                panLastMoveMs = pressed.first().uptimeMs
                out += PadAction.HoldScrollArm
            }
            locked
        }

        if (committed == Axis.VERTICAL && mode == GestureMode.TWO_FINGER) {
            if (dy != 0f) {
                out += PadAction.Scroll(dy)
                if (abs(dy) >= 1f) panLastMoveMs = pressed.first().uptimeMs
            }
        } else if (!swipeFired) {
            val travel = if (committed == Axis.HORIZONTAL) panX else panY
            if (abs(travel) >= SWIPE_TRIGGER) {
                swipeFired = true
                out += PadAction.Shortcut(swipeShortcut(mode, committed, travel))
            }
        }
        return out
    }

    private fun singleFinger(primary: Finger): List<PadAction> {
        val delta = primary.delta
        totalMove += delta.getDistance()

        if (mode == GestureMode.WAITING) {
            if (totalMove <= TAP_SLOP) return emptyList()
            mode = GestureMode.MOVE
        }
        // Measured per event rather than smoothed: one frame of lag in the gain is one
        // frame of the pointer moving at the wrong speed, which is what this is for.
        val dtMs = (primary.uptimeMs - primary.previousUptimeMs).coerceAtLeast(1L)
        val scale = sensitivity(pointerSpeed) *
            precisionGain(delta.getDistance() / dtMs, precisionPointer)
        carryX += delta.x * scale
        carryY += delta.y * scale
        // Truncate, then keep the fraction: over a slow drag the leftovers add up to the
        // movement rounding would eat.
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        if (dx == 0 && dy == 0) return emptyList()
        carryX -= dx
        carryY -= dy
        return listOf(PadAction.Move(dx, dy))
    }
}
