package com.portalremote.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tile on the Deck — a search, a launched app, a key shortcut, a power or media
 * action. [icon] and [type] are [com.portalremote.ui.DeckIcon] / [com.portalremote.ui.DeckActionType]
 * names, kept as plain strings here rather than the enums themselves: the data layer
 * doesn't import Compose, the same reason [AppSettings.mirrorPreset] is a string and
 * not a [com.portalremote.ui.MirrorPreset].
 */
data class DeckItem(
    val id: String,
    val label: String,
    val icon: String,
    val type: String,
    /** RUN: the command/path Win+R opens. WEB_SEARCH/PC_SEARCH: a default query, blank
     *  to ask each tap. POWER: a power mode. MEDIA: a media action. Unused by SHORTCUT. */
    val payload: String = "",
    /** SHORTCUT only: the combo to press, in [com.portalremote.net.Protocol.combo] order. */
    val keys: List<String> = emptyList(),
)

fun encodeDeckItems(items: List<DeckItem>): String {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject().apply {
                put("id", item.id)
                put("label", item.label)
                put("icon", item.icon)
                put("type", item.type)
                put("payload", item.payload)
                put("keys", JSONArray(item.keys))
            },
        )
    }
    return array.toString()
}

/** Empty on anything that doesn't parse — a corrupted pref is a fresh Deck, not a crash. */
fun decodeDeckItems(json: String): List<DeckItem> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val keys = o.optJSONArray("keys")
        DeckItem(
            id = o.getString("id"),
            label = o.getString("label"),
            icon = o.getString("icon"),
            type = o.getString("type"),
            payload = o.optString("payload", ""),
            keys = keys?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        )
    }
}.getOrDefault(emptyList())

/**
 * What a fresh install (or a Deck cleared back to nothing) opens with — search, one
 * launched app, four shortcuts, two power modes and mute. Everything past this is the
 * user's own, added through DeckScreen's editor.
 */
fun defaultDeckItems(): List<DeckItem> = listOf(
    DeckItem("web-search", "Web Search", "PUBLIC", "WEB_SEARCH"),
    DeckItem("pc-search", "PC Search", "SEARCH", "PC_SEARCH"),
    DeckItem("explorer", "Explorer", "FOLDER", "RUN", payload = "explorer"),
    DeckItem("task-manager", "Task Manager", "TASKS", "SHORTCUT", keys = listOf("ctrl", "shift", "esc")),
    DeckItem("show-desktop", "Show Desktop", "DESKTOP", "SHORTCUT", keys = listOf("win", "d")),
    DeckItem("task-view", "Task View", "GRID", "SHORTCUT", keys = listOf("win", "tab")),
    DeckItem("screenshot", "Screenshot", "CAMERA", "SHORTCUT", keys = listOf("win", "shift", "s")),
    DeckItem("lock", "Lock", "LOCK", "POWER", payload = "lock"),
    DeckItem("sleep", "Sleep", "SLEEP", "POWER", payload = "sleep"),
    DeckItem("mute", "Mute", "MUTE", "MEDIA", payload = "mute"),
)
