package com.portalremote.net

/** A castable stream spotted on the page being browsed. */
data class FoundMedia(
    val url: String,
    val kind: String,
    /** Page title when it was found — a better cast label than a CDN filename. */
    val pageTitle: String?,
) {
    /** Last path segment, for when the page title is missing or useless. */
    val fileName: String
        get() = url.substringBefore('?').substringAfterLast('/').ifBlank { url }

    val label: String get() = pageTitle?.takeIf { it.isNotBlank() } ?: fileName
}

/**
 * Spots media URLs in the browser's own traffic — the trick the whole app rests on
 * (see `docs/phase4-casting.md` §1). The receiver fetches the original file, so
 * nothing here re-encodes anything and "original quality" is automatic.
 */
object MediaSniffer {
    /**
     * What kind of stream a URL points at, or null if it isn't one.
     *
     * ponytail: extension matching only. It misses CDNs that serve media from
     * extension-less URLs, which is what the `<video>` scan in BrowserScreen is for;
     * the complete fix is reading `Content-Type` off the response, and that means
     * fetching every subresource ourselves instead of letting WebView do it.
     */
    fun classify(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".webm") -> "WebM"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".m4a") || path.endsWith(".mp3") -> "Audio"
            // Deliberately not .ts: HLS segments arrive by the hundred and casting one
            // gets you two seconds of video. The .m3u8 above is the castable thing.
            else -> null
        }
    }

    /**
     * True for a source a receiver can never fetch. Media Source Extensions hand
     * `<video>` a blob built in the page's own memory, so there is no URL to hand on —
     * this is the YouTube case, and the honest answer is to say so rather than cast
     * something that will fail.
     */
    fun isUnfetchable(url: String): Boolean =
        url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("mediasource:")
}
