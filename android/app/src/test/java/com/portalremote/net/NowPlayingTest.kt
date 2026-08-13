package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The progress bar is drawn from a position the PC sent some time ago, advanced
 * against this phone's clock. Getting that wrong is a bar that runs off the end, or
 * one that creeps forward while the music is paused. Kept in step with the payload
 * `NowPlaying.Snapshot()` builds on the server (Media/NowPlaying.cs).
 */
class NowPlayingTest {

    /** `receivedAt` is passed everywhere rather than defaulted: the default reads
     *  `SystemClock`, which is a stub that throws on the JVM. */
    private fun parse(vararg fields: Pair<String, Any?>) = NowPlaying.fromPush(
        JSONObject(mapOf("t" to "now_playing", "active" to true) + fields.toMap()),
        receivedAt = 0,
    )

    @Test
    fun `nothing playing parses to nothing`() {
        assertNull(NowPlaying.fromPush(JSONObject("""{"t":"now_playing","active":false}"""), receivedAt = 0))
    }

    @Test
    fun `a track push carries what the card draws`() {
        val state = parse(
            "title" to "Windowlicker",
            "artist" to "Aphex Twin",
            "album" to "",
            "app" to "Spotify",
            "playing" to true,
            "positionMs" to 61_000,
            "durationMs" to 366_000,
            "canSeek" to true,
            "art" to 3,
        )!!

        assertEquals("Windowlicker", state.title)
        assertEquals("Aphex Twin", state.artist)
        // Blank fields are absent, not empty — otherwise the subtitle grows a
        // trailing separator with nothing after it.
        assertNull(state.album)
        assertEquals("Aphex Twin", state.subtitle)
        assertEquals(3, state.art)
        assertTrue(state.canSeek)
    }

    @Test
    fun `a track with no cover has no revision to fetch`() {
        val state = parse("title" to "Live stream", "art" to JSONObject.NULL)!!
        assertNull(state.art)
    }

    @Test
    fun `transport buttons stay usable when the server says nothing about them`() {
        val state = parse("title" to "x")!!
        assertTrue(state.canNext)
        assertTrue(state.canPrev)
    }

    @Test
    fun `the playhead advances while playing and stops when paused`() {
        val playing = state(playing = true, positionMs = 10_000, durationMs = 200_000, receivedAt = 1_000)
        assertEquals(10_000, playing.positionAt(1_000))
        assertEquals(15_000, playing.positionAt(6_000))

        val paused = playing.copy(playing = false)
        assertEquals(10_000, paused.positionAt(6_000))
    }

    @Test
    fun `the playhead never runs past the end of the track`() {
        // The socket can go quiet — phone asleep, Wi-Fi blip — long after the track
        // that was playing ended.
        val state = state(playing = true, positionMs = 190_000, durationMs = 200_000, receivedAt = 0)
        assertEquals(200_000, state.positionAt(3_600_000))
    }

    @Test
    fun `a live stream has no end to clamp to`() {
        val state = state(playing = true, positionMs = 0, durationMs = 0, receivedAt = 0)
        assertEquals(45_000, state.positionAt(45_000))
    }

    private fun state(playing: Boolean, positionMs: Long, durationMs: Long, receivedAt: Long) =
        NowPlaying(
            title = "t", artist = null, album = null, app = null,
            playing = playing, positionMs = positionMs, durationMs = durationMs,
            canSeek = true, canNext = true, canPrev = true, art = null,
            receivedAt = receivedAt,
        )
}
