package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kind decides what a tap on the row does — open a browser, download a file, or
 * paste. Getting it wrong means a link you can't open, or a sentence handed to the
 * browser as a URL. Kept in step with `ShareKind` on the server (Share/ShareHub.cs),
 * so the same string doesn't classify differently at each end.
 */
class ShareKindTest {

    @Test
    fun `a bare url is a link`() {
        assertEquals(ShareKind.LINK, ShareKind.forText("https://example.com/a?b=c"))
        assertEquals(ShareKind.LINK, ShareKind.forText("HTTP://example.com"))
        assertEquals(ShareKind.LINK, ShareKind.forText("  https://example.com \n"))
    }

    @Test
    fun `a sentence that merely contains a url is not`() {
        // Handing this to ACTION_VIEW would fail, and the useful action is paste.
        assertEquals(ShareKind.TEXT, ShareKind.forText("look at https://example.com"))
        assertEquals(ShareKind.TEXT, ShareKind.forText("example.com"))
        assertEquals(ShareKind.TEXT, ShareKind.forText("ftp://example.com/x"))
    }

    @Test
    fun `image extensions are told apart from other files`() {
        assertEquals(ShareKind.IMAGE, ShareKind.forFile("Screenshot_2026.PNG"))
        assertEquals(ShareKind.IMAGE, ShareKind.forFile("holiday.jpeg"))
        assertEquals(ShareKind.FILE, ShareKind.forFile("invoice.pdf"))
        assertEquals(ShareKind.FILE, ShareKind.forFile("no-extension"))
    }

    @Test
    fun `parses a share push and ignores everything else`() {
        val push = JSONObject(
            """{"t":"share","kind":"link","text":"https://example.com","from":"DESKTOP-1"}"""
        )
        val entry = ShareEntry.fromPush(push, id = 7)!!

        assertEquals(7, entry.id)
        assertTrue(entry.incoming)
        assertEquals(ShareKind.LINK, entry.kind)
        assertEquals("https://example.com", entry.text)
        assertEquals("DESKTOP-1", entry.from)
        assertNull(entry.fileName)

        assertNull(ShareEntry.fromPush(JSONObject("""{"t":"pong","seq":1}"""), id = 8))
    }

    @Test
    fun `a file push carries the path the phone downloads from`() {
        val push = JSONObject(
            """{"t":"share","kind":"image","file":"clip.png","path":"Inbox/clip.png","from":"PC"}"""
        )
        val entry = ShareEntry.fromPush(push, id = 1)!!

        assertEquals("Inbox/clip.png", entry.path)
        assertEquals("clip.png", entry.fileName)
        assertNull(entry.text)
    }
}
