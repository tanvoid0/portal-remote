package com.portalremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A published build on GitHub: the tag's version, and the APK hanging off it. */
data class Release(val version: String, val apkUrl: String, val notes: String)

/**
 * "Is there a newer Portal Remote?", answered by the project's own GitHub releases —
 * the same ones CI publishes on a `v*` tag. Sideloaded builds get no Play Store to
 * update them, so without this the only upgrade path is noticing the repo changed.
 *
 * Deliberately unauthenticated: the release list of a public repo is public, and an
 * API token in a sideloaded APK is a token everyone has.
 */
object Updates {

    private const val LATEST_URL = "https://api.github.com/repos/tanvoid0/portal-remote/releases/latest"

    /** Null when the release carries no APK — a server-only release, or one still
     *  uploading its assets. */
    fun parse(json: JSONObject): Release? {
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isBlank()) return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        return Release(version, apk.optString("browser_download_url"), json.optString("body"))
    }

    /**
     * Numeric-segment compare, so 0.10.0 beats 0.9.0 where a string compare wouldn't.
     * Anything non-numeric in a segment (a `-rc1` suffix) counts as 0: a pre-release
     * of a version should not read as newer than the release itself.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun latest(client: OkHttpClient = OkHttpClient()): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) return@withContext null
            parse(JSONObject(body))
        }
    }

    /** Streamed to [target] rather than buffered: an APK is tens of megabytes, and
     *  the phone asking for the update is the one least able to spare the heap. */
    suspend fun download(release: Release, target: File, client: OkHttpClient = OkHttpClient()): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(release.apkUrl).build()
            client.newCall(request).execute().use { response ->
                val body = response.body ?: throw IllegalStateException("Empty download")
                if (!response.isSuccessful) throw IllegalStateException("Download failed (${response.code})")
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            target
        }
}
