package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaSnifferTest {
    @Test
    fun `classifies the formats worth casting`() {
        assertEquals("HLS", MediaSniffer.classify("https://cdn.example.com/master.m3u8"))
        assertEquals("DASH", MediaSniffer.classify("https://cdn.example.com/manifest.mpd"))
        assertEquals("MP4", MediaSniffer.classify("https://cdn.example.com/clip.MP4"))
        assertEquals("WebM", MediaSniffer.classify("https://cdn.example.com/clip.webm"))
    }

    @Test
    fun `looks past the query string, which is where media tokens live`() {
        assertEquals("HLS", MediaSniffer.classify("https://cdn.example.com/v.m3u8?token=abc&exp=1"))
        assertEquals("MP4", MediaSniffer.classify("https://cdn.example.com/v.mp4#t=10"))
    }

    @Test
    fun `ignores hls segments`() {
        // A .ts is two seconds of the film; the .m3u8 beside it is the film. Casting
        // the segment is worse than finding nothing, because it looks like it worked.
        assertNull(MediaSniffer.classify("https://cdn.example.com/seg-00042.ts"))
    }

    @Test
    fun `ignores everything that is not media`() {
        assertNull(MediaSniffer.classify("https://example.com/index.html"))
        assertNull(MediaSniffer.classify("https://example.com/app.js"))
        assertNull(MediaSniffer.classify("https://example.com/poster.jpg"))
    }

    @Test
    fun `knows what a receiver can never fetch`() {
        assertTrue(MediaSniffer.isUnfetchable("blob:https://youtube.com/abc-123"))
        assertTrue(MediaSniffer.isUnfetchable("data:video/mp4;base64,AAAA"))
        assertFalse(MediaSniffer.isUnfetchable("https://cdn.example.com/clip.mp4"))
    }

    @Test
    fun `labels fall back to the file name`() {
        val withTitle = FoundMedia("https://c.example/a/b.mp4?x=1", "MP4", "Big Buck Bunny")
        val without = FoundMedia("https://c.example/a/b.mp4?x=1", "MP4", null)
        assertEquals("Big Buck Bunny", withTitle.label)
        assertEquals("b.mp4", without.label)
        assertEquals("b.mp4", without.fileName)
    }
}
