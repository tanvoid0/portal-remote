package com.portalremote.net

import org.json.JSONObject

/**
 * The PC's foreground app, pushed as `active_win` while Deck's context row is watching —
 * a touch-bar-style row that changes with whatever window has focus over there. [process]
 * is the bare executable name (`"chrome"`, `"explorer"`, no `.exe`); [contextActionsFor]
 * in `DeckScreen.kt` lowercases it before matching.
 */
data class ActiveWindow(val process: String, val title: String) {
    companion object {
        fun fromPush(json: JSONObject) = ActiveWindow(
            process = json.optString("process"),
            title = json.optString("title"),
        )
    }
}
