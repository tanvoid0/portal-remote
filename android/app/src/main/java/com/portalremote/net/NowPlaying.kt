package com.portalremote.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Something with a position that moves on its own between pushes.
 *
 * Two unrelated things on the Media tab have this shape — the PC's own media session
 * and a cast receiver page — and the composable that ticks a bar only ever needed
 * these two members. Implemented by [NowPlaying] and [CastStatus].
 */
interface Playhead {
    /** Advancing right now, so a bar drawn from it needs to recompose. */
    val playing: Boolean

    /** Position in milliseconds at [now], a `SystemClock.elapsedRealtime()` reading. */
    fun positionAt(now: Long): Long
}

/**
 * What the paired PC is playing, as of the last `now_playing` push.
 *
 * [positionMs] is where the playhead was at the instant the PC serialized the
 * message, not at some timestamp on the PC's clock — so progress can be carried
 * forward here against this phone's own monotonic clock, with no assumption that the
 * two devices agree on the time. Between pushes (half a second at most, and only
 * while something is actually moving) the bar is interpolated rather than stepped.
 */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** The player it's coming from — "Spotify", "vlc" — or null when Windows only
     *  gave us an opaque id, which is what browsers register. */
    val app: String?,
    override val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val canSeek: Boolean,
    val canNext: Boolean,
    val canPrev: Boolean,
    /** Changes whenever the cover art does; null when the track has none. The art
     *  itself comes from [MediaApi.art]. */
    val art: Int?,
    /** Monotonic clock reading when this arrived — the baseline [positionAt] counts from. */
    val receivedAt: Long,
) : Playhead {
    /** Second line under the title. Either may be missing; a lone bullet is not a subtitle. */
    val subtitle: String? get() = listOfNotNull(artist, album).takeIf { it.isNotEmpty() }?.joinToString(" • ")

    /**
     * Where the playhead is at [now] (a `SystemClock.elapsedRealtime()` reading).
     * Paused means it hasn't moved. Live streams report no duration, so those are
     * left to run rather than clamped to zero.
     */
    override fun positionAt(now: Long): Long {
        if (!playing) return positionMs.coerceAtLeast(0)
        val moved = positionMs + (now - receivedAt)
        return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
    }

    companion object {
        /** Null when nothing is playing — the card has nothing to show, not an empty one. */
        fun fromPush(json: JSONObject, receivedAt: Long = SystemClock.elapsedRealtime()): NowPlaying? {
            if (!json.optBoolean("active")) return null
            return NowPlaying(
                title = json.optString("title").ifBlank { null },
                artist = json.optString("artist").ifBlank { null },
                album = json.optString("album").ifBlank { null },
                app = json.optString("app").ifBlank { null },
                playing = json.optBoolean("playing"),
                positionMs = json.optLong("positionMs"),
                durationMs = json.optLong("durationMs"),
                canSeek = json.optBoolean("canSeek"),
                canNext = json.optBoolean("canNext", true),
                canPrev = json.optBoolean("canPrev", true),
                art = if (json.isNull("art")) null else json.optInt("art"),
                receivedAt = receivedAt,
            )
        }
    }
}

/** Client for `/media/art` — the cover of whatever is playing on the PC. */
class MediaApi(private val client: OkHttpClient = OkHttpClient()) {
    /**
     * The current cover, or null if the track has none (or the fetch failed — a
     * missing cover is a blank square, never an error the user has to dismiss).
     *
     * [revision] only varies the URL so a stale image can't be served from a cache;
     * the server always answers with the cover of whatever is playing right now.
     */
    suspend fun art(host: SavedHost, revision: Int): Bitmap? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${host.httpBase}/media/art?rev=$revision")
            .header("Authorization", "Bearer ${host.token}")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}
