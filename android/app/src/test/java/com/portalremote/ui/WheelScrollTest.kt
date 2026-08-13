package com.portalremote.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The notch accumulator is what both scrolling surfaces are now built on, and its one
 * job is not to lose travel: an early version of this logic dropped every sub-notch
 * movement, which is invisible until someone tries to scroll slowly.
 *
 * `fling` is not exercised here — it needs a frame clock. The `by`/carry path is the
 * part that can silently lose input.
 */
class WheelScrollTest {

    private class Recorder {
        val notches = mutableListOf<Triple<Int, Int, Boolean>>()
        val wheel = WheelScroll(CoroutineScope(Dispatchers.Unconfined)) { dx, dy, coasting ->
            notches += Triple(dx, dy, coasting)
        }
    }

    @Test
    fun `sub-notch travel is carried, not dropped`() {
        val r = Recorder()
        repeat(5) { r.wheel.by(dy = SCROLL_PX_PER_NOTCH / 5f) }
        assertEquals(listOf(Triple(0, 1, false)), r.notches, "five fifths of a notch is a notch")
    }

    @Test
    fun `a long drag emits every notch it covers`() {
        val r = Recorder()
        r.wheel.by(dy = SCROLL_PX_PER_NOTCH * 3.5f)
        assertEquals(listOf(Triple(0, 3, false)), r.notches)
        // The half notch left over still counts towards the next one.
        r.wheel.by(dy = SCROLL_PX_PER_NOTCH * 0.5f)
        assertEquals(Triple(0, 1, false), r.notches.last())
    }

    @Test
    fun `both axes accumulate independently`() {
        val r = Recorder()
        r.wheel.by(dx = SCROLL_PX_PER_NOTCH, dy = SCROLL_PX_PER_NOTCH / 2f)
        assertEquals(listOf(Triple(1, 0, false)), r.notches, "only the x axis has reached a notch")
        r.wheel.by(dy = SCROLL_PX_PER_NOTCH / 2f)
        assertEquals(Triple(0, 1, false), r.notches.last())
    }

    @Test
    fun `negative travel scrolls the other way and carries too`() {
        val r = Recorder()
        r.wheel.by(dy = -SCROLL_PX_PER_NOTCH * 0.75f)
        assertTrue(r.notches.isEmpty(), "three quarters of a notch is not a notch")
        r.wheel.by(dy = -SCROLL_PX_PER_NOTCH * 0.75f)
        assertEquals(listOf(Triple(0, -1, false)), r.notches)
    }

    @Test
    fun `momentum off never coasts`() {
        val r = Recorder()
        r.wheel.fling(0f, 5000f, MomentumLevel.OFF)
        assertTrue(r.notches.isEmpty())
    }

    @Test
    fun `a parked finger is not a throw`() {
        val r = Recorder()
        // Well under the fling threshold on both axes: releasing a scroll that had
        // already stopped must not restart it.
        r.wheel.fling(10f, 10f, MomentumLevel.STANDARD)
        assertTrue(r.notches.isEmpty())
    }
}
