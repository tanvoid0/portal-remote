package com.portalremote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gain curve is what makes a 16px close button hittable on a 3440px desktop shown
 * at phone width. Get it wrong in the wrong direction and the pad either can't do fine
 * work at all (gain too flat) or crawls across the screen (gain too aggressive).
 */
class PrecisionGainTest {

    @Test
    fun `disabled is exactly the old linear behaviour`() {
        assertEquals(1f, precisionGain(0f, enabled = false))
        assertEquals(1f, precisionGain(5f, enabled = false))
    }

    @Test
    fun `a still finger is damped, not stopped`() {
        val gain = precisionGain(0f, enabled = true)
        assertTrue(gain > 0f, "a damped pointer must still move")
        assertTrue(gain < 0.5f, "expected real damping at rest, got $gain")
    }

    @Test
    fun `fast travel is unchanged`() {
        // Anything at or past the fast threshold must be 1.0 — crossing the screen can't
        // get slower than it was before this feature existed.
        assertEquals(1f, precisionGain(1.2f, enabled = true))
        assertEquals(1f, precisionGain(40f, enabled = true))
    }

    @Test
    fun `gain rises with speed`() {
        val samples = listOf(0f, 0.1f, 0.3f, 0.6f, 0.9f, 1.2f).map { precisionGain(it, enabled = true) }
        samples.zipWithNext { slower, faster ->
            assertTrue(faster >= slower, "gain must never fall as the finger speeds up: $samples")
        }
        assertTrue(samples.first() < samples.last(), "curve is flat: $samples")
    }
}
