package com.portalremote.net

import android.os.SystemClock
import org.json.JSONObject

/**
 * What the PC was last told to cast, from its `cast_ok` acknowledgement.
 *
 * `via` is the part that matters to the UI: a receiver page holds a socket and takes
 * transport commands, while a shell launch is fire-and-forget — the PC handed the
 * link to whatever is registered for it and has no way back to it. Showing transport
 * buttons for the second case would offer control that doesn't exist.
 */
data class CastState(
    val url: String,
    val via: String,
    val title: String? = null,
    /** [CastTarget.id] of where it landed, and that target's name — so "Casting" can say
     *  *to what*, which matters the moment there is more than one screen in the list. */
    val target: String? = null,
    val targetName: String? = null,
) {
    /** Everything except a shell launch: the PC handed that link to whatever was
     *  registered for it and has no way back to it. Written as "not shell" rather than
     *  a list of the good ones so a protocol added on the PC works here without an
     *  app update. */
    val controllable: Boolean get() = via.isNotBlank() && via != SHELL

    /** The title if the caster sent one, otherwise the link itself. */
    val label: String get() = title ?: url

    companion object {
        const val RECEIVER = "receiver"

        /** The PC's own mpv window. Reports position and takes the same transport
         *  commands as a receiver page, so nothing downstream tells them apart. */
        const val MPV = "mpv"

        /** `ShellExecute` on the PC — fire and forget, nothing to drive afterwards. */
        const val SHELL = "shell"

        /** `{"t":"cast_ok","url":…,"via":"receiver"|"mpv"|"shell"|"roku"|"dlna",
         *  "target":id,"name":…}`, or null if it isn't one. */
        fun fromAck(json: JSONObject, title: String? = null): CastState? {
            val url = json.optStringOrNull("url") ?: return null
            return CastState(
                url = url,
                via = json.optString("via"),
                title = title,
                target = json.optStringOrNull("target"),
                targetName = json.optStringOrNull("name"),
            )
        }
    }
}

/**
 * What the receiver page says it is doing, from the PC's `cast_status` push.
 *
 * The page reports on every transport event plus a 1 Hz tick while playing, and the
 * server forwards each one — so unlike [CastState], which only knows *where* the cast
 * landed, this is what makes a scrub bar and a real play/pause toggle possible.
 *
 * Positions arrive in seconds (they come from an HTML `<video>`) and are kept in
 * milliseconds here, so they format and interpolate with the same helpers the
 * now-playing card already uses.
 */
data class CastStatus(
    val paused: Boolean,
    val ended: Boolean,
    /** The page has not been pressed yet, so no `play()` from here will be honoured —
     *  a browser will not start playback the user never asked for. Worth saying out
     *  loud, since "nothing happened" otherwise looks like a broken cast. */
    val waitingForGesture: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val muted: Boolean,
    val volume: Float,
    /** `HTMLMediaElement.error.code`, 0 when there is none. */
    val errorCode: Int,
    /** Monotonic clock reading when this arrived — the baseline [positionAt] counts from. */
    val receivedAt: Long,
) : Playhead {
    /** Advancing on its own, so the bar should tick. */
    override val playing: Boolean get() = !paused && !ended && !waitingForGesture

    /** Seekable at all — a live stream reports no duration and has no bar to drag. */
    val seekable: Boolean get() = durationMs > 0

    /**
     * Where the playhead is at [now] (a `SystemClock.elapsedRealtime()` reading),
     * carried forward against this phone's own clock between the receiver's ticks.
     */
    override fun positionAt(now: Long): Long {
        if (!playing) return positionMs.coerceAtLeast(0)
        val moved = positionMs + (now - receivedAt)
        return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
    }

    companion object {
        /**
         * `{"t":"cast_status","receiver":bool,"status":{…}|null}`.
         *
         * Null when there is no receiver attached or it has not reported yet: both mean
         * there is no position to draw, and a bar pinned at zero would claim otherwise.
         */
        fun fromPush(json: JSONObject, receivedAt: Long = SystemClock.elapsedRealtime()): CastStatus? {
            if (!json.optBoolean("receiver")) return null
            val status = json.optJSONObject("status") ?: return null
            return CastStatus(
                paused = status.optBoolean("paused", true),
                ended = status.optBoolean("ended"),
                waitingForGesture = status.optBoolean("waitingForGesture"),
                positionMs = (status.optDouble("position", 0.0) * 1000).toLong(),
                durationMs = (status.optDouble("duration", 0.0) * 1000).toLong(),
                muted = status.optBoolean("muted"),
                volume = status.optDouble("volume", 1.0).toFloat(),
                errorCode = status.optInt("error"),
                receivedAt = receivedAt,
            )
        }
    }
}
