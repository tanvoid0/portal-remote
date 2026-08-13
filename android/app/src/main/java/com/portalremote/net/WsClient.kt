package com.portalremote.net

import android.util.Log
import com.portalremote.data.SavedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "WsClient"
private const val RECONNECT_DELAY_MS = 1500L

data class ServerHello(
    val name: String,
    val version: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Stable per-install id — see [com.portalremote.data.SavedHost.id]. Null from
     *  servers built before this field existed. */
    val id: String? = null,
    /** This PC's LAN adapter address, for waking it later. Null from servers built
     *  before this field existed, and from a machine with no ordinary LAN adapter. */
    val mac: String? = null,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hello: ServerHello) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
    data object Disconnected : ConnectionState
}

/**
 * Owns the single `/control` WebSocket connection. One instance is shared for the
 * app's lifetime; screens send through it and observe [state] to know when it's
 * safe to send.
 *
 * The control socket is not the only thing that matters to a screen — e.g. the
 * Files screen talks to the server over plain HTTP — so a control-socket blip
 * (backgrounded for the system file/photo picker, brief Wi-Fi hiccup, screen
 * lock) auto-reconnects to the same host instead of forcing the whole app back
 * to the pairing screen and tearing down whatever the user was doing.
 */
class WsClient(private val device: String = "") {
    private val client = OkHttpClient.Builder()
        // No read timeout: the socket is meant to sit open indefinitely between
        // user gestures, so an idle connection must not be torn down as "stalled".
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob())
    private var socket: WebSocket? = null
    private var lastHost: SavedHost? = null
    private var intentionalDisconnect = false
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Non-hello frames from the server (currently just `error`/`pong`). */
    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    fun connect(host: SavedHost) {
        reconnectJob?.cancel()
        closeSocket(intentional = false) // clears the old socket without touching lastHost/state timing below
        lastHost = host
        intentionalDisconnect = false
        _state.value = ConnectionState.Connecting
        openSocket(host)
    }

    private fun openSocket(host: SavedHost) {
        // Bearer header, not a ?token= query param: the query string form is what
        // the server falls back to for requests that can't set headers (downloads,
        // <img> tags), but here it would just leak the token into the server's
        // plaintext request logs on every connect for no reason.
        val request = Request.Builder()
            .url(host.wsUrl)
            .header("Authorization", "Bearer ${host.token}")
            // What the PC labels this phone in its device list. A header rather than a
            // query param for the same reason the token is one, and because a name with
            // an apostrophe in it should not need escaping to reach the other side.
            .apply { if (device.isNotBlank()) header("X-Portal-Device", device) }
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "socket open, awaiting hello")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("t")) {
                    "hello" -> {
                        val screen = json.optJSONObject("screen")
                        _state.value = ConnectionState.Connected(
                            ServerHello(
                                name = json.optString("name", "Desktop"),
                                version = json.optString("version", "?"),
                                screenWidth = screen?.optInt("width") ?: 0,
                                screenHeight = screen?.optInt("height") ?: 0,
                                id = json.optStringOrNull("id"),
                                mac = json.optStringOrNull("mac"),
                            )
                        )
                    }
                    else -> _messages.tryEmit(json)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed: $code $reason")
                if (code == UNAUTHORIZED_CLOSE_CODE) {
                    _state.value = ConnectionState.Failed("Pairing token was rejected")
                    return
                }
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure", t)
                if (response?.code == 401) {
                    _state.value = ConnectionState.Failed("Pairing token was rejected")
                    return
                }
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        })
    }

    /** Retry the same host after a brief delay, unless the caller explicitly disconnected. */
    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
        val host = lastHost ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (intentionalDisconnect) return@launch
            Log.i(TAG, "attempting reconnect to ${host.host}:${host.port}")
            _state.value = ConnectionState.Connecting
            openSocket(host)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        intentionalDisconnect = true
        lastHost = null
        closeSocket(intentional = true)
    }

    private fun closeSocket(intentional: Boolean) {
        socket?.close(1000, "client closing")
        socket = null
        if (intentional && _state.value !is ConnectionState.Idle) {
            _state.value = ConnectionState.Disconnected
        }
    }

    /** Best-effort send: silently dropped if the socket isn't open, by design —
     *  trackpad/gamepad deltas are transient and a stale send is worse than a
     *  skipped one. */
    fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    companion object {
        const val UNAUTHORIZED_CLOSE_CODE = 4401
    }
}
