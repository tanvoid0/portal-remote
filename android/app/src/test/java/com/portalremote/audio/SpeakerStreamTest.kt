package com.portalremote.audio

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of the speaker that decide whether it sounds like music.
 *
 * A socket delivers whatever it delivers, and `AudioTrack` drops any bytes past the last
 * whole frame of a write — so a chunk that ends mid-frame does not lose a sample, it
 * shifts every sample after it, and 16-bit PCM read a byte out of step is static with
 * the music buried under it. And the two ends run on different crystals, which without
 * a correction walk apart until the stream glitches.
 */
class SpeakerStreamTest {

    /** Hands back an awkward number of bytes at a time, as a real socket does. */
    private class Trickle(private val data: Buffer, private val at: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long) = data.read(sink, minOf(byteCount, at))

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = data.close()
    }

    private fun pcm(bytes: Int) = ByteArray(bytes) { (it % 251).toByte() }

    @Test
    fun `chunks stay frame-aligned however the socket splits the stream`() {
        val stream = pcm(4096 * 3)
        // 7 is the point: coprime with the 4-byte frame, so every read but the first
        // ends mid-frame if the reads are passed through as they arrive.
        val source = Trickle(Buffer().write(stream), at = 7).buffer()

        val played = Buffer()
        val chunk = ByteArray(4096)
        var chunks = 0
        while (source.readFrames(chunk)) {
            played.write(chunk)
            chunks++
        }

        assertEquals(3, chunks)
        assertArrayEquals(stream, played.readByteArray())
    }

    /** 48kHz stereo, 150ms of target buffer. */
    private val rate = 48_000
    private val target = rate * 150 / 1000

    @Test
    fun `a track holding more than the target plays faster, and the wrong way round`() {
        // Too much in the track means the PC's clock is the faster one and this end has
        // to catch up. The sign being backwards here is a buffer that runs away instead
        // of settling, so it is worth an assertion of its own.
        assertTrue(driftSpeed(target + rate / 10L, target, rate) > 1f)
        assertTrue(driftSpeed(target - rate / 10L, target, rate) < 1f)
        assertEquals(1f, driftSpeed(target.toLong(), target, rate), 0f)
    }

    @Test
    fun `the correction stays inaudible, however far out the buffer is`() {
        // A stall, or a track drained to nothing: both are corrected slowly rather than
        // by a jump in pitch that is heard as a wobble.
        assertEquals(1.002f, driftSpeed(target + 10L * rate, target, rate), 1e-6f)
        assertEquals(0.998f, driftSpeed(0, target, rate), 1e-6f)
    }

    @Test
    fun `a hundred ppm of drift settles two milliseconds off target`() {
        // What the loop is actually for. A crystal 100ppm fast needs speed 1.0001 to be
        // held, which a proportional controller only produces at a standing error — and
        // that error is the latency this design pays for having no integral term.
        val settled = target + (0.002f * rate).toLong()
        assertEquals(1.0001f, driftSpeed(settled, target, rate), 1e-6f)
    }

    @Test
    fun `a stream ending mid-chunk ends the loop rather than half-filling it`() {
        val source = Buffer().write(pcm(100))
        val chunk = ByteArray(64)

        assertTrue(source.readFrames(chunk))
        // 36 bytes left, and a partial chunk is the one thing that must never be played.
        assertFalse(source.readFrames(chunk))
    }
}
