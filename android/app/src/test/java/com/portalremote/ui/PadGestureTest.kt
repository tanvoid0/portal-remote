package com.portalremote.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trackpad's whole gesture vocabulary, driven by synthetic fingers.
 *
 * None of this is reachable on a device from a test: injecting a real three-finger
 * swipe needs write access to /dev/input, which SELinux denies on a production image.
 * So the state machine is fed pointer events directly and asserted on what it decided
 * to send — which is also the only level at which the *combinations* (a swipe that
 * ends in a tap-shaped release, a scroll whose fingers lift in the same event) can be
 * checked at all.
 */
class PadGestureTest {

    // ---- driver ---------------------------------------------------------------

    private class Pad(
        railStartX: Float = Float.MAX_VALUE,
        longPressTimeoutMs: Long = 500L,
        pointerSpeed: Float = 1f,
        precisionPointer: Boolean = false,
    ) {
        val gesture = PadGesture(railStartX, longPressTimeoutMs, pointerSpeed, precisionPointer)
        val actions = mutableListOf<PadAction>()

        private val at = LinkedHashMap<Long, Offset>()
        private var clock = 10_000L
        private var lastDt = 16L

        fun down(x: Float = 100f, y: Float = 200f) = apply {
            at[1L] = Offset(x, y)
            actions += gesture.down(finger(1L, Offset.Zero))
        }

        /** Another finger arrives, resting, to the right of the first. */
        fun addFinger() = apply {
            val id = at.keys.maxOrNull()!! + 1
            at[id] = at.getValue(1L) + Offset(40f * id, 0f)
            step(0f, 0f, 8L)
        }

        /** Every finger down travels [dx],[dy] together, in [steps] events. */
        fun moveBy(dx: Float, dy: Float, steps: Int = 1, dtMs: Long = 16L) = apply {
            repeat(steps) { step(dx / steps, dy / steps, dtMs) }
        }

        fun longPress() = apply { actions += gesture.longPress() }

        /** Everything lifts. [dx],[dy] is the travel Compose reports in that same event,
         *  which for a fast flick is the last and biggest chunk of the gesture. */
        fun lift(dx: Float = 0f, dy: Float = 0f) = apply {
            clock += 8; lastDt = 8
            at.keys.toList().forEach { at[it] = at.getValue(it) + Offset(dx, dy) }
            actions += gesture.event(at.keys.map { finger(it, Offset(dx, dy), pressed = false) })
        }

        /** The most recently added finger leaves; the rest stay down. */
        fun liftFinger() = apply {
            val id = at.keys.maxOrNull()!!
            clock += 8; lastDt = 8
            actions += gesture.event(
                at.keys.map { finger(it, Offset.Zero, pressed = it != id) }
            )
            at.remove(id)
        }

        fun clear() = apply { actions.clear() }

        private fun step(dx: Float, dy: Float, dtMs: Long) {
            clock += dtMs; lastDt = dtMs
            at.keys.toList().forEach { at[it] = at.getValue(it) + Offset(dx, dy) }
            actions += gesture.event(at.keys.map { finger(it, Offset(dx, dy)) })
        }

        private fun finger(id: Long, delta: Offset, pressed: Boolean = true) =
            Finger(id, at.getValue(id), delta, clock, clock - lastDt, pressed)
    }

    private fun List<PadAction>.clicks() =
        filterIsInstance<PadAction.Click>().map { it.button }

    private fun List<PadAction>.shortcuts() =
        filterIsInstance<PadAction.Shortcut>().map { it.keys }

    private fun List<PadAction>.scrolled() =
        filterIsInstance<PadAction.Scroll>().map { it.dy }.sum()

    private fun List<PadAction>.moved() =
        filterIsInstance<PadAction.Move>()
            .fold(Offset.Zero) { acc, m -> acc + Offset(m.dx.toFloat(), m.dy.toFloat()) }

    private fun List<PadAction>.end() = filterIsInstance<PadAction.End>().single()

    // ---- taps -----------------------------------------------------------------

    @Test
    fun `a tap is a left click`() {
        val pad = Pad().down().lift()
        assertEquals(listOf("left"), pad.actions.clicks())
        assertEquals(Offset.Zero, pad.actions.moved(), "a tap must not nudge the pointer")
    }

    @Test
    fun `a tap that wobbles under the slop is still a left click`() {
        val pad = Pad().down().moveBy(6f, 6f, steps = 2).lift()
        assertEquals(listOf("left"), pad.actions.clicks())
    }

    @Test
    fun `two fingers tapping is a right click`() {
        val pad = Pad().down().addFinger().lift()
        assertEquals(listOf("right"), pad.actions.clicks())
    }

    @Test
    fun `three fingers tapping reloads`() {
        val pad = Pad().down().addFinger().addFinger().lift()
        assertEquals(listOf(listOf("f5")), pad.actions.shortcuts())
        assertTrue(pad.actions.clicks().isEmpty())
    }

    @Test
    fun `a drag past the slop clicks nothing on release`() {
        val pad = Pad().down().moveBy(120f, 0f, steps = 6).lift()
        assertTrue(pad.actions.clicks().isEmpty())
    }

    // ---- pointer movement -----------------------------------------------------

    @Test
    fun `movement is scaled down for Windows pointer acceleration`() {
        val pad = Pad().down().moveBy(100f, 40f)
        assertEquals(Offset(50f, 20f), pad.actions.moved())
    }

    @Test
    fun `pointer speed multiplies the base scaling`() {
        val pad = Pad(pointerSpeed = 2f).down().moveBy(100f, 0f)
        assertEquals(Offset(100f, 0f), pad.actions.moved())
    }

    @Test
    fun `the tap slop is swallowed, not replayed as a jump`() {
        // 10px is under the slop, so nothing has gone out yet; the pointer must not
        // then lurch by the whole 10px once the gesture resolves to a move.
        val pad = Pad().down().moveBy(10f, 0f)
        assertEquals(Offset.Zero, pad.actions.moved())
        pad.moveBy(20f, 0f)
        assertEquals(Offset(10f, 0f), pad.actions.moved(), "only the second event's travel")
    }

    @Test
    fun `precision damps a slow finger and leaves a fast one alone`() {
        // Same 40px of travel; only the time it takes differs.
        val slow = Pad(precisionPointer = true).down().moveBy(40f, 0f, dtMs = 400)
        val fast = Pad(precisionPointer = true).down().moveBy(40f, 0f, dtMs = 2)
        assertTrue(slow.actions.moved().x < fast.actions.moved().x)
        assertEquals(20f, fast.actions.moved().x, "a fast finger is unchanged at 1:1 gain")
    }

    @Test
    fun `sub-pixel travel is carried rather than rounded away`() {
        val pad = Pad(precisionPointer = true).down().moveBy(40f, 0f).clear()
        // 2px per event at 40ms is 0.05px/ms — fully damped, so 0.35px of pointer
        // travel per event. Truncated event by event that is zero forever, and the
        // pointer simply refuses to move at the speeds precision exists to serve.
        pad.moveBy(40f, 0f, steps = 20, dtMs = 40)
        assertEquals(7f, pad.actions.moved().x)
    }

    // ---- hold to drag ---------------------------------------------------------

    @Test
    fun `holding still starts a drag, and releasing ends it without clicking`() {
        val pad = Pad().down().longPress()
        assertEquals(listOf(PadAction.DragStart), pad.actions.filterIsInstance<PadAction.DragStart>())
        pad.moveBy(60f, 0f).lift()
        assertTrue(pad.actions.contains(PadAction.DragEnd))
        assertTrue(pad.actions.clicks().isEmpty(), "the button was already down")
        assertEquals(30f, pad.actions.moved().x, "a drag still moves the pointer")
    }

    @Test
    fun `a second finger during a drag does not turn it into a scroll`() {
        val pad = Pad().down().longPress().addFinger().moveBy(0f, 60f)
        assertEquals(GestureMode.DRAG, pad.gesture.mode)
        assertEquals(0f, pad.actions.scrolled())
    }

    @Test
    fun `the long-press budget shrinks as the finger rests`() {
        val pad = Pad(longPressTimeoutMs = 500L).down()
        assertEquals(500L, pad.gesture.waitBudgetMs(10_000L))
        assertEquals(100L, pad.gesture.waitBudgetMs(10_400L))
        assertEquals(0L, pad.gesture.waitBudgetMs(11_000L), "never negative")
    }

    // ---- the scroll rail ------------------------------------------------------

    @Test
    fun `landing on the rail scrolls one-to-one and never clicks`() {
        val pad = Pad(railStartX = 300f).down(x = 320f).moveBy(0f, 100f, steps = 5).lift()
        assertTrue(pad.actions.contains(PadAction.RailStart))
        assertEquals(100f, pad.actions.scrolled())
        assertEquals(Offset.Zero, pad.actions.moved())
        assertTrue(pad.actions.clicks().isEmpty(), "the strip is a scrollbar, not a button")
    }

    @Test
    fun `a bare tap on the rail clicks nothing`() {
        val pad = Pad(railStartX = 300f).down(x = 320f).lift()
        assertTrue(pad.actions.clicks().isEmpty())
    }

    @Test
    fun `the rail is decided by where the finger landed, not where it goes`() {
        val outside = Pad(railStartX = 300f).down(x = 100f).moveBy(250f, 0f)
        assertEquals(0f, outside.actions.scrolled(), "wandering into the strip still moves")

        val inside = Pad(railStartX = 300f).down(x = 320f).moveBy(-250f, 60f)
        assertEquals(60f, inside.actions.scrolled(), "wandering out of it still scrolls")
    }

    @Test
    fun `a rail flick coasts, a parked rail finger does not`() {
        val thrown = Pad(railStartX = 300f).down(x = 320f).moveBy(0f, 200f, steps = 10).lift()
        assertNotNull(thrown.actions.end().flingVelocityY)
        assertTrue(thrown.actions.end().flingVelocityY!! > 0f, "thrown downwards")
    }

    @Test
    fun `the rail tracks its displacement from touch-down for hold-to-scroll`() {
        val pad = Pad(railStartX = 300f).down(x = 320f, y = 200f).moveBy(0f, 140f, steps = 7)
        assertEquals(140f, pad.gesture.railOffset)
    }

    // ---- two-finger scroll ----------------------------------------------------

    @Test
    fun `two fingers scroll the full distance they travelled`() {
        // Including the travel the axis lock swallowed: a scroll that opens with a
        // 24px dead zone is one the finger can feel.
        val pad = Pad().down().addFinger().moveBy(0f, 100f, steps = 10)
        assertEquals(100f, pad.actions.scrolled())
        assertEquals(Axis.VERTICAL, pad.gesture.axis)
    }

    @Test
    fun `a two-finger scroll arms hold-to-keep-scrolling exactly once`() {
        val pad = Pad().down().addFinger().moveBy(0f, 200f, steps = 20)
        assertEquals(1, pad.actions.count { it == PadAction.HoldScrollArm })
        assertEquals(200f, pad.gesture.panOffset, "displacement from where the gesture began")
    }

    @Test
    fun `a two-finger scroll coasts on release and never right-clicks`() {
        val pad = Pad().down().addFinger().moveBy(0f, 200f, steps = 10).lift()
        assertTrue(pad.actions.end().flingVelocityY!! > 0f)
        assertTrue(pad.actions.clicks().isEmpty())
    }

    @Test
    fun `fingers that lift in the same event still count their last chunk`() {
        // Compose reports the final travel on the event where `pressed` goes false.
        // Dropping it lets a real flick land under the tap slop and fire a right click.
        val pad = Pad().down().addFinger().lift(dy = 60f)
        assertTrue(pad.actions.clicks().isEmpty())
    }

    @Test
    fun `two fingers moving nowhere is still a right click`() {
        val pad = Pad().down().addFinger().moveBy(0f, 4f, steps = 2).lift()
        assertEquals(listOf("right"), pad.actions.clicks())
    }

    // ---- two-finger swipe (the back/forward pair) -----------------------------

    @Test
    fun `two fingers swiping right go forward, and click nothing`() {
        val pad = Pad().down().addFinger().moveBy(120f, 0f, steps = 6).lift()
        assertEquals(listOf(listOf("browser_forward")), pad.actions.shortcuts())
        assertTrue(pad.actions.clicks().isEmpty(), "a swipe must never also open a context menu")
    }

    @Test
    fun `two fingers swiping left go back`() {
        val pad = Pad().down().addFinger().moveBy(-120f, 0f, steps = 6).lift()
        assertEquals(listOf(listOf("browser_back")), pad.actions.shortcuts())
    }

    @Test
    fun `a swipe fires once however far the fingers carry on`() {
        val pad = Pad().down().addFinger().moveBy(600f, 0f, steps = 30).lift()
        assertEquals(1, pad.actions.shortcuts().size)
    }

    @Test
    fun `a horizontal two-finger gesture never scrolls`() {
        val pad = Pad().down().addFinger().moveBy(200f, 0f, steps = 10).lift()
        assertEquals(0f, pad.actions.scrolled())
        assertNull(pad.actions.end().flingVelocityY, "nothing horizontal to coast")
    }

    @Test
    fun `a swipe too short to fire does nothing at all`() {
        val pad = Pad().down().addFinger().moveBy(60f, 0f, steps = 3).lift()
        assertTrue(pad.actions.shortcuts().isEmpty())
        assertTrue(pad.actions.clicks().isEmpty(), "60px is a swipe that missed, not a tap")
    }

    @Test
    fun `the axis locks on whichever way the fingers actually went`() {
        // A swipe with scroll drift in it, and a scroll with swipe drift in it.
        val across = Pad().down().addFinger().moveBy(120f, 30f, steps = 6)
        assertEquals(Axis.HORIZONTAL, across.gesture.axis)
        assertEquals(0f, across.actions.scrolled())

        val down = Pad().down().addFinger().moveBy(30f, 120f, steps = 6)
        assertEquals(Axis.VERTICAL, down.gesture.axis)
        assertEquals(120f, down.actions.scrolled())
        assertTrue(down.actions.shortcuts().isEmpty())
    }

    @Test
    fun `nothing commits before the axis-lock slop`() {
        val pad = Pad().down().addFinger().moveBy(20f, 20f, steps = 4)
        assertNull(pad.gesture.axis)
        assertEquals(0f, pad.actions.scrolled())
        assertTrue(pad.actions.shortcuts().isEmpty())
    }

    // ---- three-finger swipes --------------------------------------------------

    @Test
    fun `three fingers switch desktops the way they swipe`() {
        val right = Pad().down().addFinger().addFinger().moveBy(120f, 0f, steps = 6)
        assertEquals(listOf(listOf("win", "ctrl", "right")), right.actions.shortcuts())

        val left = Pad().down().addFinger().addFinger().moveBy(-120f, 0f, steps = 6)
        assertEquals(listOf(listOf("win", "ctrl", "left")), left.actions.shortcuts())
    }

    @Test
    fun `three fingers up is task view and down is the desktop`() {
        val up = Pad().down().addFinger().addFinger().moveBy(0f, -120f, steps = 6)
        assertEquals(listOf(listOf("win", "tab")), up.actions.shortcuts())

        val down = Pad().down().addFinger().addFinger().moveBy(0f, 120f, steps = 6)
        assertEquals(listOf(listOf("win", "d")), down.actions.shortcuts())
    }

    @Test
    fun `three fingers never scroll`() {
        val pad = Pad().down().addFinger().addFinger().moveBy(0f, 200f, steps = 10).lift()
        assertEquals(0f, pad.actions.scrolled())
        assertNull(pad.actions.end().flingVelocityY)
    }

    @Test
    fun `the third finger landing late restarts the axis decision`() {
        // Fingers land milliseconds apart, so the head of a three-finger swipe is a
        // two-finger one. That stray head must not decide the direction.
        val pad = Pad().down().addFinger().moveBy(0f, 40f, steps = 2)
        assertEquals(Axis.VERTICAL, pad.gesture.axis, "reads as a scroll while two fingers")
        pad.clear().addFinger()
        assertNull(pad.gesture.axis)
        pad.moveBy(120f, 0f, steps = 6)
        assertEquals(listOf(listOf("win", "ctrl", "right")), pad.actions.shortcuts())
    }

    // ---- fingers coming and going mid-gesture ---------------------------------

    @Test
    fun `a stray contact does not freeze the pointer for the rest of the stroke`() {
        // A palm or a resting thumb brushing the pad mid-drag. Before, the gesture was
        // stuck as a two-finger one until every finger lifted — the pointer simply died.
        val pad = Pad().down().moveBy(60f, 0f).addFinger().liftFinger().clear()
        pad.moveBy(100f, 0f)
        assertEquals(GestureMode.MOVE, pad.gesture.mode)
        assertEquals(50f, pad.actions.moved().x)
        assertEquals(0f, pad.actions.scrolled())
    }

    @Test
    fun `a two-finger tap whose fingers lift one at a time is still a right click`() {
        val pad = Pad().down().addFinger().liftFinger().lift()
        assertEquals(listOf("right"), pad.actions.clicks())
    }

    @Test
    fun `lifting one finger mid-scroll keeps scrolling, it does not become a swipe`() {
        val pad = Pad().down().addFinger().moveBy(0f, 100f, steps = 5).liftFinger()
        assertEquals(Axis.VERTICAL, pad.gesture.axis)
        pad.moveBy(0f, 60f, steps = 3)
        assertEquals(160f, pad.actions.scrolled())
        assertTrue(pad.actions.shortcuts().isEmpty())
    }

    @Test
    fun `lifting the third finger after a desktop swipe does not fire a page-back`() {
        val pad = Pad().down().addFinger().addFinger().moveBy(-120f, 0f, steps = 6)
        assertEquals(listOf(listOf("win", "ctrl", "left")), pad.actions.shortcuts())
        pad.liftFinger().moveBy(-120f, 0f, steps = 6).lift()
        assertEquals(1, pad.actions.shortcuts().size, "one shortcut per gesture")
    }

    // ---- lifecycle ------------------------------------------------------------

    @Test
    fun `a gesture ends exactly once and then goes quiet`() {
        val pad = Pad().down().moveBy(80f, 0f).lift()
        assertEquals(1, pad.actions.filterIsInstance<PadAction.End>().size)
        assertTrue(pad.gesture.finished)
        pad.clear().moveBy(500f, 500f)
        assertTrue(pad.actions.isEmpty(), "a finished gesture must not keep driving the PC")
    }

    @Test
    fun `a long press after the gesture resolved is ignored`() {
        val pad = Pad().down().moveBy(80f, 0f).clear().longPress()
        assertTrue(pad.actions.isEmpty())
        assertEquals(GestureMode.MOVE, pad.gesture.mode)
    }
}
