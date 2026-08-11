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
}
