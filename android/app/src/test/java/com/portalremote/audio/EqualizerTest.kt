package com.portalremote.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equalizer's actual math: does a Goertzel pass find the frequency it was pointed
 * at, and does feeding real PCM bytes through [EqualizerFeed] turn a tone into a
 * spectrum with a peak in it rather than noise or silence. Both are the kind of bug
 * that is invisible in a screenshot — a swapped byte order or a wrong window size still
 * draws *some* bars, just not ones that track the audio.
 */
class EqualizerTest {

    @Test
    fun `goertzel finds a tone's own frequency, not a distant one`() {
        val sampleRate = 48_000
        val samples = FloatArray(EQ_WINDOW) { i ->
            (12_000.0 * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat()
        }

        val atTone = goertzel(samples, 1_000.0, sampleRate)
        val farBelow = goertzel(samples, 100.0, sampleRate)
        val farAbove = goertzel(samples, 10_000.0, sampleRate)

        assertTrue("tone should dominate a distant low band", atTone > farBelow * 10)
        assertTrue("tone should dominate a distant high band", atTone > farAbove * 10)
    }

    @Test
    fun `silence reads as a flat zero spectrum`() {
        clearEqualizer()
        val feed = EqualizerFeed(sampleRate = 48_000, channels = 2)
        feed.feed(ByteArray(EQ_WINDOW * 2 * 2), EQ_WINDOW * 2 * 2)

        assertTrue(equalizerSpectrum.value.all { it == 0f })
    }

    @Test
    fun `a loud tone produces a clear peak, however the bytes are chunked`() {
        clearEqualizer()
        val sampleRate = 48_000
        val channels = 2
        val pcm = sineWavePcm(frames = EQ_WINDOW * 2, freqHz = 1_000.0, sampleRate = sampleRate, channels = channels)

        val feed = EqualizerFeed(sampleRate, channels)
        // 7 is coprime with the 4-byte frame, so almost every chunk ends mid-frame —
        // the same stress SpeakerStreamTest puts on the speaker's own frame reader,
        // here against EqualizerFeed's carry-across-calls logic instead.
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + 7, pcm.size)
            feed.feed(pcm.copyOfRange(offset, end), end - offset)
            offset = end
        }

        val spectrum = equalizerSpectrum.value
        assertTrue("a 1kHz tone should light at least one band", spectrum.max() > 0.05f)
        assertTrue("a pure tone should peak, not read flat", spectrum.max() > spectrum.min() * 3)
    }

    /** Interleaved 16-bit little-endian PCM of a sine tone — the same byte layout
     *  `/audio/stream` sends. */
    private fun sineWavePcm(frames: Int, freqHz: Double, sampleRate: Int, channels: Int): ByteArray {
        val bytes = ByteArray(frames * channels * 2)
        for (f in 0 until frames) {
            val sample = (12_000.0 * sin(2.0 * PI * freqHz * f / sampleRate)).toInt()
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                bytes[i] = (sample and 0xFF).toByte()
                bytes[i + 1] = ((sample shr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }
}
