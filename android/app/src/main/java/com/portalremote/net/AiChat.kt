package com.portalremote.net

import org.json.JSONObject

/**
 * One line of the conversation.
 *
 * **The transcript is the PC's, not this phone's.** It used to live in memory here and
 * nowhere else; it now lives on the PC, is persisted there, and is pushed to every client
 * watching it — this phone, the desktop window, a second phone. So nothing in this file
 * *builds* a turn: it reads the ones the PC sends.
 *
 * An assistant turn carries both halves of an answer — the prose it streamed, and the
 * [plan] it proposed if the same question also mapped onto something the PC can do. They
 * are one turn because they answer one question.
 */
data class ChatTurn(
    val id: String,
    val role: String,
    val text: String,
    /** Unix milliseconds, from the PC's clock. */
    val at: Long = 0,
    /** Tokens are still arriving. */
    val streaming: Boolean = false,
    /**
     * The stream carrying this reply stopped without saying it was finished, so what is
     * here is however much arrived. Kept and shown rather than discarded — half an answer
     * is usually still worth reading — with Regenerate offered beside it
     * (`docs/phase7-assistant.md` §4.4).
     */
    val incomplete: Boolean = false,
    /** A plan decision is in flight. A local model takes tens of seconds, so "still
     *  thinking" and "there was nothing to do" have to look different while it does. */
    val deciding: Boolean = false,
    /** Why nothing came back. Distinct from [incomplete], which has text. */
    val error: String? = null,
    val plan: AiPlan? = null,
) {
    val fromUser: Boolean get() = role == USER

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"

        fun fromJson(json: JSONObject): ChatTurn = ChatTurn(
            id = json.optString("id"),
            role = json.optString("role"),
            text = json.optString("text"),
            at = json.optLong("at"),
            streaming = json.optBoolean("streaming"),
            incomplete = json.optBoolean("incomplete"),
            deciding = json.optBoolean("deciding"),
            error = json.optStringOrNull("error"),
            plan = json.optJSONObject("plan")?.let { AiPlan.fromJson(it) },
        )
    }
}

/**
 * The three messages the PC sends about the conversation, applied to a list of turns.
 *
 * Pure functions on purpose: this is the whole of the phone's chat state machine now that
 * the streaming lives on the other machine, and "did the right token land on the right
 * turn" is not a thing to find out on a device.
 */
object AiTranscript {
    /** `{"t":"ai_chat","turns":[…]}` — everything, sent on connect and after a clear. */
    fun snapshot(json: JSONObject): List<ChatTurn> {
        val turns = json.optJSONArray("turns") ?: return emptyList()
        return (0 until turns.length()).mapNotNull { i ->
            turns.optJSONObject(i)?.let { ChatTurn.fromJson(it) }
        }
    }

    /**
     * `{"t":"ai_turn","turn":{…}}` — one turn added or changed.
     *
     * Upsert rather than append: the same turn arrives several times as it grows a reply,
     * finishes, gains a plan, and then records what running it did.
     */
    fun upsert(turns: List<ChatTurn>, turn: ChatTurn): List<ChatTurn> {
        val at = turns.indexOfFirst { it.id == turn.id }
        return if (at < 0) turns + turn else turns.toMutableList().apply { this[at] = turn }
    }

    /**
     * `{"t":"ai_delta","id":…,"text":…}` — more text for a turn already on screen.
     *
     * Its own message because a reply is one turn growing a few hundred times, and
     * re-sending the whole turn per token would put the transcript on the wire once per
     * word. A delta for an id we don't have is dropped, not appended somewhere else.
     */
    fun delta(turns: List<ChatTurn>, id: String, text: String): List<ChatTurn> {
        val at = turns.indexOfFirst { it.id == id }
        if (at < 0) return turns
        return turns.toMutableList().apply { this[at] = this[at].copy(text = this[at].text + text) }
    }
}
