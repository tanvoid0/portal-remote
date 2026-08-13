package com.portalremote.net

import com.portalremote.data.SavedHost

private const val SCHEME = "portalremote"

/** Thrown when a scanned/typed QR payload isn't a valid pairing URL. */
class InvalidPairUrl(message: String) : Exception(message)

/**
 * Parses `portalremote://<host>:<port>/<token>`, as rendered by the server's
 * pairing QR code.
 */
fun parsePairUrl(raw: String): SavedHost {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("$SCHEME://")) {
        throw InvalidPairUrl("not a $SCHEME:// link")
    }

    val withoutScheme = trimmed.removePrefix("$SCHEME://")
    val slash = withoutScheme.indexOf('/')
    if (slash < 0) throw InvalidPairUrl("missing pairing token")

    val authority = withoutScheme.substring(0, slash)
    val token = withoutScheme.substring(slash + 1).trim('/')
    if (token.isEmpty()) throw InvalidPairUrl("missing pairing token")

    val colon = authority.lastIndexOf(':')
    if (colon < 0) throw InvalidPairUrl("missing port")

    val host = authority.substring(0, colon)
    val port = authority.substring(colon + 1).toIntOrNull()
        ?: throw InvalidPairUrl("invalid port")

    if (host.isEmpty()) throw InvalidPairUrl("missing host")

    return SavedHost(host = host, port = port, token = token)
}

/**
 * Joins the four boxes of the manual-entry field into a dotted-quad, or null if
 * what's typed isn't an address yet — which is also what disables the Connect
 * button, so nothing has to explain the rules in prose.
 */
fun ipFromOctets(octets: List<String>): String? {
    if (octets.size != 4) return null
    val parts = octets.map { it.trim().toIntOrNull() ?: return null }
    if (parts.any { it !in 0..255 }) return null
    // 0.x.x.x is "this network" — never a host you can dial, and the likeliest
    // shape of a half-typed address.
    if (parts[0] == 0) return null
    return parts.joinToString(".")
}
