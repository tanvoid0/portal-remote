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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.portalremote.MainActivity
import com.portalremote.R
import com.portalremote.data.SavedHost
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
        if (job?.isActive != true) job = scope.launch { run(host) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        call?.cancel()
        scope.cancel()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        // A service that stopped itself because the pairing was refused has already put
        // the reason in the state, and that is the one thing the user needs to see —
        // resetting to Off here would replace it with "the switch is off" and no why.
        if (state.value !is SpeakerState.Failed) state.value = SpeakerState.Off
        super.onDestroy()
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
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val floor = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // The buffer is the jitter margin and the latency in one number: everything in it
        // is audio the phone has but has not played yet. 150ms rides out a Wi-Fi hiccup
        // and is far below the point where a paused track sounds late — but it is why
        // this will never be in sync with video on the PC's own screen.
        val bufferBytes = maxOf(floor, rate * channels * 2 * BUFFER_MS / 1000)

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

        try {
            val buffer = ByteArray(CHUNK_BYTES)

            // Fill the track before starting it. A track told to play while empty
            // underruns immediately, which is an audible click on the first note — and
            // it happens on every reconnect, not just the first one.
            var primed = 0
            while (primed + CHUNK_BYTES <= bufferBytes) {
                val read = source.read(buffer)
                if (read == -1) return
                output.write(buffer, 0, read)
                primed += read
            }

            output.play()
            state.value = SpeakerState.Playing(rate, channels)

            while (true) {
                val read = source.read(buffer)
                if (read == -1) return
                // Blocking write: this is the clock. It returns as the speaker consumes,
                // which throttles the socket, which throttles the PC.
                output.write(buffer, 0, read)
            }
        } finally {
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

        private const val CHANNEL_ID = "speaker"
        private const val NOTIFICATION_ID = 42

        private const val HEADER_RATE = "X-Portal-Sample-Rate"
        private const val HEADER_CHANNELS = "X-Portal-Channels"

        /** Only reached if the server ever answers without its headers. */
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_CHANNELS = 2

        private const val BUFFER_MS = 150
        private const val CHUNK_BYTES = 8 * 1024
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
