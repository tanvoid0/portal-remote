package com.portalremote.net

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Phase 4d of `docs/phase4-casting.md`: the phone serves its own files over HTTP so the
 * PC can play them. This is the inversion the rest of that phase needs — until now the
 * phone was a pure client, and casting a video that lives *on the phone* means the
 * receiver has to pull the bytes from somewhere.
 *
 * Deliberately small: `GET`/`HEAD`, one route, byte ranges, and nothing else. A player
 * seeking through a film is the entire workload.
 *
 * **Only ids minted by [offer] are servable.** No path from the wire is ever resolved
 * against the filesystem, which is the whole of the traversal defence — the attacker
 * here is on the PC side of a connection this phone opened.
 */
class MediaServer(private val token: String) : Closeable {

    /**
     * A file this phone has agreed to serve. [open] is given a byte offset because a
     * range request is the normal case, not the exception: a player seeks.
     */
    data class Item(
        val name: String,
        val mime: String,
        val size: Long,
        val open: (Long) -> InputStream,
    )

    private val items = ConcurrentHashMap<String, Item>()
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null

    /** The port the OS gave us, or -1 before [start]. Ephemeral: the URL carries it,
     *  so a fixed port would only be one more thing that can already be taken. */
    val port: Int get() = socket?.localPort ?: -1

    /**
     * Listen on [address] only. Not `0.0.0.0`: the PC is reached over one interface,
     * and a phone that later joins a different network should stop answering on the
     * old one rather than keep a listener open on whatever else it is attached to.
     */
    fun start(address: String) {
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName(address))
        socket = server
        thread(isDaemon = true, name = "media-server") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread // closed, or the interface went away
                }
                pool.execute { serve(client) }
            }
        }
    }

    /** Agree to serve [item], and return the id that reaches it. */
    fun offer(item: Item): String {
        val id = newId()
        items[id] = item
        return id
    }

    /** The full URL to hand the PC, or null before [start]. */
    fun urlFor(id: String, address: String): String? =
        if (port < 0) null else "http://$address:$port/f/$id?token=$token"

    override fun close() {
        items.clear()
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                // A player that has finished with us just stops reading; nothing here
                // should wait on it forever.
                client.soTimeout = READ_TIMEOUT_MS
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val request = readLine(input)?.split(' ') ?: return
                if (request.size < 3) return respond(output, "400 Bad Request")
                val method = request[0]
                val target = request[1]

                var range: String? = null
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }

                if (method != "GET" && method != "HEAD") return respond(output, "405 Method Not Allowed")

                val path = target.substringBefore('?')
                if (!authorized(target)) return respond(output, "401 Unauthorized")

                val id = path.removePrefix("/f/")
                val item = if (path.startsWith("/f/")) items[id] else null
                if (item == null) return respond(output, "404 Not Found")

                send(output, item, range, body = method == "GET")
            } catch (_: IOException) {
                // The other end hung up mid-response. Routine while seeking: a player
                // that jumps forward abandons the connection it was reading.
            }
        }
    }

    private fun authorized(target: String): Boolean {
        val presented = target.substringAfter("?", "")
            .split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private fun send(output: OutputStream, item: Item, rangeHeader: String?, body: Boolean) {
        val range = parseRange(rangeHeader, item.size)
        if (rangeHeader != null && range == null) {
            return respond(output, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */${item.size}"))
        }

        val start = range?.first ?: 0
        val end = range?.last ?: (item.size - 1)
        val length = end - start + 1

        val headers = mutableMapOf(
            "Content-Type" to item.mime,
            "Content-Length" to length.toString(),
            "Accept-Ranges" to "bytes",
        )
        if (range != null) headers["Content-Range"] = "bytes $start-$end/${item.size}"

        respond(output, if (range != null) "206 Partial Content" else "200 OK", headers)
        if (!body) return

        item.open(start).use { stream ->
            val buffer = ByteArray(COPY_BUFFER)
            var remaining = length
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
    }

    private fun respond(output: OutputStream, status: String, headers: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            // ponytail: one request per connection. A seek-heavy player pays a TCP
            // handshake per range; keep-alive is the fix if that ever shows up as
            // stutter on the LAN, which it has not.
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            // A request line that never ends is not a request.
            if (line.length >= MAX_LINE) return null
            line.append(c.toChar())
        }
    }

    /** Unguessable, because it is the only thing between a file and anyone who can
     *  reach this port with the token. */
    private fun newId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BACKLOG = 8
        private const val READ_TIMEOUT_MS = 15_000
        private const val COPY_BUFFER = 64 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val ID_BYTES = 12

        private val random = SecureRandom()

        /**
         * `bytes=start-end`, `bytes=start-` or `bytes=-suffix`, clamped to [size].
         * Null means "serve the whole thing" for an absent header and "unsatisfiable"
         * for a present one — the caller knows which it had.
         *
         * Only the first range of a multi-range request is honoured; players ask for
         * one at a time, and a `multipart/byteranges` body for the case nobody uses is
         * a lot of code to get wrong.
         */
        fun parseRange(header: String?, size: Long): LongRange? {
            val spec = header?.trim()?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
            if (size <= 0) return null

            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()

            val start: Long
            val end: Long
            if (from.isEmpty()) {
                // A suffix range: the last N bytes. ffmpeg uses this to read a trailing
                // moov atom, so an MP4 written for streaming last depends on it.
                val suffix = to.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = maxOf(0, size - suffix)
                end = size - 1
            } else {
                start = from.toLongOrNull() ?: return null
                end = if (to.isEmpty()) size - 1 else (to.toLongOrNull() ?: return null)
            }

            if (start < 0 || start >= size || end < start) return null
            return start..minOf(end, size - 1)
        }
    }
}

/**
 * Which of this phone's addresses the PC at [peer] can actually dial back on.
 *
 * Prefer one on the same /24 as the PC. A phone routinely has several — Wi-Fi, a VPN's
 * tun, mobile data — and handing the PC the wrong one produces a cast that fails with
 * no explanation on either side.
 */
fun pickLocalAddress(candidates: List<String>, peer: String): String? {
    val subnet = peer.substringBeforeLast('.', "")
    return candidates.firstOrNull { subnet.isNotEmpty() && it.substringBeforeLast('.', "") == subnet }
        ?: candidates.firstOrNull()
}

/** This phone's IPv4 addresses, loopback and down interfaces excluded. */
fun localIpv4Addresses(): List<String> =
    runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toList()
    }.getOrDefault(emptyList())
