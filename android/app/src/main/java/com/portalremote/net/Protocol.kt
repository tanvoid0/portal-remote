package com.portalremote.net

import org.json.JSONArray
import org.json.JSONObject

/** Builders for the `/control` WebSocket JSON messages the server understands. */
object Protocol {
    fun mouseMove(dx: Int, dy: Int) = JSONObject().apply {
        put("t", "mouse_move"); put("dx", dx); put("dy", dy)
    }

    fun mouseMoveAbs(x: Int, y: Int) = JSONObject().apply {
        put("t", "mouse_move_abs"); put("x", x); put("y", y)
    }

    /** Point at a fraction (0..1) of [monitor]'s area — what the screen mirror sends,
     *  since the phone knows where the finger landed in the mirrored image but not
     *  what that is in desktop pixels. Null [monitor] means the primary display. */
    fun mouseMoveNorm(nx: Float, ny: Float, monitor: Int? = null) = JSONObject().apply {
        put("t", "mouse_move_abs")
        put("nx", nx.coerceIn(0f, 1f).toDouble())
        put("ny", ny.coerceIn(0f, 1f).toDouble())
        if (monitor != null) put("mon", monitor)
    }

    /** Full press-and-release when [down] is null, otherwise just that half of the click. */
    fun mouseClick(button: String, down: Boolean? = null) = JSONObject().apply {
        put("t", "mouse_click"); put("btn", button)
        if (down != null) put("down", down)
    }

    fun scroll(dy: Int = 0, dx: Int = 0) = JSONObject().apply {
        put("t", "scroll"); put("dy", dy); put("dx", dx)
    }

    fun key(name: String, down: Boolean) = JSONObject().apply {
        put("t", "key"); put("key", name); put("down", down)
    }

    fun tap(name: String) = JSONObject().apply {
        put("t", "tap"); put("key", name)
    }

    fun combo(vararg keys: String) = JSONObject().apply {
        put("t", "combo"); put("keys", JSONArray(keys.toList()))
    }

    fun text(s: String) = JSONObject().apply {
        put("t", "text"); put("s", s)
    }

    fun media(action: String) = JSONObject().apply {
        put("t", "media"); put("action", action)
    }

    /** Jump to [ms] from the start of the current track. The one transport command
     *  with no media key behind it, so it goes to the media session directly. */
    fun seek(ms: Long) = JSONObject().apply {
        put("t", "seek"); put("ms", ms)
    }

    /** The remote's power button: `lock`, `sleep`, `restart` or `shutdown`. */
    fun power(mode: String) = JSONObject().apply {
        put("t", "power"); put("mode", mode)
    }

    /** Ask the PC to open a media URL — phase 4a of `docs/phase4-casting.md`. The
     *  title is what the receiver shows: a page title beats a CDN filename.
     *
     *  [target] is a [CastTarget.id]; left out, the PC picks its own best local route,
     *  which is what every caller did before there was anything else to pick. A TV is
     *  never chosen for you — putting a video on a screen across the room is a decision,
     *  not a fallback. */
    fun cast(url: String, title: String? = null, target: String? = null) = JSONObject().apply {
        put("t", "cast"); put("url", url)
        if (!title.isNullOrBlank()) put("title", title)
        if (!target.isNullOrBlank()) put("target", target)
    }

    /** Ask for the cast targets — step 4k of `docs/phase4-casting.md`. Answered from the
     *  PC's cache straight away; with [scan] it also starts an SSDP sweep for Rokus and
     *  DLNA renderers, which takes a few seconds and pushes a second `cast_targets` when
     *  it finds them. */
    fun castTargets(scan: Boolean = false) = JSONObject().apply {
        put("t", "cast_targets"); put("scan", scan)
    }

    /**
     * Transport for whatever the cast receiver page is playing: `play`, `pause`,
     * `toggle`, `stop`, `seek` or `volume`.
     *
     * Deliberately not [media]: that taps the global media keys, which land on
     * whichever window Windows currently thinks is playing — pausing Spotify while
     * the film carries on is the exact failure this avoids.
     */
    fun player(action: String) = JSONObject().apply {
        put("t", "player"); put("action", action)
    }

    /** Skip [seconds] from wherever the receiver is now; negative rewinds. Still the
     *  right message for the skip buttons, and the only one available before the
     *  receiver has reported a position. */
    fun playerSeekBy(seconds: Double) = player("seek").put("by", seconds)

    /** Jump to [seconds] from the start — what dragging the scrub bar sends, now that
     *  `cast_status` carries a position to drag against. */
    fun playerSeekTo(seconds: Double) = player("seek").put("to", seconds)

    /** 0..1, the `<video>` element's own scale. */
    fun playerVolume(level: Float) = player("volume").put("level", level.toDouble())

    fun playerMuted(muted: Boolean) = player("volume").put("muted", muted)
}
