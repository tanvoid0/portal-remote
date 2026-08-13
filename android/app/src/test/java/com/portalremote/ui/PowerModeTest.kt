package com.portalremote.ui

import com.portalremote.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The power button is the one control in the app that can close every unsaved
 * document on the PC, and both halves of it fail silently: a renamed wire value is
 * rejected at the far end of a socket nobody is watching, and a mis-set
 * [PowerMode.destructive] skips the confirmation without anything looking wrong.
 */
class PowerModeTest {
    /** Exactly the modes `server/PortalRemote.Server/Input/Power.cs` accepts. */
    @Test
    fun `wire values match the server`() {
        assertEquals(
            listOf("screen_off", "lock", "sleep", "restart", "shutdown"),
            PowerMode.entries.map { it.wire },
        )
    }

    @Test
    fun `only the modes that lose work are confirmed`() {
        assertFalse(PowerMode.SCREEN_OFF.destructive)
        assertFalse(PowerMode.LOCK.destructive)
        assertFalse(PowerMode.SLEEP.destructive)
        assertTrue(PowerMode.RESTART.destructive)
        assertTrue(PowerMode.SHUTDOWN.destructive)
    }

    @Test
    fun `power message carries the mode`() {
        val message = Protocol.power(PowerMode.SHUTDOWN.wire)
        assertEquals("power", message.getString("t"))
        assertEquals("shutdown", message.getString("mode"))
    }

    @Test
    fun `power timer set message carries the mode and delay`() {
        val message = Protocol.powerTimerSet(PowerMode.LOCK.wire, 300)
        assertEquals("power_timer_set", message.getString("t"))
        assertEquals("lock", message.getString("mode"))
        assertEquals(300, message.getInt("seconds"))
    }

    @Test
    fun `power timer cancel message carries nothing else`() {
        val message = Protocol.powerTimerCancel()
        assertEquals("power_timer_cancel", message.getString("t"))
    }
}
