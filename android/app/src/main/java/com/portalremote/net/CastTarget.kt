package com.portalremote.net

import org.json.JSONObject

/**
 * One screen a cast can go to — step 4c of `docs/phase4-casting.md`.
 *
 * The PC does the protocol work and the discovering; this is just the row in the picker.
 * That split is deliberate: SSDP is multicast, and a phone needs a `MulticastLock` and
 * (on 13+) `NEARBY_WIFI_DEVICES` to hear a reply at all, while the PC is already awake,
 * already on the wire, and already speaking the renderer half of the same protocol.
 *
 * [seek] and [volume] are what the receiver can *actually* do, so the UI can offer a
 * read-only progress bar instead of a scrubber that swallows the drag — a Roku has no
 * absolute seek in its entire control protocol.
 */
data class CastTarget(
    val id: String,
    val name: String,
    /** `receiver`, `mpv`, `shell`, `roku` or `dlna`. */
    val kind: String,
    val seek: Boolean,
    val volume: Boolean,
    /** Reports its position back, so there is a playhead to draw. */
    val status: Boolean,
) {
    /** Worth showing transport for at all. */
    val controllable: Boolean get() = kind != CastState.SHELL

    companion object {
        /** `{"t":"cast_targets","active":id|null,"targets":[…]}` — the whole list, since
         *  the PC re-sends it in full whenever anything changes. */
        fun listFromPush(json: JSONObject): List<CastTarget> {
            val array = json.optJSONArray("targets") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { return@mapNotNull null }
                CastTarget(
                    id = id,
                    name = item.optString("name").ifBlank { id },
                    kind = item.optString("kind"),
                    seek = item.optBoolean("seek"),
                    volume = item.optBoolean("volume"),
                    status = item.optBoolean("status"),
                )
            }
        }

        /** Which one is holding the current cast, if any. */
        fun activeFromPush(json: JSONObject): String? =
            json.optString("active").ifBlank { null }

        /** Whether the PC is mid-sweep. Taken from the PC rather than timed on this
         *  side: a short list and an unfinished one look the same here. */
        fun scanningFromPush(json: JSONObject): Boolean = json.optBoolean("scanning")
    }
}
