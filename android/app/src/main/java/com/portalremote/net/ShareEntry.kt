package com.portalremote.net

import org.json.JSONObject

/**
 * One line in the share history — sent or received. Kept in memory only: a share
 * is a hand-off between two devices in front of you, not a mailbox, and anything
 * that mattered is already on the clipboard or in the PC's Inbox folder.
 */
data class ShareEntry(
    val id: Long,
    val incoming: Boolean,
    val kind: String,
    val text: String? = null,
    val fileName: String? = null,
    /** Server-relative path under the share root, for a received file. */
    val path: String? = null,
    val from: String,
    /** Non-null while an outgoing share is still in flight or has failed. */
    val status: String? = null,
) {
    /** One-line form for a list row or a notification. */
    val preview: String
        get() = (text ?: fileName ?: kind).lineSequence().firstOrNull()?.trim().orEmpty()

    /** Outgoing and not across yet. The app retries these on the next reconnect;
     *  the row is tappable to try immediately. */
    val isQueued: Boolean get() = !incoming && status != null

    companion object {
        /** Parse a server `{"t":"share",…}` push. Returns null if it isn't one. */
        fun fromPush(json: JSONObject, id: Long): ShareEntry? {
            if (json.optString("t") != "share") return null
            return ShareEntry(
                id = id,
                incoming = true,
                kind = json.optString("kind", ShareKind.TEXT),
                text = json.optString("text").ifBlank { null },
                fileName = json.optString("file").ifBlank { null },
                path = json.optString("path").ifBlank { null },
                from = json.optString("from").ifBlank { null } ?: "your PC",
            )
        }
    }
}
