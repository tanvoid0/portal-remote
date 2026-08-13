package com.portalremote.net

import org.json.JSONObject

/**
 * Whether the assistant's backend is up, pushed by the PC — step 7a of
 * `docs/phase7-assistant.md`.
 *
 * The phone never probes anything and never guesses: `agent-platformd` is a separate app
 * the user starts independently, so **unavailable is the normal case**, and the tab has
 * to be right the moment it opens rather than after a request has failed.
 */
data class AiState(
    val state: String,
    /** Why, in words meant for whoever has to fix it. */
    val detail: String? = null,
    /** The PC knows how to launch it. False until 7g builds that. */
    val canStart: Boolean = false,
) {
    val ready: Boolean get() = state == READY

    /** A launch is in flight — a spinner, not an error. */
    val starting: Boolean get() = state == STARTING

    /** What the tab says about itself. Never "unavailable": that's a state name, not
     *  something to put on a screen. */
    val headline: String
        get() = when (state) {
            READY -> "Assistant ready"
            STARTING -> "Starting the assistant…"
            UNCONFIGURED -> "Assistant not set up"
            else -> "Assistant is not running"
        }

    companion object {
        const val READY = "ready"
        const val STARTING = "starting"
        const val UNAVAILABLE = "unavailable"
        const val UNCONFIGURED = "unconfigured"

        /** `{"t":"ai_state","state":…,"detail":…,"canStart":…}`, or null if it isn't one. */
        fun fromPush(json: JSONObject): AiState? {
            val state = json.optString("state").ifBlank { return null }
            return AiState(
                state = state,
                detail = json.optString("detail").ifBlank { null },
                canStart = json.optBoolean("canStart"),
            )
        }
    }
}
