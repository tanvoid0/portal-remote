package com.portalremote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalremote.data.Prefs
import com.portalremote.data.SavedHost
import com.portalremote.net.ConnectionState
import com.portalremote.net.ServerHello
import com.portalremote.net.WsClient
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * App-scoped state: the single WebSocket connection and the last-paired host.
 * Survives navigation between screens; does not survive process death (a fresh
 * pairing/reconnect on relaunch is preferable to trusting a stale token silently).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    val ws = WsClient()

    val connectionState get() = ws.state

    /** The host currently paired/connecting/connected — the files HTTP client needs
     *  this directly since it doesn't go through the WebSocket. */
    var currentHost by mutableStateOf<SavedHost?>(null)
        private set

    /** The most recent successful hello. Kept around so the UI can keep showing
     *  RemoteScreen (device name, screen size) through a brief reconnect — e.g. the
     *  control socket dropping while the system file picker had the app backgrounded
     *  — instead of bouncing back to the pairing screen and losing whatever the user
     *  was doing (mid-upload, mid-folder-navigation). */
    var lastHello by mutableStateOf<ServerHello?>(null)
        private set

    /** True once this session has connected at least once — distinguishes "never
     *  paired yet" (show PairScreen) from "was connected, blipped" (keep RemoteScreen,
     *  let WsClient's auto-reconnect do its thing). */
    var hasEverConnected by mutableStateOf(false)
        private set

    fun pairAndConnect(host: SavedHost) {
        currentHost = host
        hasEverConnected = false
        lastHello = null
        viewModelScope.launch { prefs.save(host) }
        ws.connect(host)
    }

    /** Reconnect using the last host that was successfully paired, if any. */
    suspend fun reconnectSavedHost(): Boolean {
        val saved = prefs.currentSavedHost() ?: return false
        currentHost = saved
        hasEverConnected = false
        lastHello = null
        ws.connect(saved)
        return true
    }

    fun onConnected(hello: ServerHello) {
        hasEverConnected = true
        lastHello = hello
    }

    fun send(json: JSONObject) = ws.send(json)

    fun disconnect() {
        ws.disconnect()
    }

    fun forgetHost() {
        ws.disconnect()
        currentHost = null
        hasEverConnected = false
        lastHello = null
        viewModelScope.launch { prefs.clear() }
    }

    override fun onCleared() {
        ws.disconnect()
    }
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Not connected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected to ${state.hello.name}"
    is ConnectionState.Failed -> "Failed: ${state.reason}"
    ConnectionState.Disconnected -> "Disconnected"
}
