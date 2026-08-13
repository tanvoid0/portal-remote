package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastStateTest {
    @Test
    fun `a receiver cast is controllable`() {
        val state = CastState.fromAck(
            JSONObject("""{"t":"cast_ok","url":"https://example.com/clip.mp4","via":"receiver"}""")
        )
        assertEquals("https://example.com/clip.mp4", state?.url)
        assertTrue(state!!.controllable)
    }

    @Test
    fun `a shell cast is not`() {
        // The PC handed the link to whatever is registered for it and kept no handle
        // on it — transport buttons here would only ever error.
        val state = CastState.fromAck(
            JSONObject("""{"t":"cast_ok","url":"https://example.com/clip.mp4","via":"shell"}""")
        )
        assertFalse(state!!.controllable)
    }

    @Test
    fun `an mpv cast is controllable too`() {
        // mpv reports its position and takes the same transport commands, so the
        // transport row belongs here as much as on a receiver page.
        val state = CastState.fromAck(
            JSONObject("""{"t":"cast_ok","url":"https://example.com/stream.m3u8","via":"mpv"}""")
        )
        assertTrue(state!!.controllable)
    }

    @Test
    fun `an ack without a url is not a cast`() {
        assertNull(CastState.fromAck(JSONObject("""{"t":"cast_ok"}""")))
    }

    @Test
    fun `falls back to the url when there is no title`() {
        val state = CastState("https://example.com/clip.mp4", CastState.RECEIVER)
        assertEquals("https://example.com/clip.mp4", state.label)
        assertEquals("Clip", state.copy(title = "Clip").label)
    }
}

class CastStatusTest {
    private fun push(status: String) =
        JSONObject("""{"t":"cast_status","receiver":true,"status":$status}""")

    /** `receivedAt` always passed explicitly: its default reads `SystemClock`, which is
     *  a stub that throws off-device. */
    private fun parse(json: JSONObject) = CastStatus.fromPush(json, receivedAt = 0L)

    @Test
    fun `reads a playing report, converting seconds to milliseconds`() {
        val state = CastStatus.fromPush(
            push("""{"paused":false,"position":12.5,"duration":600.0,"volume":0.8,"muted":false}"""),
            receivedAt = 1_000L,
        )!!
        assertEquals(12_500L, state.positionMs)
        assertEquals(600_000L, state.durationMs)
        assertTrue(state.playing)
        assertTrue(state.seekable)
    }

    @Test
    fun `no receiver and no report both mean nothing to draw`() {
        assertNull(parse(JSONObject("""{"t":"cast_status","receiver":false}""")))
        assertNull(parse(JSONObject("""{"t":"cast_status","receiver":true}""")))
    }

    @Test
    fun `an untouched page is not playing, whatever it says about pause`() {
        // A browser reports `paused:false` the moment play() is called, but nothing
        // moves until the page has been interacted with — ticking a bar against that
        // would show a playhead advancing through a video that is still frozen.
        val state = parse(push("""{"paused":false,"waitingForGesture":true,"position":0,"duration":10}"""))!!
        assertFalse(state.playing)
    }

    @Test
    fun `a paused playhead does not move`() {
        val state = CastStatus.fromPush(
            push("""{"paused":true,"position":30.0,"duration":600.0}"""),
            receivedAt = 1_000L,
        )!!
        assertEquals(30_000L, state.positionAt(9_000L))
    }

    @Test
    fun `a playing playhead advances with the phone's own clock`() {
        val state = CastStatus.fromPush(
            push("""{"paused":false,"position":30.0,"duration":600.0}"""),
            receivedAt = 1_000L,
        )!!
        assertEquals(34_000L, state.positionAt(5_000L))
    }

    @Test
    fun `interpolation stops at the end rather than running past it`() {
        val state = CastStatus.fromPush(
            push("""{"paused":false,"position":9.0,"duration":10.0}"""),
            receivedAt = 1_000L,
        )!!
        assertEquals(10_000L, state.positionAt(60_000L))
    }

    @Test
    fun `a live stream has no bar to scrub`() {
        // `isFinite(video.duration)` is false for a live stream, and the receiver
        // sends 0 for it — a slider drawn against that range is a dead control.
        val state = parse(push("""{"paused":false,"position":5.0,"duration":0}"""))!!
        assertFalse(state.seekable)
    }
}
