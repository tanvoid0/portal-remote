package com.portalremote.net

import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One line of the conversation. */
data class ChatTurn(
    val role: String,
    val text: String,
    /**
     * The stream carrying this reply stopped without saying it was finished, so what is
     * here is however much arrived. Kept and shown rather than discarded — half an answer
     * is usually still worth reading — with Regenerate offered beside it
     * (`docs/phase7-assistant.md` §4.4).
     */
    val incomplete: Boolean = false,
) {
    val fromUser: Boolean get() = role == USER

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** What one `data:` line of the server-sent stream turned out to be. */
sealed interface ChatEvent {
    /** More text for the reply being assembled. */
    data class Delta(val text: String) : ChatEvent

    /** Upstream said `[DONE]`. The reply is whole. */
    data object Done : ChatEvent

    /** A frame that carries nothing to show — a keep-alive, a role-only first chunk, a
     *  comment. Skipped rather than treated as an empty delta. */
    data object Ignore : ChatEvent
}

/**
 * Client for the PC's `/ai/chat` — step 7b of `docs/phase7-assistant.md`.
 *
 * This talks to **Portal Remote's own server**, never to agent-platform. The PC holds
 * that credential and reaches the daemon over loopback; the phone would have to be handed
 * a token for an entirely different trust domain to do it directly (§3).
 *
 * The conversation is sent in full on every turn and held nowhere but memory. Persisting
 * it is a decision nobody has made yet (§11.5), and a chat log is not a thing to start
 * writing to disk by accident.
 */
class AiChat(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // A model thinking is not a stalled socket. The stream ending is the only
        // signal that means anything here, so there is no read timeout to apply.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    /**
     * Stream a reply to [history] (which must end with the user's message).
     *
     * Emits [ChatEvent.Delta] as tokens arrive and [ChatEvent.Done] only if the stream
     * says so. **Ending without a `Done` is the signal for an incomplete reply** — that is
     * the whole reason `Done` is modelled rather than inferred from the flow completing.
     *
     * Throws [AiChatException] when the PC refuses the request outright, which is a
     * different thing from a stream that died: nothing was shown yet, so there is nothing
     * partial to keep.
     */
    fun stream(host: SavedHost, history: List<ChatTurn>): Flow<ChatEvent> = flow {
        val payload = JSONObject().put(
            "messages",
            JSONArray().apply {
                history.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.text))
                }
            },
        )

        val request = Request.Builder()
            .url("${host.httpBase}/ai/chat")
            .header("Authorization", "Bearer ${host.token}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // The PC forwards agent-platform's own body on an upstream failure, and
                // that names the real cause — an unknown model id, a provider with no
                // key — far better than a status code does.
                throw AiChatException(describe(response.code, response.body?.string()))
            }

            val source = response.body?.source() ?: throw AiChatException("empty response")
            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val event = parse(line)) {
                    is ChatEvent.Delta -> emit(event)
                    ChatEvent.Done -> {
                        emit(ChatEvent.Done)
                        return@use
                    }
                    ChatEvent.Ignore -> {}
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON = "application/json".toMediaType()

        /**
         * One line of the stream, classified. Pure, so the parsing this whole screen
         * depends on is testable without a socket.
         *
         * Only `data:` lines carry anything. Blank lines separate events, `:` lines are
         * comments used as keep-alives, and the first chunk of an OpenAI stream usually
         * carries `{"role":"assistant"}` with no content at all — none of which are text
         * and none of which are errors.
         */
        fun parse(line: String): ChatEvent {
            if (!line.startsWith("data:")) return ChatEvent.Ignore
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return ChatEvent.Ignore
            if (payload == "[DONE]") return ChatEvent.Done

            val delta = runCatching {
                JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
            }.getOrNull()

            // A frame we can't parse is not worth killing a reply over — the next one is
            // usually fine, and the stream ending is what actually reports failure.
            return if (delta.isNullOrEmpty()) ChatEvent.Ignore else ChatEvent.Delta(delta)
        }

        /** Human-readable failure, preferring whatever the PC said over the status code. */
        fun describe(code: Int, body: String?): String {
            val json = runCatching { JSONObject(body ?: "") }.getOrNull()
                ?: return "The PC answered HTTP $code."
            // A 503 from us is the availability model talking, and its `detail` is the
            // sentence written for exactly this moment.
            json.optString("detail").ifBlank { null }?.let { return it }
            json.optString("error").ifBlank { null }?.let { return it }
            return "The PC answered HTTP $code."
        }
    }
}

/** The request never produced a stream. Distinct from a stream that stopped early. */
class AiChatException(message: String) : Exception(message)
