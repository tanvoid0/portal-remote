package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerTimerStateTest {
    @Test
    fun `reads a pending timer`() {
        val state = PowerTimerState.fromPush(
            JSONObject("""{"t":"power_timer","mode":"shutdown","endsAt":1700000000000}"""),
        )
        assertEquals("shutdown", state?.mode)
        assertEquals(1700000000000L, state?.endsAtMs)
    }

    @Test
    fun `a null mode means nothing pending`() {
        // What the server sends between timers, not a malformed push.
        assertNull(PowerTimerState.fromPush(JSONObject("""{"t":"power_timer","mode":null,"endsAt":null}""")))
    }

    @Test
    fun `a missing mode means the same thing`() {
        assertNull(PowerTimerState.fromPush(JSONObject("""{"t":"power_timer"}""")))
    }
}
