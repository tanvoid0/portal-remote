package com.portalremote.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserStoreTest {
    @Test
    fun `bookmarks survive a round trip`() {
        val items = listOf(
            Bookmark("https://example.com", "Example"),
            Bookmark("https://example.com/x?a=1&b=2", "Query \"quoted\" title"),
        )
        assertEquals(items, BrowserStore.decodeBookmarks(BrowserStore.encodeBookmarks(items)))
    }

    @Test
    fun `corrupt or missing storage reads as empty, not a crash`() {
        assertEquals(emptyList(), BrowserStore.decodeBookmarks(null))
        assertEquals(emptyList(), BrowserStore.decodeBookmarks(""))
        assertEquals(emptyList(), BrowserStore.decodeBookmarks("not json"))
        assertEquals(emptyList(), BrowserStore.decodeHistory("{\"not\":\"an array\"}"))
    }

    @Test
    fun `revisiting moves an entry up instead of duplicating it`() {
        val existing = listOf(
            HistoryEntry("https://a.com", "A", 1),
            HistoryEntry("https://b.com", "B", 2),
        )
        val merged = BrowserStore.mergeVisit(existing, HistoryEntry("https://b.com", "B", 3))
        assertEquals(2, merged.size)
        assertEquals("https://b.com", merged.first().url)
        assertEquals(3, merged.first().visitedAt)
    }

    @Test
    fun `history is capped`() {
        var history = emptyList<HistoryEntry>()
        for (i in 1..600) {
            history = BrowserStore.mergeVisit(history, HistoryEntry("https://s$i.com", "s$i", i.toLong()))
        }
        assertEquals(500, history.size)
        // Newest kept, oldest dropped.
        assertEquals("https://s600.com", history.first().url)
        assertTrue(history.none { it.url == "https://s1.com" })
    }

    @Test
    fun `search engines encode the query`() {
        val url = SearchEngine.DUCKDUCKGO.urlFor("a b&c")
        assertTrue(url.startsWith("https://duckduckgo.com/?q="))
        assertTrue("&c" !in url.removePrefix("https://duckduckgo.com/?q="))
    }
}
