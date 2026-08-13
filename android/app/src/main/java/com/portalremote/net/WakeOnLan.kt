package com.portalremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake a PC that is asleep or off — `docs/phase4-casting.md` §8.
 *
 * Best-effort by nature: it only works if the user enabled Wake-on-LAN in their BIOS
 * *and* on the adapter, and nothing comes back to say whether it did. So this reports
 * only whether the packet left the phone, and the UI has to say the rest.
 */
object WakeOnLan {

    /** Port 9 (discard) by convention; 7 also works and neither is listened on. */
    private const val PORT = 9

    /**
     * `FF × 6` then the MAC sixteen times — the whole of the format. Null if [mac]
     * isn't six bytes of hex, separated by anything or nothing.
     */
    fun magicPacket(mac: String): ByteArray? {
        val bytes = mac.split(':', '-', ' ').filter { it.isNotEmpty() }
            .let { if (it.size == 1) it.first().chunked(2) else it }
            .map { it.toIntOrNull(16) ?: return null }
        if (bytes.size != 6 || bytes.any { it !in 0..255 }) return null

        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            for (i in 0 until 6) packet[6 + repeat * 6 + i] = bytes[i].toByte()
        }
        return packet
    }

    /**
     * Broadcast a magic packet for [mac]. Sent to the subnet's broadcast address as
     * well as `255.255.255.255`, because a sleeping PC has no address to aim at and
     * some routers drop the all-ones form.
     *
     * Returns false only if nothing could be sent at all.
     */
    suspend fun wake(mac: String, peer: String? = null): Boolean = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac) ?: return@withContext false
        val targets = buildList {
            peer?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { add("$it.255") }
            add("255.255.255.255")
        }

        var sent = false
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                targets.forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), PORT))
                        sent = true
                    }
                }
            }
        }
        sent
    }
}
