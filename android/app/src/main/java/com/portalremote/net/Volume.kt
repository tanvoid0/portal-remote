package com.portalremote.net

import org.json.JSONObject

/**
 * The PC's master output level (0..1) and mute state, pushed as `volume` on connect and
 * on every change — from the Media screen's own slider, the media keys' vol_up/vol_down/
 * mute, or another paired phone doing either. Null fields when this PC has no audio
 * output device to ask, the same shape [ServerHello.mac] uses for "not available" rather
 * than a fake zero.
 */
data class Volume(val level: Float?, val muted: Boolean?) {
    companion object {
        fun fromPush(json: JSONObject) = Volume(
            level = if (json.isNull("level")) null else json.optDouble("level").toFloat(),
            muted = if (json.isNull("muted")) null else json.optBoolean("muted"),
        )
    }
}
