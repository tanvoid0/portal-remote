package com.portalremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

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
