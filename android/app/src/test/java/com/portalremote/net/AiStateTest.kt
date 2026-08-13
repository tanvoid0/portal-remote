package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiStateTest {
    @Test
    fun `ready is ready`() {
        val state = AiState.fromPush(JSONObject("""{"t":"ai_state","state":"ready","canStart":false}"""))!!
        assertTrue(state.ready)
        assertEquals("Assistant ready", state.headline)
        assertNull(state.detail)
    }

    @Test
    fun `unavailable keeps the reason`() {
        // The detail names the real cause, and it is the only thing on that screen worth
        // reading — "unavailable" on its own tells nobody what to do.
        val state = AiState.fromPush(
            JSONObject("""{"t":"ai_state","state":"unavailable","detail":"nothing on 127.0.0.1:18410"}""")
        )!!
        assertFalse(state.ready)
        assertEquals("Assistant is not running", state.headline)
        assertEquals("nothing on 127.0.0.1:18410", state.detail)
    }

    @Test
    fun `unconfigured is a setup step, not a failure`() {
        val state = AiState.fromPush(JSONObject("""{"t":"ai_state","state":"unconfigured"}"""))!!
        assertEquals("Assistant not set up", state.headline)
    }

    @Test
    fun `starting wants a spinner, not an error`() {
        val state = AiState.fromPush(JSONObject("""{"t":"ai_state","state":"starting"}"""))!!
        assertTrue(state.starting)
        assertFalse(state.ready)
    }

    @Test
    fun `a push with no state is not one`() {
        assertNull(AiState.fromPush(JSONObject("""{"t":"ai_state"}""")))
    }
}
