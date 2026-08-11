package com.portalremote.net

import android.content.Context
import android.net.Uri
import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import java.net.URLEncoder

data class RemoteFileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String,
)

class FileApiException(message: String, val statusCode: Int? = null) : Exception(message)

/** Client for the server's `/files` HTTP endpoints. */
class FileApi(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun list(host: SavedHost, path: String): List<RemoteFileEntry> = withContext(Dispatchers.IO) {
        val url = "${host.httpBase}/files/list?path=${encode(path)}"
        val request = authedRequest(host, url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw FileApiException("list failed: HTTP ${resp.code}", resp.code)
            val arr = JSONArray(resp.body?.string() ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteFileEntry(
                    name = o.getString("name"),
                    isDir = o.getBoolean("isDir"),
                    size = o.getLong("size"),
                    modified = o.optString("modified"),
                )
            }
        }
    }

    fun downloadUrl(host: SavedHost, path: String): String =
        "${host.httpBase}/files/download?path=${encode(path)}"

    /**
     * Uploads the content behind [uri] by streaming directly from the
     * ContentResolver, so multi-hundred-MB files (photos, videos) don't have to
     * be fully buffered in memory first.
     */
    suspend fun upload(
        context: Context,
        host: SavedHost,
        dirPath: String,
        fileName: String,
        contentType: String?,
        uri: Uri,
    ) = withContext(Dispatchers.IO) {
        val mediaType = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
        val body = streamRequestBody(context, uri, mediaType)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, body)
            .build()
        val url = "${host.httpBase}/files/upload?path=${encode(dirPath)}"
        val request = authedRequest(host, url).post(multipart).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw FileApiException("upload failed: HTTP ${resp.code}", resp.code)
        }
    }

    private fun authedRequest(host: SavedHost, url: String) =
        Request.Builder().url(url).header("Authorization", "Bearer ${host.token}")

    private fun streamRequestBody(context: Context, uri: Uri, mediaType: MediaType?): RequestBody =
        object : RequestBody() {
            override fun contentType() = mediaType

            override fun contentLength(): Long =
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("could not open $uri")
                input.use { sink.writeAll(it.source()) }
            }
        }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}
