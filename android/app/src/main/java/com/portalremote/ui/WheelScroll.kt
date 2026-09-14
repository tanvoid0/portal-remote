package com.portalremote.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import com.portalremote.ui.theme.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Pixels of finger travel per wheel notch. Shared by the trackpad and the mirror so
 *  a scroll feels the same distance on both. */
internal const val SCROLL_PX_PER_NOTCH = 60f

/** Wheel delta for one notch — the Windows convention the server passes straight to
 *  `MOUSEEVENTF_WHEEL`. */
internal const val WHEEL_DELTA = 120

/** Finger speed (px/s) at release below which a scroll just stops. Under this it reads
 *  as "I let go", not "I threw it". */
private const val MIN_FLING_VELOCITY = 300f

/** Ceiling on fling velocity. A flick can measure several thousand px/s and every bit
 *  of it becomes wheel messages on the socket; past this it is a burst, not a scroll. */
private const val MAX_FLING_VELOCITY = 6000f

/**
 * How far a flick keeps scrolling after the finger leaves. Exposed as a preference
 * rather than a constant because the right answer depends on what the user scrolls:
 * a long document wants a throw that carries, a code editor wants it to stop where it
 * was put. Stored by [name], like [MirrorPreset].
 */
enum class MomentumLevel(val label: String, val friction: Float) {
    OFF("Off", 0f),
    SHORT("Short", 3f),
    STANDARD("Standard", 1.6f),
    LONG("Long", 0.8f);

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/**
 * Finger travel in, wheel notches out — with momentum.
 *
 * One implementation for both scrolling surfaces (the trackpad's rail and two-finger
 * scroll, the mirror's two-finger scroll): they were separately accumulating the same
 * remainder against the same constant, which is exactly the kind of duplication that
 * ends with two surfaces scrolling at subtly different speeds.
 *
 * [onNotches] receives *whole notches* in each axis, and whether they came from the
 * coasting animation rather than a finger. The caller applies scroll direction and the
 * wheel-delta scaling, since that is where the user's natural-scroll preference lives.
 */
internal class WheelScroll(
    private val scope: CoroutineScope,
    private val onNotches: (dx: Int, dy: Int, coasting: Boolean) -> Unit,
) {
    private var carryX = 0f
    private var carryY = 0f
    private val coast = Animatable(Offset.Zero, Offset.VectorConverter)
    private var job: Job? = null

    /** Feed finger (or coasting) travel in pixels. Sub-notch movement is carried, not
     *  discarded, so a slow scroll still adds up to a notch eventually. */
    fun by(dx: Float = 0f, dy: Float = 0f, coasting: Boolean = false) {
        carryX += dx
        carryY += dy
        val nx = (carryX / SCROLL_PX_PER_NOTCH).toInt()
        val ny = (carryY / SCROLL_PX_PER_NOTCH).toInt()
        if (nx == 0 && ny == 0) return
        carryX -= nx * SCROLL_PX_PER_NOTCH
        carryY -= ny * SCROLL_PX_PER_NOTCH
        onNotches(nx, ny, coasting)
    }

    /** Stop any coast. Called on every touch-down: catching a moving scroll is how a
     *  hand stops a spinning wheel, and it has to work before the new gesture is even
     *  classified. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Keep scrolling after the finger leaves, decaying to a stop. Velocities are in
     * px/s, straight from a `VelocityTracker`; a release slower than [MIN_FLING_VELOCITY]
     * on both axes is a park, not a throw, and does nothing.
     */
    fun fling(velocityX: Float, velocityY: Float, level: MomentumLevel) {
        if (level == MomentumLevel.OFF) return
        if (abs(velocityX) < MIN_FLING_VELOCITY && abs(velocityY) < MIN_FLING_VELOCITY) return
        stop()
        job = scope.launch {
            var last = Offset.Zero
            coast.snapTo(Offset.Zero)
            coast.animateDecay(
                Offset(
                    velocityX.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY),
                    velocityY.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY),
                ),
                // Stops once it is shedding less than a notch a second: below that it
                // emits nothing anyway, and a wheel that never quite settles reads as a
                // stuck scroll rather than as momentum.
                exponentialDecay(
                    frictionMultiplier = level.friction,
                    absVelocityThreshold = SCROLL_PX_PER_NOTCH,
                ),
            ) {
                by(value.x - last.x, value.y - last.y, coasting = true)
                last = value
            }
        }
    }
}

/**
 * Hold-to-keep-scrolling — shared by every scrolling surface in the app: the
 * trackpad's rail and two-finger scroll, and the mirror's (see [startHoldToScroll]).
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
 * two-finger vertical scroll, on both the trackpad and the mirror, share one rate
 * curve instead of four that could drift apart. [offset] is displacement from wherever the caller considers the gesture's
 * start — the rail's distance from touch-down, or the two-finger case's cumulative
 * pan since the axis locked; [lastMoveMs] is the last time that offset actually
 * changed, which is what "held still" means here.
 */
internal fun startHoldToScroll(
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
