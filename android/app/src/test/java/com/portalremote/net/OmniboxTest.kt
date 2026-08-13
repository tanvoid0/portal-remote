package com.portalremote.net

import com.portalremote.data.SearchEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmniboxTest {
    private val engine = SearchEngine.DUCKDUCKGO

    @Test
    fun `addresses navigate`() {
        assertEquals("https://example.com", Omnibox.resolve("example.com", engine))
        assertEquals("https://example.com/a/b", Omnibox.resolve("example.com/a/b", engine))
        assertEquals("http://192.168.0.5:8765", Omnibox.resolve("http://192.168.0.5:8765", engine))
        assertEquals("http://localhost:8765", Omnibox.resolve("http://localhost:8765", engine))
    }

    @Test
    fun `words search`() {
        val result = Omnibox.resolve("big buck bunny", engine)!!
        assertTrue(result.startsWith("https://duckduckgo.com/?q="))
        assertTrue(result.contains("big+buck+bunny") || result.contains("big%20buck%20bunny"))
    }

    @Test
    fun `a single word with no dot is a search, not a host`() {
        // The most-felt omnibox bug: "recipes" must not become https://recipes.
        assertTrue(Omnibox.resolve("recipes", engine)!!.startsWith("https://duckduckgo.com/"))
    }

    @Test
    fun `an explicit non-web scheme is refused, not searched for`() {
        // Typing intent:// by hand should not silently become a web search for it,
        // and must never be handed to the WebView either.
        assertNull(Omnibox.resolve("intent://scan/#Intent;scheme=zxing;end", engine))
        assertNull(Omnibox.resolve("file:///etc/passwd", engine))
    }

    @Test
    fun `blank input does nothing`() {
        assertNull(Omnibox.resolve("", engine))
        assertNull(Omnibox.resolve("   ", engine))
    }

    @Test
    fun `the chosen engine is the one used`() {
        assertTrue(Omnibox.resolve("cats", SearchEngine.GOOGLE)!!.contains("google.com"))
        assertTrue(Omnibox.resolve("cats", SearchEngine.STARTPAGE)!!.contains("startpage.com"))
    }
}
