package com.portalremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

// Its own store rather than a corner of portal_remote_prefs: browsing data is the
// one thing here a user may want to wipe on its own, and "clear browsing data"
// should not be able to take the pairing with it.
private val Context.browserStore by preferencesDataStore(name = "browser")

/** Cap on remembered history. Old enough to be useless, long enough to find yesterday. */
private const val HISTORY_LIMIT = 500

data class Bookmark(val url: String, val title: String)

data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

enum class SearchEngine(val label: String, private val template: String) {
    // First because it is the one that doesn't build a profile of the person typing.
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s");

    fun urlFor(query: String): String =
        template.format(java.net.URLEncoder.encode(query, "UTF-8"))
}

data class BrowserSettings(
    val adBlockEnabled: Boolean = true,
    /** Hosts the user has switched blocking *off* for — sites that break with it on. */
    val allowedHosts: Set<String> = emptySet(),
    val blockThirdPartyCookies: Boolean = true,
    val saveHistory: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
)

/** Bookmarks, history and the browser's own preferences. */
class BrowserStore(private val context: Context) {
    private object Keys {
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val HISTORY = stringPreferencesKey("history")
        val ADBLOCK = booleanPreferencesKey("adblock_enabled")
        val ALLOWED = stringPreferencesKey("adblock_allowed_hosts")
        val THIRD_PARTY_COOKIES = booleanPreferencesKey("block_third_party_cookies")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
    }

    val settings: Flow<BrowserSettings> = context.browserStore.data.map { prefs ->
        val defaults = BrowserSettings()
        BrowserSettings(
            adBlockEnabled = prefs[Keys.ADBLOCK] ?: defaults.adBlockEnabled,
            allowedHosts = prefs[Keys.ALLOWED].orEmpty()
                .split('\n').filter { it.isNotBlank() }.toSet(),
            blockThirdPartyCookies = prefs[Keys.THIRD_PARTY_COOKIES] ?: defaults.blockThirdPartyCookies,
            saveHistory = prefs[Keys.SAVE_HISTORY] ?: defaults.saveHistory,
            searchEngine = prefs[Keys.SEARCH_ENGINE]
                ?.let { name -> SearchEngine.entries.firstOrNull { it.name == name } }
                ?: defaults.searchEngine,
        )
    }

    val bookmarks: Flow<List<Bookmark>> = context.browserStore.data.map { prefs ->
        decodeBookmarks(prefs[Keys.BOOKMARKS])
    }

    val history: Flow<List<HistoryEntry>> = context.browserStore.data.map { prefs ->
        decodeHistory(prefs[Keys.HISTORY])
    }

    suspend fun saveSettings(settings: BrowserSettings) {
        context.browserStore.edit { prefs ->
            prefs[Keys.ADBLOCK] = settings.adBlockEnabled
            prefs[Keys.ALLOWED] = settings.allowedHosts.joinToString("\n")
            prefs[Keys.THIRD_PARTY_COOKIES] = settings.blockThirdPartyCookies
            prefs[Keys.SAVE_HISTORY] = settings.saveHistory
            prefs[Keys.SEARCH_ENGINE] = settings.searchEngine.name
        }
    }

    /** Add if absent, remove if present — the star button is one control, not two. */
    suspend fun toggleBookmark(url: String, title: String) {
        context.browserStore.edit { prefs ->
            val current = decodeBookmarks(prefs[Keys.BOOKMARKS])
            val next = if (current.any { it.url == url }) {
                current.filterNot { it.url == url }
            } else {
                current + Bookmark(url, title.ifBlank { url })
            }
            prefs[Keys.BOOKMARKS] = encodeBookmarks(next)
        }
    }

    suspend fun removeBookmark(url: String) {
        context.browserStore.edit { prefs ->
            prefs[Keys.BOOKMARKS] =
                encodeBookmarks(decodeBookmarks(prefs[Keys.BOOKMARKS]).filterNot { it.url == url })
        }
    }

    suspend fun recordVisit(url: String, title: String, at: Long) {
        context.browserStore.edit { prefs ->
            if (prefs[Keys.SAVE_HISTORY] == false) return@edit
            prefs[Keys.HISTORY] = encodeHistory(
                mergeVisit(decodeHistory(prefs[Keys.HISTORY]), HistoryEntry(url, title, at))
            )
        }
    }

    suspend fun clearHistory() {
        context.browserStore.edit { prefs -> prefs.remove(Keys.HISTORY) }
    }

    companion object {
        /**
         * Newest first, one entry per URL, capped. Revisiting a page moves it up rather
         * than adding a duplicate — a history full of the same URL is a history nobody
         * can scroll.
         */
        fun mergeVisit(existing: List<HistoryEntry>, visit: HistoryEntry): List<HistoryEntry> =
            (listOf(visit) + existing.filterNot { it.url == visit.url }).take(HISTORY_LIMIT)

        fun encodeBookmarks(items: List<Bookmark>): String =
            JSONArray().apply {
                items.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) }
            }.toString()

        fun decodeBookmarks(raw: String?): List<Bookmark> = readArray(raw) { item ->
            Bookmark(item.optString("url"), item.optString("title"))
        }.filter { it.url.isNotBlank() }

        fun encodeHistory(items: List<HistoryEntry>): String =
            JSONArray().apply {
                items.forEach {
                    put(
                        JSONObject()
                            .put("url", it.url)
                            .put("title", it.title)
                            .put("at", it.visitedAt)
                    )
                }
            }.toString()

        fun decodeHistory(raw: String?): List<HistoryEntry> = readArray(raw) { item ->
            HistoryEntry(item.optString("url"), item.optString("title"), item.optLong("at"))
        }.filter { it.url.isNotBlank() }

        private fun <T> readArray(raw: String?, read: (JSONObject) -> T): List<T> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(read(it)) }
                }
            }
        }
    }
}
