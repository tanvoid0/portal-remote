package com.portalremote.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for a real bug: a deliberate vertical two-finger swipe was landing on
 * ZOOM (and so never scrolling) whenever finger-spread jitter crossed the slop a
 * frame before the pan signal did, even though the pan travel was the bigger of the
 * two. The fix compares which signal dominates instead of which crosses first.
 */
class TwoFingerIntentTest {

    @Test
    fun `neither signal past the slop stays undecided`() {
        assertEquals(TwoFingerIntent.UNDECIDED, classifyTwoFingerIntent(5f, 5f, zoomed = false))
    }

    @Test
    fun `a swipe with a little spread jitter still scrolls`() {
        // The exact shape of the original bug: pinch noise (17px) crosses the 16px
        // slop, but the deliberate vertical travel (40px) is more than double it.
        assertEquals(
            TwoFingerIntent.SCROLL,
            classifyTwoFingerIntent(pinchTravel = 17f, panTravel = 40f, zoomed = false),
        )
    }

    @Test
    fun `a real pinch still wins when it dominates`() {
        assertEquals(
            TwoFingerIntent.ZOOM,
            classifyTwoFingerIntent(pinchTravel = 40f, panTravel = 17f, zoomed = false),
        )
    }

    @Test
    fun `pan becomes a drag once zoomed in`() {
        assertEquals(
            TwoFingerIntent.PAN,
            classifyTwoFingerIntent(pinchTravel = 5f, panTravel = 40f, zoomed = true),
        )
    }
}
