package com.portalremote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        https://cdn.example.com/1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        720/index.m3u8
    """.trimIndent()

    @Test
    fun `variants come back best first, relative URLs resolved`() {
        val out = Hls.parseMaster(master, "https://v.example.com/hls/master.m3u8")
        assertEquals(
            listOf(
                "https://cdn.example.com/1080/index.m3u8" to 1080,
                "https://v.example.com/hls/720/index.m3u8" to 720,
                "https://v.example.com/hls/360/index.m3u8" to 360,
            ),
            out,
        )
    }

    @Test
    fun `a media playlist has no variants`() {
        val media = """
            #EXTM3U
            #EXTINF:9.0,
            segment0.ts
            #EXTINF:9.0,
            segment1.ts
        """.trimIndent()
        assertTrue(Hls.parseMaster(media, "https://v.example.com/hls/index.m3u8").isEmpty())
    }

    @Test
    fun `a variant without a RESOLUTION still lists, just unranked`() {
        val audioOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000
            audio.m3u8
        """.trimIndent()
        val out = Hls.parseMaster(audioOnly, "https://v.example.com/master.m3u8")
        assertEquals(1, out.size)
        assertNull(out[0].second)
    }

    @Test
    fun `height comes off a filename when nothing better says`() {
        assertEquals(1080, MediaSniffer.heightHint("https://cdn/x/1080p/index.m3u8"))
        assertEquals(720, MediaSniffer.heightHint("https://cdn/video_1280x720.mp4"))
        // A year, an id, a bitrate — none of them are frame heights.
        assertNull(MediaSniffer.heightHint("https://cdn/2024/clip.mp4"))
        assertNull(MediaSniffer.heightHint("https://cdn/aef91b2/index.m3u8"))
    }
}
