package com.portalremote.net

import org.json.JSONObject

/**
 * `optString` for a key the sender may have left out — or sent as JSON `null`.
 *
 * `JSONObject.optString` maps a JSON `null` to the four-character string `"null"`, not
 * to `""`, so the usual `optString(key).ifBlank { null }` keeps it and the UI renders
 * the word. That is how "null • null" ended up under the now-playing title whenever
 * Windows reported a track with no artist or album.
 */
fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
