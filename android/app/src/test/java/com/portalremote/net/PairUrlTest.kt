package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The manual-entry field's Connect button is enabled by exactly this function,
 *  so a wrong answer here is either a dead button or a dialled nonsense address. */
class IpFromOctetsTest {

    @Test
    fun `joins four octets`() {
        assertEquals("192.168.0.21", ipFromOctets(listOf("192", "168", "0", "21")))
    }

    @Test
    fun `rejects an unfinished address`() {
        assertNull(ipFromOctets(listOf("192", "168", "0", "")))
        assertNull(ipFromOctets(listOf("192", "168", "0")))
    }

    @Test
    fun `rejects out-of-range octets`() {
        assertNull(ipFromOctets(listOf("192", "168", "0", "256")))
        // 0.x.x.x is "this network" — never dialable, and the likely shape of a
        // field the user has only started filling in.
        assertNull(ipFromOctets(listOf("0", "0", "0", "0")))
    }

    @Test
    fun `parses a pairing url`() {
        val host = parsePairUrl("portalremote://192.168.0.21:8765/abc123")
        assertEquals("192.168.0.21", host.host)
        assertEquals(8765, host.port)
        assertEquals("abc123", host.token)
    }
}
