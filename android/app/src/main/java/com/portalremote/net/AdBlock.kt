package com.portalremote.net

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "AdBlock"

/** Refetched no more often than this; EasyList changes daily but nobody notices a week. */
private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Hostname-level ad blocking for the in-app browser.
 *
 * ponytail: this understands exactly one kind of EasyList rule — `||host^`, the plain
 * domain block, which is the bulk of the list and the bulk of the requests. Rules with
 * options (`$third-party`), wildcards, path patterns and cosmetic (`##`) rules are all
 * skipped, so pages load without ads but may still have the empty boxes where they
 * were. If that isn't good enough, the upgrade is a real engine — Brave's adblock-rust
 * via JNI, or Edsuns/AdblockAndroid — not more regex here.
 *
 * Skipping option rules is deliberate rather than lazy: `||example.com^$third-party`
 * blocks a domain *only* in third-party context, and applying it unconditionally would
 * block first-party requests too, which breaks the site outright.
 */
class AdBlock {
    @Volatile
    private var hosts: Set<String> = SEED

    @Volatile
    var blockedCount: Int = 0
        private set

    /** True while only the built-in seed is loaded, so the UI can say so honestly. */
    @Volatile
    var usingSeedOnly: Boolean = true
        private set

    val ruleCount: Int get() = hosts.size

    /** Master switch. Off means every request passes, whatever the lists say. */
    @Volatile
    var enabled: Boolean = true

    /** Sites the user has turned blocking off for, by host. */
    @Volatile
    var allowedHosts: Set<String> = emptySet()

    /**
     * Whether to block [url] while browsing [pageHost].
     *
     * The allowlist is keyed on the *page*, not the request: "don't block on this
     * site" has to exempt the third-party requests that site makes, which is the
     * entire point of turning it off. Keying on the request host would exempt
     * nothing that was actually being blocked.
     */
    fun shouldBlock(url: String, pageHost: String? = null): Boolean {
        if (!enabled) return false
        if (pageHost != null && isAllowed(pageHost, allowedHosts)) return false
        val host = hostOf(url) ?: return false
        if (!isBlockedHost(host, hosts)) return false
        blockedCount++
        return true
    }

    fun resetCount() {
        blockedCount = 0
    }

    /**
     * Load the cached rules, refreshing from the network if they're missing or stale.
     * Safe to call on every browser open — a fresh cache costs one file read.
     */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        // Its own client: this runs twice a week and shares nothing with the control
        // socket, which is tuned for a connection that never closes.
        val client = OkHttpClient()
        val cache = File(context.filesDir, "adblock-hosts.txt")

        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS) {
            adopt(cache.readLines().toSet())
            return@withContext
        }

        // Stale but present beats nothing while the refresh runs — and if the refresh
        // fails (offline, list moved) this is what we keep using.
        if (cache.isFile) adopt(cache.readLines().toSet())

        for (url in LIST_URLS) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body ?: return@use
                    if (!response.isSuccessful) return@use
                    val parsed = body.source().let { source ->
                        buildSet {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                hostRule(line)?.let { add(it) }
                            }
                        }
                    }
                    if (parsed.size > 100) {
                        val merged = hosts + parsed
                        adopt(merged)
                        cache.writeText(merged.joinToString("\n"))
                        Log.i(TAG, "loaded ${parsed.size} rules from $url")
                    }
                }
            } catch (e: Exception) {
                // A blocker that can't fetch its list still blocks the seed set; it is
                // never a reason to fail opening the browser.
                Log.w(TAG, "could not refresh $url", e)
            }
        }
    }

    private fun adopt(loaded: Set<String>) {
        if (loaded.isEmpty()) return
        hosts = loaded + SEED
        usingSeedOnly = loaded.size <= SEED.size
    }

    companion object {
        private val LIST_URLS = listOf(
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
        )

        /** Enough to block the obvious before the first list fetch lands. */
        val SEED = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagservices.com", "googletagmanager.com",
            "adservice.google.com", "adnxs.com", "adsrvr.org", "rubiconproject.com",
            "pubmatic.com", "openx.net", "criteo.com", "criteo.net", "taboola.com",
            "outbrain.com", "scorecardresearch.com", "quantserve.com", "moatads.com",
            "amazon-adsystem.com", "casalemedia.com", "sharethrough.com", "teads.tv",
            "smartadserver.com", "zedo.com", "adcolony.com", "applovin.com",
            "facebook.net", "hotjar.com", "mixpanel.com", "segment.io", "branch.io",
        )

        /**
         * The host from an EasyList line, or null if this isn't a rule we handle:
         * comments, exceptions (`@@`), cosmetic rules (`##`), anything with options
         * (`$`), wildcards or a path. See the class doc for why options are skipped.
         */
        fun hostRule(line: String): String? {
            val rule = line.trim()
            if (!rule.startsWith("||")) return null
            if (rule.contains('$') || rule.contains('*') || rule.contains("##")) return null

            val end = rule.indexOf('^', startIndex = 2)
            val host = (if (end == -1) rule.substring(2) else rule.substring(2, end)).lowercase()
            // Trailing junk after the separator means a path rule, not a domain rule.
            if (end != -1 && rule.length > end + 1) return null
            if (host.isEmpty() || '/' in host || '.' !in host) return null
            if (host.any { it !in "abcdefghijklmnopqrstuvwxyz0123456789.-" }) return null
            return host
        }

        /**
         * Whether blocking is switched off for [pageHost]. Allowing `example.com`
         * allows `www.example.com` too — nobody thinks of those as different sites,
         * and a toggle that only covers the exact subdomain looks broken the moment
         * the site redirects.
         */
        fun isAllowed(pageHost: String, allowed: Set<String>): Boolean =
            allowed.isNotEmpty() && isBlockedHost(pageHost, allowed)

        /** Strips `www.`, so the per-site toggle survives a redirect between the two. */
        fun siteKey(host: String): String = host.lowercase().removePrefix("www.")

        /** A host matches if it *is* a blocked domain or sits underneath one. */
        fun isBlockedHost(host: String, blocked: Set<String>): Boolean {
            var candidate = host.lowercase()
            while (true) {
                if (candidate in blocked) return true
                val dot = candidate.indexOf('.')
                // Stop before the bare TLD: a rule can't legitimately be "com".
                if (dot == -1 || candidate.indexOf('.', dot + 1) == -1) return false
                candidate = candidate.substring(dot + 1)
            }
        }

        fun hostOf(url: String): String? =
            runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
