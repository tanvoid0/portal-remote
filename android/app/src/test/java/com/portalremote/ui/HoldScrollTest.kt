package com.portalremote.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rate curve behind "stop moving and it keeps scrolling", shared by the scroll rail
 * and two-finger vertical scroll. The tick loop it drives can't be tested (it is a
 * coroutine reading the system clock), but the curve is where the feel lives: a dead
 * zone so a resting finger does nothing, a squared ramp so the near end is a crawl you
 * can stop on a line, and a ceiling so the far end is fast rather than a burst.
 */
class HoldScrollTest {

    @Test
    fun `a finger parked where it landed scrolls nothing`() {
        assertEquals(0f, holdScrollStep(0f))
        assertEquals(0f, holdScrollStep(19f), "inside the dead zone")
        assertEquals(0f, holdScrollStep(-19f))
        assertTrue(holdScrollStep(21f) > 0f, "just outside it")
    }

    @Test
    fun `it scrolls the way the finger was pushed`() {
        assertTrue(holdScrollStep(120f) > 0f)
        assertEquals(-holdScrollStep(120f), holdScrollStep(-120f), "symmetric")
    }

    @Test
    fun `the near end crawls compared with the far end`() {
        // Half the travel past the dead zone is a quarter of the rate, not half — this
        // is the difference between stopping on a line and overshooting the page.
        val half = holdScrollStep(20f + 80f)
        val full = holdScrollStep(20f + 160f)
        assertEquals(full / 4f, half, 1e-3f)
    }

    @Test
    fun `the rate is capped however far the finger goes`() {
        val atFullSpeed = holdScrollStep(20f + 160f)
        assertEquals(atFullSpeed, holdScrollStep(2000f), "no runaway past the ceiling")
        // 1600px/s over a 16ms tick, in the finger pixels WheelScroll takes.
        assertEquals(1600f * 0.016f, atFullSpeed, 1e-3f)
    }

    @Test
    fun `a tick's travel scales with how long the tick was`() {
        assertEquals(2f * holdScrollStep(100f, 16L), holdScrollStep(100f, 32L), 1e-3f)
    }

    @Test
    fun `full tilt is about twenty-six notches a second`() {
        val notchesPerSecond = abs(holdScrollStep(2000f)) / SCROLL_PX_PER_NOTCH * (1000f / 16f)
        assertTrue(notchesPerSecond in 25f..27f, "was $notchesPerSecond")
    }
}
