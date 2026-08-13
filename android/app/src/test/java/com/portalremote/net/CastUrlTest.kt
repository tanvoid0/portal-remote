package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastUrlTest {
    @Test
    fun `keeps a well-formed url`() {
        assertEquals("https://example.com/clip.mp4", CastUrl.normalize("https://example.com/clip.mp4"))
        assertEquals("http://192.168.1.9:8080/a.m3u8", CastUrl.normalize("http://192.168.1.9:8080/a.m3u8"))
    }

    @Test
    fun `assumes https for a bare host`() {
        assertEquals("https://example.com/clip.mp4", CastUrl.normalize("example.com/clip.mp4"))
    }

    @Test
    fun `trims what a paste drags along`() {
        assertEquals("https://example.com/clip.mp4", CastUrl.normalize("  https://example.com/clip.mp4\n"))
    }

    @Test
    fun `keeps the query string, which is where media tokens live`() {
        assertEquals(
            "https://cdn.example.com/v.m3u8?token=abc&exp=1",
            CastUrl.normalize("https://cdn.example.com/v.m3u8?token=abc&exp=1"),
        )
    }

    @Test
    fun `rejects anything that is not http`() {
        // The PC opens these with ShellExecute, so a non-http scheme reaching it is
        // a program launch, not a cast. Blocked on both sides; this is the near one.
        assertNull(CastUrl.normalize("file:///C:/Windows/System32/cmd.exe"))
        assertNull(CastUrl.normalize("ms-settings:privacy"))
        assertNull(CastUrl.normalize("javascript:alert(1)"))
    }

    @Test
    fun `rejects empty and hostless input`() {
        assertNull(CastUrl.normalize(""))
        assertNull(CastUrl.normalize("   "))
        assertNull(CastUrl.normalize("https://"))
    }
}
