package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeOnLanTest {
    private val mac = "1a:2b:3c:4d:5e:6f"
    private val bytes = listOf(0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f).map { it.toByte() }

    @Test
    fun `a magic packet is six FFs then the mac sixteen times`() {
        val packet = WakeOnLan.magicPacket(mac)!!
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        for (repeat in 0 until 16) {
            assertEquals(bytes, packet.toList().subList(6 + repeat * 6, 12 + repeat * 6), "repeat $repeat")
        }
    }

    @Test
    fun `separators are whatever the PC happened to use`() {
        val expected = WakeOnLan.magicPacket(mac)!!.toList()
        assertEquals(expected, WakeOnLan.magicPacket("1A-2B-3C-4D-5E-6F")!!.toList())
        assertEquals(expected, WakeOnLan.magicPacket("1a2b3c4d5e6f")!!.toList())
    }

    @Test
    fun `a mac that isn't one is refused rather than padded`() {
        assertNull(WakeOnLan.magicPacket(""))
        assertNull(WakeOnLan.magicPacket("1a:2b:3c"))
        assertNull(WakeOnLan.magicPacket("1a:2b:3c:4d:5e:6f:70"))
        assertNull(WakeOnLan.magicPacket("zz:2b:3c:4d:5e:6f"))
    }
}
