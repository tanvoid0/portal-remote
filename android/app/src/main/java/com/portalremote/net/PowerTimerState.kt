package com.portalremote.net

import org.json.JSONObject

/**
 * A power action scheduled for later, pushed as `power_timer` on connect and on every
 * change — set, edited, cancelled or fired. This is the PC's state, not this phone's:
 * it's what the server was holding before this socket opened, and what every other
 * paired phone sees too. [mode] is a [com.portalremote.ui.PowerMode.wire] value;
 * [endsAtMs] is `System.currentTimeMillis()`-comparable, since the server sends epoch
 * milliseconds.
 */
data class PowerTimerState(val mode: String, val endsAtMs: Long) {
    companion object {
        /** Null when nothing is pending — the shape the server sends between timers,
         *  not an error to guard against. */
        fun fromPush(json: JSONObject): PowerTimerState? {
            val mode = json.optStringOrNull("mode") ?: return null
            if (json.isNull("endsAt")) return null
            return PowerTimerState(mode, json.optLong("endsAt"))
        }
    }
}
