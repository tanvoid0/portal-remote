package com.portalremote.net

import java.net.URI

/**
 * Turns whatever got pasted into a URL the PC will accept, or null if it can't be
 * one. The server validates again — this exists so a typo fails on the phone, where
 * the user is looking, instead of silently doing nothing on a screen across the room.
 */
object CastUrl {
    private val allowedSchemes = setOf("http", "https")

    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        // "example.com/clip.mp4" is what people actually paste; assume https rather
        // than rejecting it.
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in allowedSchemes) return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toASCIIString()
    }
}
