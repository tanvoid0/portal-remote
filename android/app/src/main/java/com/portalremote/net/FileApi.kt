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
        /** Bytes written so far and the total, or -1 when the provider won't say.
         *  Called from the IO thread — hop before touching Compose state. */
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val mediaType = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
        val body = streamRequestBody(context, uri, mediaType, onProgress)
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

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}

internal fun authedRequest(host: SavedHost, url: String) =
    Request.Builder().url(url).header("Authorization", "Bearer ${host.token}")

/** Copy chunk. 64KB is okio's own segment-pool sizing; small enough that a progress
 *  callback fires often on a slow link, large enough not to be the bottleneck. */
private const val COPY_CHUNK = 64L * 1024

/**
 * Streams [uri]'s content straight from the ContentResolver, so multi-hundred-MB
 * files (photos, videos) don't have to be fully buffered in memory first. Shared
 * with [ShareApi] — both endpoints take the same multipart form.
 *
 * With [onProgress] the copy is chunked rather than a single `writeAll`, so the
 * caller can show a *determinate* bar — the transfer is a known number of bytes over
 * a LAN, and an indeterminate spinner would misreport it as unknown-duration (see
 * docs/design-system.md §7). Without it the fast path is unchanged.
 */
internal fun streamRequestBody(
    context: Context,
    uri: Uri,
    mediaType: MediaType?,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
): RequestBody =
    object : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength(): Long =
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("could not open $uri")
            val report = onProgress
            input.use {
                if (report == null) {
                    sink.writeAll(it.source())
                    return
                }
                val total = contentLength()
                val source = it.source()
                var sent = 0L
                report(0, total)
                while (true) {
                    val read = source.read(sink.buffer, COPY_CHUNK)
                    if (read == -1L) break
                    sent += read
                    // Hand it to the socket as we go, or `sink.buffer` grows into the
                    // whole file and the streaming this function exists for is undone.
                    sink.emitCompleteSegments()
                    report(sent, total)
                }
            }
        }
    }
