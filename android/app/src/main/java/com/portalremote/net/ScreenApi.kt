package com.portalremote.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.portalremote.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import org.json.JSONObject
import java.io.EOFException
import java.util.concurrent.TimeUnit

/** One display attached to the paired PC. [index] is what the server wants back. */
data class RemoteMonitor(
    val index: Int,
    val name: String,
    val primary: Boolean,
    val width: Int,
    val height: Int,
) {
    val label: String get() = "Display ${index + 1}"
}

/** Client for the server's `/screen` endpoints — monitor list and the MJPEG mirror. */
class ScreenApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // The mirror is a single response that never ends, so a read timeout would
        // kill it the moment the desktop sits still long enough to compress well.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    suspend fun monitors(host: SavedHost): List<RemoteMonitor> = withContext(Dispatchers.IO) {
        val request = authed(host, "${host.httpBase}/screen/monitors").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw FileApiException("monitors failed: HTTP ${resp.code}", resp.code)
            val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("monitors")
                ?: return@use emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteMonitor(
                    index = o.getInt("index"),
                    name = o.optString("name"),
                    primary = o.optBoolean("primary"),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                )
            }
        }
    }

    /**
     * Decoded frames of the desktop, until the collector stops or the connection drops.
     *
     * The transport is `multipart/x-mixed-replace`: one HTTP response containing an
     * endless run of JPEGs separated by a boundary line. Every part carries a
     * Content-Length, so this reads exactly that many bytes rather than scanning the
     * body for a boundary that could legitimately occur inside JPEG data.
     */
    fun frames(
        host: SavedHost,
        monitor: Int?,
        fps: Int,
        width: Int,
        quality: Int,
    ): Flow<Bitmap> = flow {
        val url = buildString {
            append("${host.httpBase}/screen/mjpeg?fps=$fps&width=$width&quality=$quality")
            if (monitor != null) append("&monitor=$monitor")
        }
        val call = client.newCall(authed(host, url).build())
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw FileApiException("mirror failed: HTTP ${resp.code}", resp.code)
                val source = resp.body?.source() ?: throw FileApiException("mirror returned no body")
                while (true) {
                    val length = readPartLength(source) ?: break
                    val jpeg = source.readByteArray(length.toLong())
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                    emit(bitmap)
                }
            }
        } finally {
            // Cancelling the collector unwinds through emit(), but the socket only
            // actually closes when the call is cancelled — otherwise the server keeps
            // capturing frames for a phone that stopped watching.
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Consume one part's headers; returns its body length, or null at end of stream. */
    private fun readPartLength(source: BufferedSource): Int? {
        var length = -1
        while (true) {
            val line = try {
                source.readUtf8LineStrict()
            } catch (_: EOFException) {
                return null
            }
            // A blank line ends the headers; everything before it is the boundary
            // marker plus this part's own headers.
            if (line.isEmpty()) return length.takeIf { it >= 0 }
            val separator = line.indexOf(':')
            if (separator > 0 && line.take(separator).equals("Content-Length", ignoreCase = true)) {
                length = line.substring(separator + 1).trim().toIntOrNull() ?: -1
            }
        }
    }

    private fun authed(host: SavedHost, url: String) =
        Request.Builder().url(url).header("Authorization", "Bearer ${host.token}")
}
