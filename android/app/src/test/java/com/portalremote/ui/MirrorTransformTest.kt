package com.portalremote.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mirror's touch->desktop mapping, which can't be exercised on an emulator:
 * injecting a real two-finger pinch needs write access to /dev/input, and SELinux
 * denies that on a production image. So the transform gets checked here instead.
 */
class MirrorTransformTest {

    private val area = IntSize(1000, 500)

    @Test
    fun `unzoomed touch maps to its own fraction of the view`() {
        val f = mirrorFraction(Offset(250f, 100f), pan = Offset.Zero, zoom = 1f, area = area)
        assertEquals(0.25f, f.x, 1e-4f)
        assertEquals(0.20f, f.y, 1e-4f)
    }

    @Test
    fun `zooming about a point leaves that point over the same pixel`() {
        // The pinch centroid: whatever is under the fingers must stay under them.
        val centroid = Offset(400f, 300f)
        val before = mirrorFraction(centroid, Offset.Zero, 1f, area)

        val zoom = 2.5f
        val pan = clampPan(centroid - (centroid - Offset.Zero) * zoom, zoom, area)
        val after = mirrorFraction(centroid, pan, zoom, area)

        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
    }

    @Test
    fun `panning stays within the zoomed content`() {
        val zoom = 3f
        // Dragged far past both edges in turn.
        val farPositive = clampPan(Offset(9_999f, 9_999f), zoom, area)
        assertEquals(Offset.Zero, farPositive)

        val farNegative = clampPan(Offset(-9_999f, -9_999f), zoom, area)
        assertEquals(area.width * (1 - zoom), farNegative.x, 1e-4f)
        assertEquals(area.height * (1 - zoom), farNegative.y, 1e-4f)

        // At the most negative pan, the bottom-right corner of the view still shows
        // content — i.e. the fraction there is inside 0..1, not off the end.
        val corner = mirrorFraction(
            Offset(area.width.toFloat(), area.height.toFloat()), farNegative, zoom, area,
        )
        assertTrue(corner.x in 0f..1f, "x fraction ${corner.x} escaped the desktop")
        assertTrue(corner.y in 0f..1f, "y fraction ${corner.y} escaped the desktop")
    }

    @Test
    fun `a fully panned view reaches the far edge of the desktop`() {
        val zoom = 2f
        val pan = clampPan(Offset(-9_999f, -9_999f), zoom, area)
        val corner = mirrorFraction(
            Offset(area.width.toFloat(), area.height.toFloat()), pan, zoom, area,
        )
        // Bottom-right of a fully panned view is the bottom-right of the desktop:
        // without this, part of the screen would be unreachable at any zoom.
        assertEquals(1f, corner.x, 1e-4f)
        assertEquals(1f, corner.y, 1e-4f)
    }
}
