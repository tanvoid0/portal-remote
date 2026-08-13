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
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /**
     * Where the installed build sits against the newest release. Three answers, not two:
     * a build that was never tagged is not a point on the release line at all, and "you
     * are on a build that was never released" is a different thing to say than "you are
     * up to date".
     */
    enum class Standing { Behind, Same, Unreleased }

    /** A build nobody tagged — the `-dev` default from `build.gradle`, or CI off an
     *  untagged ref. Its digits are whatever was last checked in, so they say nothing
     *  about where it sits against a release: the answer is always "don't update". */
    fun isDevBuild(version: String): Boolean =
        version.trim().endsWith("-dev", ignoreCase = true)

    fun standing(installed: String, latest: String): Standing = when {
        isDevBuild(installed) -> Standing.Unreleased
        compare(latest, installed) > 0 -> Standing.Behind
        // Built after a release but before the next tag: still nothing to update to.
        compare(installed, latest) > 0 -> Standing.Unreleased
        else -> Standing.Same
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
