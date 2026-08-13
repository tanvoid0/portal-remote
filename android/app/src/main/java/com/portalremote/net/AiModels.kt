package com.portalremote.net

import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** One provider agent-platform knows about, and whether this machine has it configured. */
data class AiProvider(val id: String, val configured: Boolean)

/** One model a provider answers to. Carries its own [configured] rather than being
 *  omitted when it isn't: the picker greys it out with a reason instead of a list that
 *  silently shrinks (`docs/design-system.md` §4.5). */
data class AiModel(val id: String, val provider: String, val configured: Boolean)

/** What `GET /ai/models` returns: the pair currently answering `/ai/chat`, and every
 *  provider/model this PC could switch to instead. */
data class AiCatalog(
    val currentProvider: String?,
    val currentModel: String?,
    val providers: List<AiProvider>,
    val models: List<AiModel>,
) {
    /** After [AiModels.select] succeeds — the list doesn't change, only which entry is
     *  current, so there's no reason to re-fetch it. */
    fun withCurrent(provider: String?, model: String?) =
        copy(currentProvider = provider, currentModel = model)

    companion object {
        fun parse(json: JSONObject): AiCatalog {
            val current = json.optJSONObject("current")
            return AiCatalog(
                currentProvider = current?.optString("provider")?.ifBlank { null },
                currentModel = current?.optString("model")?.ifBlank { null },
                providers = json.optJSONArray("providers")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        AiProvider(o.getString("id"), o.optBoolean("configured"))
                    }
                } ?: emptyList(),
                models = json.optJSONArray("models")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        AiModel(o.getString("id"), o.getString("provider"), o.optBoolean("configured"))
                    }
                } ?: emptyList(),
            )
        }
    }
}

/** The request never got a catalogue, or the PC refused to switch. */
class AiModelsException(message: String) : Exception(message)

/**
 * Client for the PC's `GET /ai/models` and `POST /ai/model` — lets the phone see and
 * change which provider/model answers the assistant, instead of the two being fixed at
 * whatever was hand-edited into the PC's config file (`docs/phase7-assistant.md` §11.2).
 *
 * The last HTTP the assistant needs. The conversation itself moved onto the control socket
 * when the PC became the thing that owns it — see [AiTranscript].
 */
class AiModels(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(host: SavedHost): AiCatalog = withContext(Dispatchers.IO) {
        val request = authedRequest(host, "${host.httpBase}/ai/models").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) throw AiModelsException(describe(response.code, body))
            AiCatalog.parse(JSONObject(body ?: "{}"))
        }
    }

    /** [provider] may be blank — that means "let agent-platform resolve [model] on its
     *  own", the same zero-setup state the PC starts in. */
    suspend fun select(host: SavedHost, provider: String?, model: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("model", model).apply {
            if (!provider.isNullOrBlank()) put("provider", provider)
        }
        val request = authedRequest(host, "${host.httpBase}/ai/model")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) throw AiModelsException(describe(response.code, body))
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /**
         * Human-readable failure, preferring whatever the PC said over the status code.
         *
         * A 503 from us is the availability model talking, and its `detail` is the sentence
         * written for exactly this moment — "agent-platform isn't running" is a better
         * answer to "why can't I pick a model" than a number.
         */
        fun describe(code: Int, body: String?): String {
            val json = runCatching { JSONObject(body ?: "") }.getOrNull()
                ?: return "The PC answered HTTP $code."
            json.optString("detail").ifBlank { null }?.let { return it }
            json.optString("error").ifBlank { null }?.let { return it }
            return "The PC answered HTTP $code."
        }
    }
}
