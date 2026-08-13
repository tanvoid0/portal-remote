package com.portalremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** UDP, and fixed: it's the one thing the phone can know before it knows anything
 *  else about a PC — including which HTTP port that PC chose. Matches
 *  `DiscoveryResponder.Port` on the server. */
private const val DISCOVERY_PORT = 8765
private const val PROBE = "PORTALREMOTE?"
private const val REPROBE_MS = 800L
private const val RECEIVE_TIMEOUT_MS = 250

/** A Portal Remote server that answered a discovery probe. Carries no token — that
 *  still has to be approved on the PC (see [requestPairing]). */
data class DiscoveredHost(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
    /** Stable per-install id, so a PC whose IP has changed is still recognisably
     *  the same PC. Null from servers built before this field existed. */
    val id: String? = null,
)

/**
 * Portal Remote servers answering on the local network, emitted as a growing list.
 *
 * Runs until the collector is cancelled, re-probing every [REPROBE_MS]: UDP
 * broadcast is lossy, and the PC may well be switched on *after* the phone opened
 * this screen. No multicast lock is needed — the probe is broadcast, but each
 * server replies straight back to this socket.
 *
 * Finds nothing on an emulator (it sits behind NAT and never sees the LAN); manual
 * entry is the way in there.
 */
fun discoverHosts(): Flow<List<DiscoveredHost>> = flow {
    val found = LinkedHashMap<String, DiscoveredHost>()
    val probe = PROBE.toByteArray()
    val buffer = ByteArray(1024)

    emit(emptyList())
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = RECEIVE_TIMEOUT_MS
        var lastProbe = 0L

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now - lastProbe >= REPROBE_MS) {
                lastProbe = now
                broadcastTargets().forEach { target ->
                    // A downed interface or a blocked route is normal here; the
                    // other targets still have a shot.
                    runCatching { socket.send(DatagramPacket(probe, probe.size, target, DISCOVERY_PORT)) }
                }
            }

            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue // back round to re-probe / re-check cancellation
            }

            val host = parseReply(packet) ?: continue
            if (found.put(host.host, host) != host) emit(found.values.toList())
        }
    }
}.flowOn(Dispatchers.IO)

private fun parseReply(packet: DatagramPacket): DiscoveredHost? {
    val text = String(packet.data, packet.offset, packet.length)
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val address = packet.address?.hostAddress ?: return null
    val port = json.optInt("port").takeIf { it in 1..65535 } ?: return null
    return DiscoveredHost(
        name = json.optString("name").ifBlank { address },
        host = address,
        port = port,
        version = json.optString("version"),
        id = json.optStringOrNull("id"),
    )
}

/** 255.255.255.255 is dropped outright by some Wi-Fi drivers and access points, so
 *  each live interface's own subnet broadcast address gets a probe too. */
private fun broadcastTargets(): List<InetAddress> = buildList {
    runCatching { add(InetAddress.getByName("255.255.255.255")) }
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { it.broadcast }
            .forEach { add(it) }
    }
}
