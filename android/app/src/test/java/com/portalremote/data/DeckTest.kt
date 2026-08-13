package com.portalremote.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [encodeDeckItems]/[decodeDeckItems] are the only hand-written parsing in the Deck
 * feature — everything else is Compose state. Two things are worth pinning: a roundtrip
 * loses nothing (including an empty [DeckItem.keys], which `JSONArray` is happy to make
 * indistinguishable from "absent" if the encode side ever gets sloppy about it), and a
 * corrupted or pre-Deck pref decodes to an empty list rather than crashing the app on
 * launch.
 */
class DeckTest {

    @Test
    fun `a deck roundtrips through JSON unchanged`() {
        val items = listOf(
            DeckItem("a", "Explorer", "FOLDER", "RUN", payload = "explorer"),
            DeckItem("b", "Task Manager", "TASKS", "SHORTCUT", keys = listOf("ctrl", "shift", "esc")),
            DeckItem("c", "Web Search", "PUBLIC", "WEB_SEARCH"),
        )

        val decoded = decodeDeckItems(encodeDeckItems(items))

        assertEquals(items, decoded)
    }

    @Test
    fun `garbage decodes to an empty deck rather than throwing`() {
        assertEquals(emptyList(), decodeDeckItems("not json"))
        assertEquals(emptyList(), decodeDeckItems(""))
        assertEquals(emptyList(), decodeDeckItems("[{\"id\":\"missing-fields\"}]"))
    }

    @Test
    fun `defaults are non-empty and every id is unique`() {
        val defaults = defaultDeckItems()
        assertTrue(defaults.isNotEmpty())
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
    }
}
