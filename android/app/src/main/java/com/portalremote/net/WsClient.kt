package com.portalremote.net

import android.util.Log
import com.portalremote.data.SavedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "WsClient"
private const val RECONNECT_DELAY_MS = 1500L

/** Ceiling on the backoff below. A phone in a pocket with the PC switched off used to
 *  open a TCP connection every 1.5s forever; past this the retry is a heartbeat rather
 *  than a scan, and a PC that comes back is still noticed within half a minute. */
private const val RECONNECT_MAX_DELAY_MS = 30_000L

/** How long a live server on the LAN gets to answer a protocol ping — see [checkAlive].
 *  A real round trip is a few ms; anything near this is a socket that is not coming back. */
private const val PONG_TIMEOUT_MS = 2_000L

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
        // Also the only thing that notices a connection the network dropped out from
        // under us mid-use: OkHttp fails the socket when the *next* ping is due and the
        // previous pong never came, so the dead window is two intervals. At 20s that was
        // up to 40s of a trackpad that sends into the void with no error — a ping frame
        // is six bytes, so shortening it costs nothing worth counting.
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob())
    // Written on the main thread, read from OkHttp's callback threads.
    @Volatile
    private var socket: WebSocket? = null
    private var lastHost: SavedHost? = null
    private var intentionalDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var aliveJob: Job? = null
    private var pingSeq = 0

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
        reconnectAttempts = 0
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
                if (webSocket !== socket) return
                Log.i(TAG, "socket open, awaiting hello")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("t")) {
                    "hello" -> {
                        reconnectAttempts = 0
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
                // A socket this client has already replaced still reports its own close.
                // Acting on it marked a *live* connection as Disconnected and scheduled a
                // second reconnect against the new host — which is how following a PC to a
                // new address ended with the phone listed twice on the PC and the shell
                // stuck saying "Reconnecting…" over a connection that was up.
                if (webSocket !== socket) return
                Log.i(TAG, "socket closed: $code $reason")
                if (code == UNAUTHORIZED_CLOSE_CODE) {
                    _state.value = ConnectionState.Failed("Pairing token was rejected")
                    return
                }
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
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
        // Doubling, capped: the first retry is still immediate enough for a Wi-Fi blip,
        // and an hour against a PC that is switched off is no longer an hour of radio.
        val delayMs = (RECONNECT_DELAY_MS shl reconnectAttempts.coerceAtMost(5))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempts++
        reconnectJob = scope.launch {
            delay(delayMs)
            if (intentionalDisconnect) return@launch
            Log.i(TAG, "attempting reconnect to ${host.host}:${host.port}")
            _state.value = ConnectionState.Connecting
            openSocket(host)
        }
    }

    /**
     * Prove the socket is still attached to a server, and reconnect if it isn't. For
     * the moment the app comes back to the foreground: a phone that slept, roamed to
     * another access point or lost Wi-Fi for a while keeps a socket that still says
     * open, and every send into it succeeds locally and goes nowhere — the trackpad
     * simply stops, with no error and no "Reconnecting…", until OkHttp's own ping
     * happens to notice. A protocol ping gets an answer in milliseconds from a
     * server that can hear us; not getting one inside [PONG_TIMEOUT_MS] is the
     * verdict. A brief trip to the share sheet or the photo picker costs two tiny
     * frames and nothing else.
     */
    fun checkAlive() {
        val host = lastHost ?: return
        if (_state.value !is ConnectionState.Connected) return
        aliveJob?.cancel()
        aliveJob = scope.launch {
            val seq = ++pingSeq
            // Subscribed before the ping goes out: the shared flow has no replay, and
            // on a LAN the pong can land before this coroutine gets back to collecting.
            val pong = async(start = CoroutineStart.UNDISPATCHED) {
                messages.first { it.optString("t") == "pong" && it.optInt("seq") == seq }
            }
            send(JSONObject().put("t", "ping").put("seq", seq))
            val answered = withTimeoutOrNull(PONG_TIMEOUT_MS) { pong.await() } != null
            if (answered || intentionalDisconnect) { pong.cancel(); return@launch }
            Log.w(TAG, "no pong within ${PONG_TIMEOUT_MS}ms; socket is dead, reconnecting")
            connect(host)
        }
    }

    fun disconnect() {
        aliveJob?.cancel()
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

    /** Best-effort send: dropped if the socket isn't open, by design — trackpad deltas
     *  are transient and a stale send is worse than a skipped one. Returns whether it
     *  actually went out, which the callers that are sending something a person typed
     *  (and cannot retype) need in order to keep it. */
    fun send(json: JSONObject): Boolean = socket?.send(json.toString()) ?: false

    companion object {
        const val UNAUTHORIZED_CLOSE_CODE = 4401
    }
}
