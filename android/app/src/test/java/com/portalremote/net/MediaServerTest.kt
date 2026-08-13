package com.portalremote.net

import java.io.ByteArrayInputStream
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real server over a real socket on loopback — the parsing, the ranges and
 * the auth are the whole point of the class, and a mock of them would only test itself.
 */
class MediaServerTest {
    private val body = ByteArray(1000) { (it % 251).toByte() }
    private val server = MediaServer().apply { start("127.0.0.1") }

    /** The server's own secret, not the PC pairing token — these URLs get handed to a
     *  television by the Roku and DLNA senders, which log them. */
    private val token = server.urlSecret
    private val id = server.offer(
        MediaServer.Item("clip.mp4", "video/mp4", body.size.toLong()) { offset ->
            ByteArrayInputStream(body, offset.toInt(), body.size - offset.toInt())
        }
    )

    @AfterTest
    fun stop() = server.close()

    /** Returns the status line, the headers, and however many body bytes arrived. */
    private fun request(target: String, method: String = "GET", range: String? = null): Triple<String, Map<String, String>, ByteArray> {
        Socket("127.0.0.1", server.port).use { socket ->
            val out = socket.getOutputStream()
            val head = buildString {
                append("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            out.write(head.toByteArray())
            out.flush()

            val response = socket.getInputStream().readBytes()
            val split = String(response, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val lines = String(response, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val headers = lines.drop(1).associate {
                it.substringBefore(':').trim() to it.substringAfter(':').trim()
            }
            return Triple(lines.first(), headers, response.copyOfRange(split + 4, response.size))
        }
    }

    @Test
    fun `serves a whole file`() {
        val (status, headers, bytes) = request("/f/$id?token=$token")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("video/mp4", headers["Content-Type"])
        assertEquals("1000", headers["Content-Length"])
        assertEquals("bytes", headers["Accept-Ranges"])
        assertTrue(bytes.contentEquals(body))
    }

    @Test
    fun `serves a byte range`() {
        val (status, headers, bytes) = request("/f/$id?token=$token", range = "bytes=100-199")
        assertEquals("HTTP/1.1 206 Partial Content", status)
        assertEquals("bytes 100-199/1000", headers["Content-Range"])
        assertEquals("100", headers["Content-Length"])
        assertTrue(bytes.contentEquals(body.copyOfRange(100, 200)))
    }

    @Test
    fun `an open-ended range runs to the end`() {
        val (status, headers, bytes) = request("/f/$id?token=$token", range = "bytes=900-")
        assertEquals("HTTP/1.1 206 Partial Content", status)
        assertEquals("bytes 900-999/1000", headers["Content-Range"])
        assertEquals(100, bytes.size)
    }

    @Test
    fun `HEAD answers the headers and no body`() {
        // ffmpeg asks before it reads, to find out whether seeking is possible at all.
        val (status, headers, bytes) = request("/f/$id?token=$token", method = "HEAD")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("1000", headers["Content-Length"])
        assertEquals(0, bytes.size)
    }

    @Test
    fun `no token is a 401`() {
        assertEquals("HTTP/1.1 401 Unauthorized", request("/f/$id").first)
        assertEquals("HTTP/1.1 401 Unauthorized", request("/f/$id?token=wrong").first)
    }

    @Test
    fun `an id nobody offered is a 404`() {
        // The defence that matters: nothing resolves a path from the wire, so an id
        // that was never minted reaches no file at all.
        assertEquals("HTTP/1.1 404 Not Found", request("/f/deadbeef?token=$token").first)
        assertEquals("HTTP/1.1 404 Not Found", request("/f/../../etc/passwd?token=$token").first)
        assertEquals("HTTP/1.1 404 Not Found", request("/etc/passwd?token=$token").first)
    }

    @Test
    fun `a range past the end is a 416`() {
        val (status, headers, _) = request("/f/$id?token=$token", range = "bytes=5000-6000")
        assertEquals("HTTP/1.1 416 Range Not Satisfiable", status)
        assertEquals("bytes */1000", headers["Content-Range"])
    }

    @Test
    fun `a suffix range reads from the end`() {
        // How an MP4's trailing moov atom gets fetched before anything else.
        assertEquals(900L..999L, MediaServer.parseRange("bytes=-100", 1000))
    }

    @Test
    fun `range parsing`() {
        assertEquals(0L..99L, MediaServer.parseRange("bytes=0-99", 1000))
        assertEquals(500L..999L, MediaServer.parseRange("bytes=500-", 1000))
        // Clamped rather than refused: a player asking for more than there is still
        // wants what there is.
        assertEquals(0L..999L, MediaServer.parseRange("bytes=0-4000", 1000))
        // Only the first range of a multi-range request.
        assertEquals(0L..9L, MediaServer.parseRange("bytes=0-9,20-29", 1000))
        assertNull(MediaServer.parseRange(null, 1000))
        assertNull(MediaServer.parseRange("bytes=1000-", 1000))
        assertNull(MediaServer.parseRange("bytes=abc-def", 1000))
        assertNull(MediaServer.parseRange("bytes=50-10", 1000))
    }

    @Test
    fun `the address handed to the PC is the one on its own subnet`() {
        val candidates = listOf("10.8.0.2", "192.168.1.42", "172.20.10.3")
        assertEquals("192.168.1.42", pickLocalAddress(candidates, "192.168.1.10"))
        // Nothing shares the PC's subnet — better to try the first than to give up.
        assertEquals("10.8.0.2", pickLocalAddress(candidates, "203.0.113.9"))
        assertNull(pickLocalAddress(emptyList(), "192.168.1.10"))
    }
}
