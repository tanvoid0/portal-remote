package com.portalremote.net

import android.content.Context
import android.net.Uri
import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** What a share is, which decides what the receiving side offers to do with it.
 *  Mirrors `ShareKind` on the server. */
object ShareKind {
    const val TEXT = "text"
    const val LINK = "link"
    const val IMAGE = "image"
    const val FILE = "file"

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** A link is worth distinguishing from plain text: it's the one kind where the
     *  useful action is "open" rather than "paste". Kept in step with the server's
     *  own guess so an item doesn't change kind in transit. */
    fun forText(text: String): String {
        val trimmed = text.trim()
        val looksLikeUrl = (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) && trimmed.none { it.isWhitespace() }
        return if (looksLikeUrl) LINK else TEXT
    }

    fun forFile(name: String): String =
        if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) IMAGE else FILE
}

/** Client for the server's `/share` endpoints — the phone's end of quick share. */
class ShareApi(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun sendText(host: SavedHost, text: String, from: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("text", text)
            put("from", from)
        }.toString().toRequestBody(JSON)

        val request = authedRequest(host, "${host.httpBase}/share/text").post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw FileApiException("share failed: HTTP ${resp.code}", resp.code)
        }
    }

    suspend fun sendFile(
        context: Context,
        host: SavedHost,
        fileName: String,
        contentType: String?,
        uri: Uri,
        from: String,
    ) = withContext(Dispatchers.IO) {
        val mediaType = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("from", from)
            .addFormDataPart("file", fileName, streamRequestBody(context, uri, mediaType))
            .build()

        val request = authedRequest(host, "${host.httpBase}/share/upload").post(multipart).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw FileApiException("share failed: HTTP ${resp.code}", resp.code)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
