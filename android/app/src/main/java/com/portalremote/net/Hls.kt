package com.portalremote.net

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * An HLS master playlist is a list of the same video at different sizes. Casting the
 * master hands the receiver the choice; listing the variants hands it to whoever is
 * holding the phone, which is the point of showing resolutions at all.
 */
object Hls {
    /** Bigger than any real master playlist, small enough that a mis-sniffed URL can't hurt. */
    private const val MAX_BYTES = 512L * 1024

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")

    /**
     * The variants a master playlist offers, best first. Empty for a media playlist —
     * one that lists segments rather than other playlists — which is already castable
     * as it stands.
     */
    fun parseMaster(playlist: String, baseUrl: String): List<Pair<String, Int?>> {
        val out = mutableListOf<Pair<String, Int?>>()
        var pending: Int? = null
        var sawStreamInf = false

        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    sawStreamInf = true
                    pending = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                sawStreamInf -> {
                    resolve(line, baseUrl)?.let { out.add(it to pending) }
                    pending = null
                    sawStreamInf = false
                }
            }
        }
        return out.sortedByDescending { it.second ?: -1 }
    }

    /** Absolute URL for a playlist entry, which may be relative to the master. */
    private fun resolve(uri: String, baseUrl: String): String? =
        runCatching { URI(baseUrl).resolve(uri).toString() }.getOrNull()

    /**
     * Fetch [url] and read its variants. [cookie] carries the browser's own session —
     * the CDN that served the page is usually the one refusing an anonymous request.
     * Returns empty on any failure: a playlist we can't read just stays a single entry.
     */
    suspend fun variants(
        url: String,
        cookie: String?,
        referer: String?,
        client: OkHttpClient = OkHttpClient(),
    ): List<Pair<String, Int?>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).apply {
                cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                referer?.let { header("Referer", it) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                parseMaster(response.peekBody(MAX_BYTES).string(), url)
            }
        }.getOrDefault(emptyList())
    }
}
