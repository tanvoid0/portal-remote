package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastTargetTest {
    @Test
    fun `reads a target list`() {
        val json = JSONObject(
            """
            {"t":"cast_targets","active":"roku:192.168.1.5","scanning":false,"targets":[
              {"id":"mpv","name":"DESKTOP (mpv)","kind":"mpv","seek":true,"volume":true,"status":true},
              {"id":"roku:192.168.1.5","name":"Living Room","kind":"roku","seek":false,"volume":false,"status":true}
            ]}
            """
        )

        val targets = CastTarget.listFromPush(json)
        assertEquals(2, targets.size)
        assertEquals("Living Room", targets[1].name)
        assertEquals("roku:192.168.1.5", CastTarget.activeFromPush(json))
        assertFalse(CastTarget.scanningFromPush(json))
    }

    @Test
    fun `a roku reports no absolute seek`() {
        // The one capability difference that changes what the UI draws: a scrubber the
        // device would ignore is worse than a read-only position.
        val json = JSONObject(
            """{"t":"cast_targets","targets":[
                 {"id":"roku:10.0.0.9","name":"Bedroom","kind":"roku","seek":false,"volume":false,"status":true}]}"""
        )
        val roku = CastTarget.listFromPush(json).single()
        assertFalse(roku.seek)
        assertTrue(roku.status)
        assertTrue(roku.controllable)
    }

    @Test
    fun `an entry with no id is dropped rather than shown as blank`() {
        val json = JSONObject("""{"t":"cast_targets","targets":[{"name":"nameless"}]}""")
        assertTrue(CastTarget.listFromPush(json).isEmpty())
    }

    @Test
    fun `no targets and no active is not an error`() {
        // What a PC that has never scanned answers with.
        val json = JSONObject("""{"t":"cast_targets","scanning":true}""")
        assertTrue(CastTarget.listFromPush(json).isEmpty())
        assertNull(CastTarget.activeFromPush(json))
        assertTrue(CastTarget.scanningFromPush(json))
    }

    @Test
    fun `a cast to a named target carries the name back`() {
        // What "Casting to Living Room" is drawn from.
        val state = CastState.fromAck(
            JSONObject(
                """{"t":"cast_ok","url":"http://192.168.1.20:8766/f/1","via":"roku",
                    "target":"roku:192.168.1.5","name":"Living Room"}"""
            )
        )
        assertEquals("Living Room", state?.targetName)
        assertEquals("roku:192.168.1.5", state?.target)
        // Anything that isn't a shell launch can be driven — including protocols this
        // build of the app has never heard of.
        assertTrue(state!!.controllable)
    }
}
