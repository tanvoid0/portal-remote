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

/** Builds a [SavedHost] from a manually typed `host:port` and token. */
fun hostFromManualEntry(hostPort: String, token: String): SavedHost {
    val trimmed = hostPort.trim()
    val colon = trimmed.lastIndexOf(':')
    val host = if (colon >= 0) trimmed.substring(0, colon) else trimmed
    val port = if (colon >= 0) trimmed.substring(colon + 1).toIntOrNull() else null
    if (host.isEmpty()) throw InvalidPairUrl("missing host")
    return SavedHost(host = host, port = port ?: 8765, token = token.trim())
}
