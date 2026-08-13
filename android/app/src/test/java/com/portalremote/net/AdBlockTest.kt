package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdBlockTest {
    @Test
    fun `takes plain domain rules`() {
        assertEquals("ads.example.com", AdBlock.hostRule("||ads.example.com^"))
        assertEquals("ads.example.com", AdBlock.hostRule("||ads.example.com"))
        assertEquals("ads.example.com", AdBlock.hostRule("  ||ADS.example.com^  "))
    }

    @Test
    fun `skips rules it would apply wrongly`() {
        // Third-party-only: applying this unconditionally blocks the site's own
        // requests and breaks the page — see the class doc.
        assertNull(AdBlock.hostRule("||example.com^\$third-party"))
        assertNull(AdBlock.hostRule("||example.com/ads/*"))
        assertNull(AdBlock.hostRule("||example.com^/path"))
        assertNull(AdBlock.hostRule("@@||example.com^"))
        assertNull(AdBlock.hostRule("example.com##.ad-banner"))
        assertNull(AdBlock.hostRule("! a comment"))
        assertNull(AdBlock.hostRule("[Adblock Plus 2.0]"))
        assertNull(AdBlock.hostRule("/banner/*/img"))
    }

    @Test
    fun `matches the domain and anything under it`() {
        val rules = setOf("doubleclick.net")
        assertTrue(AdBlock.isBlockedHost("doubleclick.net", rules))
        assertTrue(AdBlock.isBlockedHost("stats.g.doubleclick.net", rules))
        assertTrue(AdBlock.isBlockedHost("DOUBLECLICK.NET", rules))
    }

    @Test
    fun `does not match a neighbour or a parent`() {
        val rules = setOf("ads.example.com")
        assertFalse(AdBlock.isBlockedHost("example.com", rules))
        assertFalse(AdBlock.isBlockedHost("notads.example.com", rules))
        assertFalse(AdBlock.isBlockedHost("ads.example.com.evil.net", rules))
    }

    @Test
    fun `never walks up to a bare tld`() {
        // Guards the suffix walk: a malformed list containing "com" must not take the
        // whole web down with it.
        assertFalse(AdBlock.isBlockedHost("example.com", setOf("com")))
        assertFalse(AdBlock.isBlockedHost("a.b.example.com", setOf("com")))
    }

    @Test
    fun `blocks by url and counts`() {
        val blocker = AdBlock()
        assertTrue(blocker.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/x.js"))
        assertFalse(blocker.shouldBlock("https://example.com/index.html"))
        assertEquals(1, blocker.blockedCount)
        blocker.resetCount()
        assertEquals(0, blocker.blockedCount)
    }

    @Test
    fun `ignores urls with no host`() {
        val blocker = AdBlock()
        assertFalse(blocker.shouldBlock("about:blank"))
        assertFalse(blocker.shouldBlock("not a url at all"))
    }
}
