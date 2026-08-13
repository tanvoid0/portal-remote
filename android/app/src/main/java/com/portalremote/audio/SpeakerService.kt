package com.portalremote.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.portalremote.MainActivity
import com.portalremote.R
import com.portalremote.data.SavedHost
import com.portalremote.net.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocketListener
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the speaker is doing, for the card on the Media tab. */
sealed interface SpeakerState {
    data object Off : SpeakerState

    data object Connecting : SpeakerState

    data class Playing(val sampleRate: Int, val channels: Int) : SpeakerState

    /** Lost the stream and trying again — [reason] is what went wrong last time. */
    data class Reconnecting(val reason: String) : SpeakerState

    /** Given up. Only for things retrying cannot fix, like a rejected token. */
    data class Failed(val reason: String) : SpeakerState
}

/**
 * Plays the PC's own output on this phone, turning it into a wireless speaker.
 *
 * A foreground service rather than a coroutine on the Media screen, because a speaker
 * that stops when the screen locks is not a speaker. It is also the only honest shape:
 * audio keeps playing with the app in the background, so the system is owed a
 * notification saying so, and the user is owed a Stop button that isn't inside an app
 * they have navigated away from.
 *
 * The transport is one endless HTTP response of raw 16-bit PCM from `/audio/stream`,
 * written straight into an [AudioTrack]. `write()` blocks while the track is full,
 * which is what paces the whole chain: the phone's own clock pulls bytes through TCP
 * at exactly the rate it plays them.
 */
class SpeakerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        // The server pads real silence with real zeros, so this stream is constant-rate
        // by construction: five seconds of nothing on the wire means the link is gone,
        // not that the PC is quiet. That makes a read timeout a feature here — it is
        // what turns a dead Wi-Fi connection into a reconnect instead of a hang.
        .readTimeout(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null

    /** Held so [onDestroy] can break a blocking read — cancelling the coroutine alone
     *  cannot, since the read is inside a blocking socket call. */
    @Volatile
    private var call: Call? = null

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.takeIf { it.action != ACTION_STOP }?.host()
        if (host == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(host),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        acquireWifiLock()

        // Tapping the switch twice, or a second start arriving from the notification,
        // must not open a second stream against the same PC. Tested on isActive rather
        // than on null: a run that gave up on a refused token has already finished, and
        // switching back on has to be able to start a new one.
        if (job?.isActive != true) {
            // Loopback copies the PC's own mix, so unmuted PC speakers means the same
            // audio twice, out of step. Muting them is the point of "play it here"; the
            // matching unmute on the way out is what keeps this reversible rather than a
            // one-way trip to silent speakers the user has to go find and fix by hand.
            toggleMute(host)
            job = scope.launch { run(host) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        call?.cancel()
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        // Same key as the one that muted on the way in. If the user unmuted by hand
        // while this was playing — to hear it through both, say — this mutes it right
        // back rather than leaving it be; there is no unmuted-on-purpose to tell apart
        // from unmuted-because-a-track-changed without asking the PC its actual state,
        // which the mute key was chosen precisely to avoid needing.
        muteHost?.let { toggleMute(it) }
        muteHost = null
        // A service that stopped itself because the pairing was refused has already put
        // the reason in the state, and that is the one thing the user needs to see —
        // resetting to Off here would replace it with "the switch is off" and no why.
        if (state.value !is SpeakerState.Failed) state.value = SpeakerState.Off
        super.onDestroy()
    }

    private var muteHost: SavedHost? = null

    /**
     * Toggle the PC's system mute over the `/control` socket — the same `mute` command
     * the remote's own volume button sends, which is `VK_VOLUME_MUTE`: a hardware toggle
     * key, not a set-to. There is no separate "is the PC muted" the server should even
     * be asked, since answering it race with whatever the user just did by hand; the
     * toggle is symmetric by construction as long as it is sent exactly once per edge of
     * the switch, which [onStartCommand]/[onDestroy] each do.
     *
     * A one-shot socket rather than the app's shared `/control` connection: this service
     * outlives whatever screen is or isn't open, and a command that only needs to be
     * delivered once is simpler than keeping a connection alive to carry it.
     */
    private fun toggleMute(host: SavedHost) {
        muteHost = host
        val request = Request.Builder()
            .url(host.wsUrl)
            .header("Authorization", "Bearer ${host.token}")
            .build()
        // OkHttp queues an enqueued send until the handshake completes and flushes it
        // before honouring close() — both fire-and-forget, matching WsClient.send's own
        // best-effort contract. A PC that is unreachable right now drops this silently,
        // same as it would drop any other remote button press.
        runCatching {
            client.newWebSocket(request, object : WebSocketListener() {})
                .apply { send(Protocol.media("mute").toString()) }
                .close(1000, null)
        }.onFailure { Log.w(TAG, "Could not toggle PC mute", it) }
    }

    /**
     * Connect, play, and reconnect for as long as the service is up.
     *
     * A stream that ends cleanly is retried like one that failed: the server closes the
     * response when the PC's output device changes, and reconnecting is how the phone
     * follows it to the new one — which is exactly what the user asked for by switching
     * outputs on the PC.
     */
    private suspend fun run(host: SavedHost) {
        while (scope.isActive) {
            if (state.value !is SpeakerState.Reconnecting) state.value = SpeakerState.Connecting

            val reason = try {
                stream(host)
                "The PC ended the stream"
            } catch (rejected: Unrecoverable) {
                state.value = SpeakerState.Failed(rejected.message ?: "Cannot play from this PC")
                stopSelf()
                return
            } catch (dropped: IOException) {
                dropped.message ?: "Lost the PC"
            } catch (cancelled: CancellationException) {
                // A CancellationException *is* a RuntimeException, and swallowing one
                // below would leave this loop reconnecting to a service being torn down.
                throw cancelled
            } catch (refused: RuntimeException) {
                // This phone's own audio stack saying no: a buffer size it will not
                // allocate, a playback speed it will not take. Retrying is right — but
                // the point of catching it here is that an exception escaping this
                // coroutine kills the stream while leaving the service, the notification
                // and the switch all looking healthy, which is silence with nothing to
                // read anywhere. Whatever else happens, it must not be silent.
                Log.w(TAG, "Playback failed", refused)
                refused.message ?: "This phone would not play the stream"
            }

            // Switching off cancels the call, which surfaces here as a dropped socket.
            // Without this the loop would publish "reconnecting" *after* onDestroy has
            // published "off", and the card would show a switch that is on for a
            // service that no longer exists.
            if (!scope.isActive) return
            state.value = SpeakerState.Reconnecting(reason)
            delay(RETRY_MS)
        }
    }

    private fun stream(host: SavedHost) {
        val request = Request.Builder()
            .url("${host.httpBase}/audio/stream")
            .header("Authorization", "Bearer ${host.token}")
            .build()

        val pending = client.newCall(request)
        call = pending
        pending.execute().use { response ->
            // A refused token is the one failure retrying cannot fix; everything else
            // (no output device yet, server restarting, Wi-Fi blip) is worth another go.
            if (response.code == 401 || response.code == 403) {
                throw Unrecoverable("This PC no longer accepts this phone's pairing.")
            }
            if (!response.isSuccessful) {
                // The 503 body is written for a human to read — "find an audio output
                // device", say — so it beats anything this end could invent.
                throw IOException(
                    response.body?.string()?.trim()?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.code}",
                )
            }

            play(
                rate = response.header(HEADER_RATE)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE,
                channels = response.header(HEADER_CHANNELS)?.toIntOrNull() ?: DEFAULT_CHANNELS,
                source = response.body?.source() ?: throw IOException("The PC sent no audio."),
            )
        }
    }

    /** Pull PCM until the stream ends. Returns on end-of-stream; throws on a dropped
     *  socket, which the caller treats the same way. */
    private fun play(rate: Int, channels: Int, source: BufferedSource) {
        val frameBytes = channels * 2
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val floor = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // What the track is aimed at holding: the jitter margin and the latency in one
        // number, since everything in it is audio the phone has but has not played yet.
        // 150ms rides out a Wi-Fi hiccup and is far below the point where a paused track
        // sounds late — but it is why this will never be in sync with video on the PC's
        // own screen.
        val targetFrames = rate * BUFFER_MS / 1000
        // Room *above* the target rather than a track kept full. Keeping it full was the
        // old design and it hid the one number this needs: a saturated buffer reads the
        // same whether the two clocks agree or not, and the mismatch instead piles up in
        // the capture buffer on the PC until that overflows. With slack, how much the
        // track is holding is a direct measure of how far the clocks have drifted apart.
        val bufferBytes = maxOf(floor, targetFrames * frameBytes * BUFFER_SLACK)

        val output = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // MEDIA, not ASSISTANCE_SONIFICATION: this is music, and it should
                    // duck for a call and follow the media volume slider like music does.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val equalizer = EqualizerFeed(rate, channels)
        try {
            val buffer = ByteArray(CHUNK_BYTES - CHUNK_BYTES % frameBytes)
            val chunkFrames = buffer.size / frameBytes
            var written = 0L

            // Fill to the target before starting. A track told to play while empty
            // underruns immediately, which is an audible click on the first note — and
            // it happens on every reconnect, not just the first one.
            while (written + chunkFrames <= targetFrames) {
                if (!source.readFrames(buffer)) return
                output.write(buffer, 0, buffer.size)
                equalizer.feed(buffer, buffer.size)
                written += chunkFrames
            }

            output.play()
            state.value = SpeakerState.Playing(rate, channels)
            Log.i(
                TAG,
                "Playing ${rate}Hz ${channels}ch, target ${targetFrames}f, " +
                    "track ${output.bufferSizeInFrames}f",
            )

            var nextCheck = SystemClock.elapsedRealtime() + DRIFT_INTERVAL_MS
            // The loop is paced by the socket now, not by the track. The server sends at
            // exactly the PC's byte rate — real packets or padded silence, never nothing
            // — so a blocking read is the PC's clock arriving on this thread, and a
            // non-blocking write means a slow moment here can never reach back and stall
            // the capture on the PC.
            while (source.readFrames(buffer)) {
                // Every chunk is written. Nothing here decides *whether* audio plays —
                // the measurement below only trims the speed by a fraction of a percent,
                // and a measurement that can silence the stream is worse than no
                // measurement at all. Standing latency needs no rule of ours either: a
                // full track takes what it can and the rest of the chunk goes, which
                // bounds the delay at BUFFER_SLACK times the target by construction.
                val wrote = output.write(buffer, 0, buffer.size, AudioTrack.WRITE_NON_BLOCKING)
                if (wrote > 0) written += wrote / frameBytes
                equalizer.feed(buffer, buffer.size)

                if (SystemClock.elapsedRealtime() >= nextCheck) {
                    nextCheck = SystemClock.elapsedRealtime() + DRIFT_INTERVAL_MS
                    val played = output.playedFrames()
                    // Both counters live in the same unsigned 32-bit space, so masking
                    // the subtraction is right across the wrap the head position does
                    // about once a day. Past the halfway mark is not a wrap but a track
                    // reporting more played than it was given, which reads as empty.
                    val fill = ((written - played) and 0xFFFFFFFFL)
                        .let { if (it > Int.MAX_VALUE) 0L else it }
                    // A device that refuses a playback speed outright is left to drift,
                    // which is what this did before there was a correction at all — not
                    // something to end the stream over.
                    val speed = driftSpeed(fill, targetFrames, rate)
                    // Once a second, and the only window onto a mechanism whose failure
                    // is silence: fill sitting near the target is the feature working.
                    Log.d(TAG, "fill ${fill}f of $targetFrames, played $played, speed $speed")
                    runCatching {
                        output.playbackParams = PlaybackParams().setSpeed(speed)
                    }
                }
            }
        } finally {
            clearEqualizer()
            runCatching { output.stop() }
            output.release()
        }
    }

    /**
     * Wi-Fi power save parks the radio between packets once the screen is off, which on
     * a constant-rate audio stream is heard directly as dropouts. The lock costs battery
     * and is held only while something is actually playing.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "portal-remote:speaker").apply { acquire() }
    }

    private fun notification(host: SavedHost): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Speaker",
                // LOW: this notification is a control surface and a legal requirement,
                // not news. A sound or a heads-up banner for "audio is playing" would
                // interrupt the audio it is announcing.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SpeakerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_portal_mark)
            .setContentTitle("Playing ${host.label}")
            .setContentText("This phone is the PC's speaker")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun Intent.host(): SavedHost? {
        val address = getStringExtra(EXTRA_HOST) ?: return null
        val token = getStringExtra(EXTRA_TOKEN) ?: return null
        return SavedHost(
            host = address,
            port = getIntExtra(EXTRA_PORT, 0).takeIf { it > 0 } ?: return null,
            token = token,
            name = getStringExtra(EXTRA_NAME),
        )
    }

    private class Unrecoverable(message: String) : IOException(message)

    companion object {
        private const val ACTION_STOP = "com.portalremote.audio.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_NAME = "name"

        private const val TAG = "PortalSpeaker"

        private const val CHANNEL_ID = "speaker"
        private const val NOTIFICATION_ID = 42

        private const val HEADER_RATE = "X-Portal-Sample-Rate"
        private const val HEADER_CHANNELS = "X-Portal-Channels"

        /** Only reached if the server ever answers without its headers. */
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_CHANNELS = 2

        private const val BUFFER_MS = 150

        /** How much bigger the track is than [BUFFER_MS], so its fill can move both ways
         *  and mean something rather than pinning at full. It doubles as the ceiling on
         *  standing latency, since a track cannot hold more than it holds — which is why
         *  2 and not 10: the worst case here is audible lateness, not a lost measurement. */
        private const val BUFFER_SLACK = 2

        /** Drift is measured in parts per million and answered in whole seconds. Checking
         *  faster would mostly measure jitter and steer by it. */
        private const val DRIFT_INTERVAL_MS = 1_000L
        /** ~21ms at 48kHz stereo. Whole chunks are what [readFrames] waits for, so this
         *  is a hold as well as a read size — small enough not to matter next to
         *  [BUFFER_MS], large enough not to be a syscall per millisecond. */
        private const val CHUNK_BYTES = 4 * 1024
        private const val RETRY_MS = 2_000L
        private const val STALL_TIMEOUT_SECONDS = 5L

        /** Process-wide rather than per-binding: one phone plays one PC, the service
         *  outlives the screen that started it, and a binder for one enum is ceremony. */
        private val state = MutableStateFlow<SpeakerState>(SpeakerState.Off)

        val speaker: StateFlow<SpeakerState> = state.asStateFlow()

        fun start(context: Context, host: SavedHost) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SpeakerService::class.java)
                    .putExtra(EXTRA_HOST, host.host)
                    .putExtra(EXTRA_PORT, host.port)
                    .putExtra(EXTRA_TOKEN, host.token)
                    .putExtra(EXTRA_NAME, host.name),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeakerService::class.java))
        }
    }
}

/**
 * Fill [buffer] completely, or return false once the stream has ended.
 *
 * The whole point is that partial reads never reach `AudioTrack`. `write` there drops
 * whatever is left over past the last complete frame in a call, so one write ending
 * mid-frame shifts every sample after it by a byte or two — and 16-bit samples read a
 * byte out of step are white noise with the music faintly audible underneath, for the
 * rest of the connection rather than for that packet. `BufferedSource.read` hands back
 * whatever the socket happened to deliver, which owes nobody a frame boundary; waiting
 * for a whole buffer is the fix, and okio is already buffering these bytes either way.
 *
 * [buffer] must therefore be a whole number of frames long. The tail of a stream that
 * ends mid-buffer is dropped, which is at most a few milliseconds of a stream that is
 * ending anyway.
 */
internal fun BufferedSource.readFrames(buffer: ByteArray): Boolean {
    if (!request(buffer.size.toLong())) return false
    readFully(buffer)
    return true
}

/** Frames the track has actually played. [AudioTrack.getPlaybackHeadPosition] is an
 *  unsigned 32-bit count returned in an `Int`, and it wraps after about a day. */
private fun AudioTrack.playedFrames(): Long = playbackHeadPosition.toLong() and 0xFFFFFFFFL

/** Correction applied per second of error, per second of playing. */
private const val DRIFT_GAIN = 0.05f

/** Ceiling on the correction. 0.2% is three and a half cents of pitch if the phone
 *  resamples rather than time-stretches — under the ~10 cents a good ear notices, and
 *  two orders of magnitude more than any crystal is out by, so it only clamps while
 *  recovering from a stall. */
private const val DRIFT_LIMIT = 0.002f

/**
 * How fast to play, given how much audio the track is sitting on.
 *
 * The PC's audio clock and this phone's DAC are separate crystals, each out by tens of
 * parts per million. Nothing shares a clock, so nothing keeps them together: at 100ppm
 * the buffer walks by a third of a second an hour, and the stream eventually either runs
 * dry or backs up into a glitch. Every wireless speaker corrects this — resampling by
 * some ratio a hair off 1.0, steered by exactly this measurement.
 *
 * Proportional, no integral term: a constant drift settles at an error of drift/gain,
 * which for 100ppm is two milliseconds. An integrator would buy nothing and can wind up.
 */
internal fun driftSpeed(fillFrames: Long, targetFrames: Int, rate: Int): Float {
    val errorSeconds = (fillFrames - targetFrames).toFloat() / rate
    return 1f + (errorSeconds * DRIFT_GAIN).coerceIn(-DRIFT_LIMIT, DRIFT_LIMIT)
}
