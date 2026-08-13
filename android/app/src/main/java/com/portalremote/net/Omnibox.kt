package com.portalremote.net

import com.portalremote.data.SearchEngine

/**
 * Decides whether what was typed in the address bar is a place or a question.
 *
 * Getting this wrong is the most-felt bug in a browser: a search that navigates to a
 * nonexistent host, or a hostname that goes to a search engine, both happen on every
 * single use of the bar.
 */
object Omnibox {
    private val schemeLike = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun resolve(input: String, engine: SearchEngine): String? {
        val text = input.trim()
        if (text.isEmpty()) return null

        // An explicit scheme is a decision the user already made. Anything that isn't
        // http(s) is refused rather than searched for — `intent://` typed by hand is
        // still `intent://`.
        if (schemeLike.containsMatchIn(text)) return CastUrl.normalize(text)

        // A space is the one unambiguous signal: no hostname contains one.
        if (' ' in text) return engine.urlFor(text)

        // "localhost:8765" and "127.0.0.1" are addresses without a dotted TLD.
        val host = text.substringBefore('/').substringBefore('?')
        val looksLikeHost = '.' in host || host.startsWith("localhost")
        if (!looksLikeHost) return engine.urlFor(text)

        return CastUrl.normalize(text) ?: engine.urlFor(text)
    }
}
