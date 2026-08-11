package com.portalremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "portal_remote_prefs")

/** A paired desktop server, as scanned from its QR code or entered manually. */
data class SavedHost(
    val host: String,
    val port: Int,
    val token: String,
) {
    val httpBase: String get() = "http://$host:$port"

    // No token in the query string: it would otherwise land in plaintext in the
    // server's request logs on every connect. WsClient sends it as an
    // Authorization header on the handshake instead — the server accepts either.
    val wsUrl: String get() = "ws://$host:$port/control"
}

/** Persists the last-paired server so the app can reconnect without rescanning. */
class Prefs(private val context: Context) {
    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
    }

    val savedHost: Flow<SavedHost?> = context.dataStore.data.map { prefs ->
        val host = prefs[Keys.HOST]
        val port = prefs[Keys.PORT]?.toIntOrNull()
        val token = prefs[Keys.TOKEN]
        if (host != null && port != null && token != null) SavedHost(host, port, token) else null
    }

    suspend fun currentSavedHost(): SavedHost? = savedHost.first()

    suspend fun save(host: SavedHost) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = host.host
            prefs[Keys.PORT] = host.port.toString()
            prefs[Keys.TOKEN] = host.token
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
