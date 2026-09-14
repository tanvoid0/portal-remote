package com.portalremote.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The modifier keys have to behave like the ones on a PC keyboard — down while held,
 * up on release — with one addition, a quick tap latching the key for the next
 * keystroke. Each case below is one sequence of thumb events and the exact key
 * events the PC must see for it.
 */
class ModifierKeysTest {
    private val sent = mutableListOf<String>()
    private val mods = ModifierKeys { key, down -> sent += if (down) "$key↓" else "$key↑" }

    @Test
    fun `a quick tap latches until the next key`() {
        mods.press("ctrl"); mods.lift("ctrl")
        assertEquals(listOf("ctrl↓"), sent)
        mods.keyed()
        assertEquals(listOf("ctrl↓", "ctrl↑"), sent)
        assertEquals(emptyMap(), mods.down.toMap())
    }

    @Test
    fun `held through a key, released on lift`() {
        mods.press("ctrl"); mods.keyed(); mods.lift("ctrl")
        assertEquals(listOf("ctrl↓", "ctrl↑"), sent)
    }

    @Test
    fun `a second tap on a latched key lets it go`() {
        mods.press("ctrl"); mods.lift("ctrl")
        mods.press("ctrl"); mods.lift("ctrl")
        assertEquals(listOf("ctrl↓", "ctrl↑"), sent)
        assertEquals(emptyMap(), mods.down.toMap())
    }

    @Test
    fun `two latched keys wait for the same keystroke`() {
        mods.press("ctrl"); mods.lift("ctrl")
        mods.press("shift"); mods.lift("shift")
        assertEquals(listOf("ctrl↓", "shift↓"), sent)
        mods.keyed()
        assertEquals(setOf("ctrl↑", "shift↑"), sent.drop(2).toSet())
    }

    @Test
    fun `a cancelled gesture lets a held key go and never latches it`() {
        mods.press("ctrl"); mods.cancel("ctrl")
        assertEquals(listOf("ctrl↓", "ctrl↑"), sent)
        assertEquals(emptyMap(), mods.down.toMap())
    }

    @Test
    fun `a second modifier does not forget the first one fired`() {
        mods.press("ctrl"); mods.keyed(); mods.press("shift"); mods.lift("ctrl")
        assertEquals(listOf("ctrl↓", "shift↓", "ctrl↑"), sent)
    }

    @Test
    fun `leaving releases whatever is still down`() {
        mods.press("alt"); mods.lift("alt")
        mods.press("shift")
        mods.releaseAll()
        assertEquals(listOf("alt↓", "shift↓"), sent.take(2))
        assertEquals(setOf("alt↑", "shift↑"), sent.drop(2).toSet())
        assertEquals(emptyMap(), mods.down.toMap())
    }
}
