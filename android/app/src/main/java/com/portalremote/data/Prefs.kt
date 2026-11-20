package com.portalremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "portal_remote_prefs")

/**
 * What the keyboard's one-tap phrase row starts with: the words a chat or a terminal
 * prompt actually wants back, which are short, repeated, and always followed by Enter.
 * Everything past this is the user's own, edited from the row itself.
 */
fun defaultQuickPhrases(): List<String> = listOf("yes", "no", "continue", "1", "2", "3")

/** A paired desktop server, as scanned from its QR code or entered manually. */
data class SavedHost(
    val host: String,
    val port: Int,
    val token: String,
    /** The PC's own name, once it has told us — filled in from the server hello (or
     *  the pair-request reply) so the "last device" card can say "STUDIO-PC"
     *  instead of an IP address nobody recognises. */
    val name: String? = null,
    /** The PC's stable install id, learned from the hello. [host] is an IP address
     *  and DHCP reassigns those, so this is the only part of a pairing that still
     *  identifies the same machine tomorrow — it's what lets the app follow a PC to
     *  a new address instead of asking the user to pair again. */
    val id: String? = null,
    /** The PC's LAN adapter address, learned from the hello. The only thing that can
     *  reach a machine that is switched off — see [com.portalremote.net.WakeOnLan]. */
    val mac: String? = null,
) {
    /** What to call this PC in the UI before it has ever said hello. */
    val label: String get() = name ?: host

    val httpBase: String get() = "http://$host:$port"

    // No token in the query string: it would otherwise land in plaintext in the
    // server's request logs on every connect. WsClient sends it as an
    // Authorization header on the handshake instead — the server accepts either.
    val wsUrl: String get() = "ws://$host:$port/control"
}

/**
 * User-tunable client settings. Defaults reproduce exactly what was hard-coded
 * before the settings screen existed, so an upgrade changes nothing until the
 * user touches a control.
 */
data class AppSettings(
    /** Multiplier on the trackpad's base pointer sensitivity. */
    val pointerSpeed: Float = 1f,
    /** Drag down -> content follows the finger, as on a phone. Off = classic wheel. */
    val naturalScroll: Boolean = true,
    /** Damp the pointer when the finger is moving slowly, so a careful nudge lands on
     *  a pixel instead of overshooting it. Off = the linear 1:1 scaling that shipped
     *  before. See `precisionGain`. */
    val precisionPointer: Boolean = true,
    /** [com.portalremote.ui.MomentumLevel] name — how far a flick keeps scrolling after
     *  the finger leaves. `OFF` reproduces the dead-stop scroll that shipped before. */
    val momentum: String = "STANDARD",
    /** Hold the screen awake while a PC is connected — a session is mostly gestures
     *  on one surface, which the system idle timer doesn't always count as activity. */
    val keepScreenOn: Boolean = false,
    /** [com.portalremote.ui.MirrorPreset] name the mirror opens with. */
    val mirrorPreset: String = "SMOOTH",
    /** Buzz on clicks, keys, scroll notches and mode changes. On by default: the
     *  phone is being used as a control surface with nothing under the glass, and the
     *  tick is the only confirmation that a tap became a click on the PC. Still
     *  subject to the system's own touch-feedback setting. */
    val haptics: Boolean = true,
    /** [com.portalremote.ui.theme.HudAccent] name — which pair of accent hues the whole
     *  app is drawn in. Only the accents are the user's to pick: the neutrals, and what
     *  amber and red mean, are legibility decisions the app has already made. */
    val accent: String = "ICE",
    /** The two hue angles the `CUSTOM` accent is built from, within the arc the picker
     *  offers (yellow round to pink; red and amber are status). Kept even while a preset
     *  is on, so switching back doesn't lose the pair someone dialled in. */
    val accentHue: Float = 175f,
    val accentSecondHue: Float = 330f,
)

/** Persists the last-paired server so the app can reconnect without rescanning. */
class Prefs(private val context: Context) {
    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
        val NAME = stringPreferencesKey("name")
        val SERVER_ID = stringPreferencesKey("server_id")
        val MAC = stringPreferencesKey("mac")

        val POINTER_SPEED = floatPreferencesKey("pointer_speed")
        val NATURAL_SCROLL = booleanPreferencesKey("natural_scroll")
        val PRECISION_POINTER = booleanPreferencesKey("precision_pointer")
        val MOMENTUM = stringPreferencesKey("momentum")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val MIRROR_PRESET = stringPreferencesKey("mirror_preset")
        val HAPTICS = booleanPreferencesKey("haptics")
        val ACCENT = stringPreferencesKey("accent")
        val ACCENT_HUE = floatPreferencesKey("accent_hue")
        val ACCENT_SECOND_HUE = floatPreferencesKey("accent_second_hue")
        val DECK_ITEMS = stringPreferencesKey("deck_items")
        val QUICK_PHRASES = stringPreferencesKey("quick_phrases")
        val PAD_LEGEND_SEEN = booleanPreferencesKey("pad_legend_seen")
    }

    val savedHost: Flow<SavedHost?> = context.dataStore.data.map { prefs ->
        val host = prefs[Keys.HOST]
        val port = prefs[Keys.PORT]?.toIntOrNull()
        val token = prefs[Keys.TOKEN]
        if (host != null && port != null && token != null) {
            SavedHost(host, port, token, prefs[Keys.NAME], prefs[Keys.SERVER_ID], prefs[Keys.MAC])
        } else {
            null
        }
    }

    suspend fun currentSavedHost(): SavedHost? = savedHost.first()

    suspend fun save(host: SavedHost) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = host.host
            prefs[Keys.PORT] = host.port.toString()
            prefs[Keys.TOKEN] = host.token
            if (host.name != null) prefs[Keys.NAME] = host.name else prefs.remove(Keys.NAME)
            if (host.id != null) prefs[Keys.SERVER_ID] = host.id else prefs.remove(Keys.SERVER_ID)
            if (host.mac != null) prefs[Keys.MAC] = host.mac else prefs.remove(Keys.MAC)
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            pointerSpeed = prefs[Keys.POINTER_SPEED] ?: defaults.pointerSpeed,
            naturalScroll = prefs[Keys.NATURAL_SCROLL] ?: defaults.naturalScroll,
            precisionPointer = prefs[Keys.PRECISION_POINTER] ?: defaults.precisionPointer,
            momentum = prefs[Keys.MOMENTUM] ?: defaults.momentum,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            mirrorPreset = prefs[Keys.MIRROR_PRESET] ?: defaults.mirrorPreset,
            haptics = prefs[Keys.HAPTICS] ?: defaults.haptics,
            accent = prefs[Keys.ACCENT] ?: defaults.accent,
            accentHue = prefs[Keys.ACCENT_HUE] ?: defaults.accentHue,
            accentSecondHue = prefs[Keys.ACCENT_SECOND_HUE] ?: defaults.accentSecondHue,
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.POINTER_SPEED] = settings.pointerSpeed
            prefs[Keys.NATURAL_SCROLL] = settings.naturalScroll
            prefs[Keys.PRECISION_POINTER] = settings.precisionPointer
            prefs[Keys.MOMENTUM] = settings.momentum
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.MIRROR_PRESET] = settings.mirrorPreset
            prefs[Keys.HAPTICS] = settings.haptics
            prefs[Keys.ACCENT] = settings.accent
            prefs[Keys.ACCENT_HUE] = settings.accentHue
            prefs[Keys.ACCENT_SECOND_HUE] = settings.accentSecondHue
        }
    }

    /** The Deck's tiles — search, launched apps, shortcuts, power and media. Defaults to
     *  [defaultDeckItems] until the user's first edit writes something back. */
    val deckItems: Flow<List<DeckItem>> = context.dataStore.data.map { prefs ->
        prefs[Keys.DECK_ITEMS]?.let { decodeDeckItems(it) } ?: defaultDeckItems()
    }

    suspend fun saveDeckItems(items: List<DeckItem>) {
        context.dataStore.edit { prefs -> prefs[Keys.DECK_ITEMS] = encodeDeckItems(items) }
    }

    /** One-tap phrases for the keyboard, newline-separated — see
     *  [defaultQuickPhrases]. Stored as the same text the editor
     *  shows, so there is nothing to encode and nothing that can fail to parse. */
    val quickPhrases: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.QUICK_PHRASES]?.let { text ->
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } ?: defaultQuickPhrases()
    }

    suspend fun saveQuickPhrases(phrases: List<String>) {
        context.dataStore.edit { prefs -> prefs[Keys.QUICK_PHRASES] = phrases.joinToString("\n") }
    }

    /** Whether the trackpad's gesture legend has done its job. It used to be composable
     *  state, which the Control tab's cross-fade throws away — so the onboarding caption
     *  came back into the middle of the pad every time the user returned from Keyboard or
     *  Media. Seen once is seen. */
    val padLegendSeen: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.PAD_LEGEND_SEEN] ?: false
    }

    suspend fun markPadLegendSeen() {
        context.dataStore.edit { prefs -> prefs[Keys.PAD_LEGEND_SEEN] = true }
    }

    /** Forgets the paired PC only — pointer speed and the rest are the user's
     *  preferences, not the pairing's, so they survive re-pairing. */
    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.HOST)
            prefs.remove(Keys.PORT)
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.NAME)
            prefs.remove(Keys.SERVER_ID)
        }
    }
}
