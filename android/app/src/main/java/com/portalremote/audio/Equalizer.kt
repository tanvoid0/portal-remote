package com.portalremote.audio

import com.portalremote.data.SavedHost
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Live spectrum of whatever the PC is actually sending, computed directly from the raw
 * 16-bit PCM `/audio/stream` already carries — not `android.media.audiofx.Visualizer`,
 * which needs an actual local [android.media.AudioTrack] session to attach to. That tied
 * the equalizer to whether this phone was also playing the audio out loud, which is
 * exactly the thing it must not depend on (the bars have to track the PC's own sound,
 * not the Speaker switch), and it turned out to be unreliable besides — some devices
 * never deliver its FFT callback at all. Reading the bytes and doing the small amount of
 * math ourselves has neither problem.
 *
 * One shared destination: whichever of [SpeakerService]'s own playback loop or a
 * read-only [tapEqualizer] happens to be open feeds the same [equalizerSpectrum], so the
 * Media screen has one number to draw regardless of where it came from.
 */

/** Bars the Media screen's equalizer draws — plenty to read as a spectrum, few enough
 *  that each stays a distinct bar at phone width. */
const val EQ_BANDS = 16

/** The range a graphic equalizer usually covers. Log-spaced between them so each bar is
 *  roughly the same musical interval as its neighbours — ears hear octaves, not Hz. */
private const val EQ_LOW_HZ = 60.0
private const val EQ_HIGH_HZ = 12_000.0

private val EQ_CENTERS = DoubleArray(EQ_BANDS) { i ->
    EQ_LOW_HZ * (EQ_HIGH_HZ / EQ_LOW_HZ).pow(i.toDouble() / (EQ_BANDS - 1))
}

/** Samples averaged into one reading. At a typical 48kHz stream that's ~21ms of audio —
 *  fast enough to read as live, slow enough that it isn't recomputing 16 Goertzel passes
 *  on every single sample. Internal rather than private so `EqualizerTest` can feed
 *  exactly one window without duplicating the number. */
internal const val EQ_WINDOW = 1024

/**
 * Rough ceiling on a band's raw Goertzel magnitude, picked from typical program material
 * rather than measured per device.
 * ponytail: fixed loudness ceiling — if bars read consistently too hot or too quiet
 * against real playback, tune this rather than the Goertzel math.
 */
private const val EQ_MAGNITUDE_CEILING = EQ_WINDOW * 4_000.0

private val _spectrum = MutableStateFlow(FloatArray(EQ_BANDS))
val equalizerSpectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

fun clearEqualizer() {
    _spectrum.value = FloatArray(EQ_BANDS)
}

/**
 * Turns interleaved 16-bit little-endian PCM into [EQ_BANDS] live bands via one
 * [goertzel] pass per band — cheaper than a full FFT for a fixed, small set of target
 * frequencies, and there is no platform effect that would do this without an
 * [android.media.AudioTrack] already playing.
 *
 * Stateful only to carry a partial frame and a partial window across calls, since chunks
 * off the wire have no reason to land on either boundary.
 */
class EqualizerFeed(private val sampleRate: Int, channels: Int) {
    private val frameBytes = channels * 2
    private val channelCount = channels
    private val window = FloatArray(EQ_WINDOW)
    private var filled = 0
    private val carry = ByteArray(frameBytes)
    private var carryLen = 0

    /** Feeds one chunk. Publishes to [equalizerSpectrum] every time a full window of
     *  samples accumulates — usually more than once per call. */
    fun feed(pcm: ByteArray, byteCount: Int) {
        var offset = 0
        if (carryLen > 0) {
            val need = frameBytes - carryLen
            val take = minOf(need, byteCount)
            System.arraycopy(pcm, 0, carry, carryLen, take)
            carryLen += take
            offset = take
            if (carryLen < frameBytes) return
            consumeFrame(carry, 0)
            carryLen = 0
        }
        while (offset + frameBytes <= byteCount) {
            consumeFrame(pcm, offset)
            offset += frameBytes
        }
        val remaining = byteCount - offset
        if (remaining > 0) {
            System.arraycopy(pcm, offset, carry, 0, remaining)
            carryLen = remaining
        }
    }

    private fun consumeFrame(pcm: ByteArray, at: Int) {
        var sum = 0
        for (c in 0 until channelCount) {
            val i = at + c * 2
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            sum += (hi shl 8) or lo
        }
        window[filled++] = (sum / channelCount).toFloat()
        if (filled == EQ_WINDOW) {
            _spectrum.value = computeBands(window, sampleRate)
            filled = 0
        }
    }
}

private fun computeBands(samples: FloatArray, sampleRate: Int): FloatArray =
    FloatArray(EQ_BANDS) { b ->
        val freq = EQ_CENTERS[b]
        if (freq * 2 >= sampleRate) {
            0f
        } else {
            (goertzel(samples, freq, sampleRate) / EQ_MAGNITUDE_CEILING).toFloat().coerceIn(0f, 1f)
        }
    }

/** The Goertzel algorithm: the magnitude of one DFT bin, in O(n) and no bit-reversal —
 *  cheap when only a handful of fixed frequencies are wanted rather than the whole
 *  spectrum a full FFT would compute. */
internal fun goertzel(samples: FloatArray, freq: Double, sampleRate: Int): Double {
    val n = samples.size
    val k = (0.5 + n * freq / sampleRate).toInt()
    val w = 2.0 * PI * k / n
    val cosine = cos(w)
    val coeff = 2.0 * cosine
    var q1 = 0.0
    var q2 = 0.0
    for (s in samples) {
        val q0 = coeff * q1 - q2 + s
        q2 = q1
        q1 = q0
    }
    val real = q1 - q2 * cosine
    val imag = q2 * sin(w)
    return sqrt(real * real + imag * imag)
}

private val tapClient = OkHttpClient.Builder()
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

private const val TAP_RETRY_MS = 3_000L

/** How much of the stream is read per pass — no framing requirement here the way
 *  [SpeakerService]'s own read loop has, since [EqualizerFeed.feed] carries a partial
 *  frame across calls on its own. */
private const val TAP_CHUNK_BYTES = 4 * 1024

/**
 * A read-only tap on the PC's audio purely to feed [equalizerSpectrum], for whenever
 * [SpeakerService] isn't already streaming the same bytes for real playback. Scoped to
 * whatever coroutine calls it (the Media screen's own composition) rather than a
 * service: it only needs to run while something is actually watching the bars, and
 * stopping when the screen does is the whole point, not a background gap to patch —
 * unlike the Speaker feature, this has no reason to survive the screen going away.
 */
suspend fun tapEqualizer(host: SavedHost): Unit = withContext(Dispatchers.IO) {
    while (isActive) {
        val ok = runCatching {
            val request = Request.Builder()
                .url("${host.httpBase}/audio/stream")
                .header("Authorization", "Bearer ${host.token}")
                .build()
            val call = tapClient.newCall(request)
            // A blocking read doesn't notice coroutine cancellation on its own —
            // SpeakerService's own stream needs the same fix, for the same reason —
            // so without this, leaving the screen (or the Speaker feature taking
            // over) leaves this thread and its socket to the PC open until the read
            // timeout below finally trips it.
            val onCancel = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val channels = response.header("X-Portal-Channels")?.toIntOrNull() ?: 2
                    val rate = response.header("X-Portal-Sample-Rate")?.toIntOrNull() ?: 48_000
                    val source = response.body?.source() ?: return@use
                    val feed = EqualizerFeed(rate, channels)
                    val chunk = ByteArray(TAP_CHUNK_BYTES)
                    while (isActive) {
                        val read = source.read(chunk)
                        if (read <= 0) break
                        feed.feed(chunk, read)
                    }
                }
            } finally {
                onCancel?.dispose()
            }
        }.isSuccess
        clearEqualizer()
        if (!ok && isActive) delay(TAP_RETRY_MS)
    }
}
