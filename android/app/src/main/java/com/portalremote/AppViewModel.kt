package com.portalremote

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalremote.data.AppSettings
import com.portalremote.data.Prefs
import com.portalremote.data.SavedHost
import com.portalremote.net.AiCatalog
import com.portalremote.net.AiModels
import com.portalremote.net.AiModelsException
import com.portalremote.net.AiState
import com.portalremote.net.AiTranscript
import com.portalremote.net.ChatTurn
import com.portalremote.net.CastState
import com.portalremote.net.CastStatus
import com.portalremote.net.CastTarget
import com.portalremote.net.ConnectionState
import com.portalremote.net.MediaServer
import com.portalremote.net.NowPlaying
import com.portalremote.net.Protocol
import com.portalremote.net.ServerHello
import com.portalremote.net.ShareApi
import com.portalremote.net.ShareEntry
import com.portalremote.net.ShareKind
import com.portalremote.net.WsClient
import com.portalremote.net.discoverHosts
import com.portalremote.net.localIpv4Addresses
import com.portalremote.net.pickLocalAddress
import com.portalremote.ui.copyToClipboard
import com.portalremote.ui.displayNameOf
import com.portalremote.ui.notifyShare
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** How long WsClient gets to fix a drop on the known address before a discovery
 *  sweep is worth the radio. */
private const val FOLLOW_DELAY_MS = 6_000L

/** Share history is in-memory and scrollable, not a log — past this the old
 *  entries are noise you'd scroll past to reach the ones that matter. */
private const val MAX_SHARES = 50

/** How long a share handed to us by another app waits for the pairing to come
 *  back up before it gives up and says so. */
private const val SHARE_HOST_WAIT_MS = 15_000L

private const val SENDING = "Sending…"

/** Shown while a share is queued for the next time the PC is reachable. Deliberately
 *  not "Failed": nothing was lost, and the app will send it without being asked. */
private const val WAITING = "Waiting for your PC"

/** Message type the PC pushes a share down the control socket as. */
private const val SHARE = "share"

/** Message type the server pushes the PC's playback state under. */
private const val NOW_PLAYING = "now_playing"

/** The server's acknowledgement of a `cast`, carrying where the link ended up. */
private const val CAST_OK = "cast_ok"

/** The server's acknowledgement of a `player` transport command. */
private const val PLAYER_OK = "player_ok"

/** What the receiver page reports it is doing, forwarded by the server. */
private const val CAST_STATUS = "cast_status"

/** The screens this PC can cast to, pushed whenever a scan changes the list. */
private const val CAST_TARGETS = "cast_targets"

/** Whether the assistant's backend is up, pushed by the PC. */
private const val AI_STATE = "ai_state"

/** Something typed, on this phone or on the PC. One message for both, because the PC
 *  decides on its own whether it is a question or something to do — there is no longer a
 *  second button here saying which. */
private const val AI_ASK = "ai_ask"
private const val AI_REGENERATE = "ai_regenerate"
private const val AI_STOP = "ai_stop"
private const val AI_CLEAR = "ai_clear"

/** The conversation, pushed by the PC that owns it: the whole thing, one turn, or more
 *  text for a turn already on screen. */
private const val AI_CHAT = "ai_chat"
private const val AI_TURN = "ai_turn"
private const val AI_DELTA = "ai_delta"

/** The subset of a plan the user approved, or a plan dismissed without running it. */
private const val AI_CONFIRM = "ai_confirm"
private const val AI_CANCEL = "ai_cancel"

/** Errors come back as `{"t":"error","detail":…}`; this is the one that means the
 *  receiver page has gone away since we last cast to it. */
private const val ERROR = "error"
private const val NO_RECEIVER = "no cast receiver"

/** Discovery re-probes every 800ms; this is several probes' worth of patience
 *  before concluding the PC is genuinely not on this network. */
private const val DISCOVERY_TIMEOUT_MS = 5_000L

/**
 * App-scoped state: the single WebSocket connection and the last-paired host.
 * Survives navigation between screens; does not survive process death (a fresh
 * pairing/reconnect on relaunch is preferable to trusting a stale token silently).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    val ws = WsClient()
    private val shareApi = ShareApi()

    val connectionState get() = ws.state

    private val _shares = MutableStateFlow<List<ShareEntry>>(emptyList())

    /** Newest first — what the Share tab shows. */
    val shares: StateFlow<List<ShareEntry>> = _shares.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** What the PC is playing, pushed by the server; null when it's playing nothing. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _cast = MutableStateFlow<CastState?>(null)

    /** The link this phone last cast, and where it landed — null when there is
     *  nothing of ours playing to control. */
    val cast: StateFlow<CastState?> = _cast.asStateFlow()

    private val _castStatus = MutableStateFlow<CastStatus?>(null)

    /** Position, duration and paused state of the receiver page, pushed as it plays.
     *  Null when nothing is attached or it hasn't reported yet — which is what tells
     *  the transport to fall back to blind buttons rather than draw an empty bar. */
    val castStatus: StateFlow<CastStatus?> = _castStatus.asStateFlow()

    private val _castTargets = MutableStateFlow<List<CastTarget>>(emptyList())

    /** Every screen the PC can reach — itself, an open receiver page, and whatever the
     *  last LAN scan found. Empty until the picker is opened, since asking costs a
     *  round trip and most casts never leave the PC. */
    val castTargets: StateFlow<List<CastTarget>> = _castTargets.asStateFlow()

    private val _castTarget = MutableStateFlow<String?>(null)

    /** The [CastTarget.id] the next cast goes to, or null for "let the PC choose".
     *  Sticky, so casting three links in a row to the TV is one choice, not three. */
    val castTarget: StateFlow<String?> = _castTarget.asStateFlow()

    private val _castScanning = MutableStateFlow(false)

    /** A LAN sweep is running. SSDP takes seconds, so the picker says so rather than
     *  looking like a list that is simply short. */
    val castScanning: StateFlow<Boolean> = _castScanning.asStateFlow()

    private val _aiState = MutableStateFlow<AiState?>(null)

    /** Whether the assistant's backend is answering. Null until the PC says — which it
     *  does on connect, so the tab is right the moment it opens. */
    val aiState: StateFlow<AiState?> = _aiState.asStateFlow()

    private val aiModels = AiModels()

    private val _aiCatalog = MutableStateFlow<AiCatalog?>(null)

    /** Every provider/model this PC could switch `/ai/chat` to, and which one currently
     *  answers it. Null until the picker is opened — a catalogue read can hit live
     *  provider APIs on the PC's side, so it isn't fetched on every tab open. */
    val aiCatalog: StateFlow<AiCatalog?> = _aiCatalog.asStateFlow()

    private val _aiCatalogLoading = MutableStateFlow(false)

    val aiCatalogLoading: StateFlow<Boolean> = _aiCatalogLoading.asStateFlow()

    private val _aiCatalogError = MutableStateFlow<String?>(null)

    val aiCatalogError: StateFlow<String?> = _aiCatalogError.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatTurn>>(emptyList())

    /**
     * The conversation. **Held by the PC, mirrored here** — it is persisted over there,
     * pushed in full on connect, and updated by push after that. A reply to something
     * typed in the PC's own assistant window lands in this list exactly like one typed
     * here, which is what makes the two surfaces one assistant rather than two chats.
     */
    val chat: StateFlow<List<ChatTurn>> = _chat.asStateFlow()

    /**
     * The assistant is busy — a reply still arriving, or a decision still running after
     * the text finished. Read off the transcript rather than tracked separately: the PC
     * owns that fact, and a second copy of it here could disagree.
     *
     * Deciding counts. The PC takes one ask at a time and drops a second, so a Send button
     * still enabled while a local model spends thirty seconds on the tool prompt is a
     * button that silently throws the message away.
     */
    val chatStreaming: StateFlow<Boolean> =
        _chat.map { turns -> turns.any { it.streaming || it.deciding } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var nextShareId = 0L

    /** Serves picked files to the PC. Null until something is cast from this phone —
     *  a listening socket nobody asked for is not worth the battery. */
    private var mediaServer: MediaServer? = null

    /** `<token>@<address>` the live [mediaServer] was started for. */
    private var mediaKey: String? = null

    /**
     * Outgoing shares that haven't landed yet, keyed by entry id and holding the
     * closure that sends them. A share made while the PC is asleep, or interrupted
     * by a Wi-Fi drop mid-upload, sits here until [retryPending] runs on the next
     * reconnect — the phone is the device that moves between networks, so "try again
     * later" has to be the app's job rather than the user's.
     */
    private val pending = linkedMapOf<Long, suspend (SavedHost) -> Unit>()

    /** Ids with an attempt already running, so a flapping link can't start a second. */
    private val inFlight = mutableSetOf<Long>()

    /** This phone, as the PC should name it in its notification. */
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        .replaceFirstChar { it.uppercase() }

    /** Eagerly started so the first frame of the trackpad already has the user's
     *  pointer speed, rather than moving at the default for a frame and then jumping. */
    val settings: StateFlow<AppSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { prefs.saveSettings(change(settings.value)) }
    }

    /** The last PC that paired successfully, for the pairing screen to offer back.
     *  Eagerly started for the same reason as [settings]: it decides what the very
     *  first frame of that screen looks like. */
    val savedHost: StateFlow<SavedHost?> =
        prefs.savedHost.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    private var followJob: Job? = null

    // A saved pairing stores an IP address, and DHCP hands out a different one
    // eventually — after a router reboot, a lease expiry, a move between bands.
    // Without this the app retries a dead address forever and the only way out the
    // user can see is pairing again from scratch.
    init {
        viewModelScope.launch {
            ws.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        followJob?.cancel()
                        followJob = null
                        // The PC is back — flush anything that couldn't go out while
                        // it wasn't. Keyed off the control socket rather than off
                        // Android's network callbacks because reaching *this* PC is
                        // the condition that matters, and having Wi-Fi is not it.
                        retryPending()
                    }

                    ConnectionState.Disconnected -> followToNewAddress()
                    // Failed means the token was rejected, which a new address
                    // won't fix, and Connecting/Idle aren't failures yet.
                    else -> Unit
                }
            }
        }

        // Shares pushed from the PC arrive on the control socket already open, so
        // there's nothing to poll and nothing to keep alive — see docs/phase5-share.md
        // for why receiving while the app is closed is deliberately not built.
        viewModelScope.launch {
            ws.messages.collect { json ->
                // The PC pushes what it's playing on the same socket, for the same
                // reason: it already exists, and this is state nobody needs to poll for.
                if (json.optString("t") == NOW_PLAYING) {
                    _nowPlaying.value = NowPlaying.fromPush(json)
                    return@collect
                }
                // Where a cast landed is only knowable from the reply: the same
                // message reaches a receiver page (controllable) or ShellExecute
                // (not), and the phone can't tell which until the PC says so.
                if (json.optString("t") == CAST_OK) {
                    _cast.value = CastState.fromAck(json)
                    return@collect
                }
                // Stop is the one transport command that ends the session rather
                // than changing it, so it's also what takes the controls away.
                if (json.optString("t") == PLAYER_OK) {
                    if (json.optString("action") == "stop") {
                        _cast.value = null
                        _castStatus.value = null
                    }
                    return@collect
                }
                if (json.optString("t") == CAST_TARGETS) {
                    _castTargets.value = CastTarget.listFromPush(json)
                    _castScanning.value = CastTarget.scanningFromPush(json)
                    // The PC is the authority on where the current cast landed — a
                    // second phone, or the PC's own DLNA renderer, can move it.
                    CastTarget.activeFromPush(json)?.let { _castTarget.value = it }
                    // A target that has gone (TV switched off, receiver tab closed) must
                    // not stay selected, or the next cast fails with "no cast target
                    // called…" instead of quietly going to the PC.
                    _castTarget.value = _castTarget.value?.takeIf { chosen ->
                        _castTargets.value.any { it.id == chosen }
                    }
                    return@collect
                }
                if (json.optString("t") == AI_STATE) {
                    _aiState.value = AiState.fromPush(json)
                    return@collect
                }
                // The conversation lives on the PC. Everything below is this phone being
                // told what it now says — including replies to something typed on the
                // desktop, which is what makes the two surfaces one assistant.
                if (json.optString("t") == AI_CHAT) {
                    _chat.value = AiTranscript.snapshot(json)
                    return@collect
                }
                if (json.optString("t") == AI_TURN) {
                    json.optJSONObject("turn")?.let {
                        _chat.value = AiTranscript.upsert(_chat.value, ChatTurn.fromJson(it))
                    }
                    return@collect
                }
                if (json.optString("t") == AI_DELTA) {
                    _chat.value = AiTranscript.delta(
                        _chat.value, json.optString("id"), json.optString("text"),
                    )
                    return@collect
                }
                if (json.optString("t") == CAST_STATUS) {
                    _castStatus.value = CastStatus.fromPush(json)
                    // `receiver:false` against a cast we believed was controllable
                    // means the page was closed at the other end. Previously that only
                    // surfaced as an error on the next button press; now the controls
                    // go away when the receiver does.
                    if (!json.optBoolean("receiver") && _cast.value?.controllable == true) {
                        _cast.value = null
                    }
                    return@collect
                }
                // The receiver was closed at the other end. Nothing failed on this
                // side, but there is no longer anything to drive.
                if (json.optString("t") == ERROR) {
                    if (json.optString("detail").startsWith(NO_RECEIVER)) {
                        _cast.value = null
                        _castStatus.value = null
                    }
                    return@collect
                }
                // Id claimed only once the message is known to be a share: pongs and
                // errors come through here too, and burning an id on each would walk
                // the notification ids up for no reason.
                if (json.optString("t") != SHARE) return@collect
                val entry = ShareEntry.fromPush(json, nextShareId++) ?: return@collect
                onShareReceived(entry)
            }
        }

        // A dropped socket means the card is showing a track that may have ended,
        // paused or changed while we were away. The server re-sends on connect, so
        // blank is only what's shown in the gap.
        viewModelScope.launch {
            ws.state.collect { state ->
                if (state !is ConnectionState.Connected) {
                    _nowPlaying.value = null
                    // Same reasoning for the cast: the server restarting takes every
                    // receiver's socket with it, and transport buttons that error on
                    // every press are worse than no buttons.
                    _cast.value = null
                    _castStatus.value = null
                    // The target list is the PC's, not ours — a different PC (or the
                    // same one restarted) has a different set of screens in reach.
                    _castTargets.value = emptyList()
                    _castTarget.value = null
                    _castScanning.value = false
                }
            }
        }
    }

    /**
     * A share landed from the PC. Text goes onto the clipboard before the
     * notification fires — the feature is only quick if the thing is already
     * pasteable by the time you look at the phone.
     */
    private fun onShareReceived(entry: ShareEntry) {
        val context = getApplication<Application>()
        if (entry.kind == ShareKind.TEXT || entry.kind == ShareKind.LINK) {
            entry.text?.let { copyToClipboard(context, it) }
        }
        record(entry)
        notifyShare(context, entry)
    }

    /** Send text (typed, pasted, or arriving from another app's share sheet) to the PC. */
    fun shareText(text: String) {
        if (text.isBlank()) return
        val entry = ShareEntry(
            id = nextShareId++,
            incoming = false,
            kind = ShareKind.forText(text),
            text = text,
            from = deviceName,
            status = SENDING,
        )
        record(entry)
        send(entry) { host -> shareApi.sendText(host, text, deviceName) }
    }

    /** Send a file or image [uri] to the PC — the system share sheet's payload. */
    fun shareUri(uri: Uri) {
        val context = getApplication<Application>()
        val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: "shared.bin"
        val type = context.contentResolver.getType(uri)
        val entry = ShareEntry(
            id = nextShareId++,
            incoming = false,
            kind = ShareKind.forFile(name),
            fileName = name,
            from = deviceName,
            status = SENDING,
        )
        record(entry)
        send(entry) { host -> shareApi.sendFile(context, host, name, type, uri, deviceName) }
    }

    /**
     * Everything an app can hand us through ACTION_SEND. Called from MainActivity
     * for both the cold start and, thanks to `singleTop`, a share into a session
     * that's already open.
     */
    fun shareFromIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

        // A stream wins over the text extra: apps that share an image often attach a
        // caption too, and the image is the thing the user pointed at.
        if (uri != null) {
            shareUri(uri)
            return
        }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shareText(it) }
    }

    /**
     * Queue [transmit] against the paired PC and try it now.
     *
     * The payload lives in the closure rather than on [ShareEntry] so a retry needs
     * nothing from the model — a file share's content uri stays captured here, which
     * is also why retries only work for the life of the process: the read permission
     * an ACTION_SEND grant carries dies with it.
     */
    private fun send(entry: ShareEntry, transmit: suspend (SavedHost) -> Unit) {
        pending[entry.id] = transmit
        deliver(entry.id)
    }

    /**
     * One delivery attempt. Waits for a host if the app was cold-started *by* the
     * share — the share sheet can put us on screen a second or two before the address
     * is settled — and leaves the item in [pending] on any failure so
     * [retryPending] can pick it up when the PC comes back.
     */
    private fun deliver(id: Long) {
        val transmit = pending[id] ?: return
        // A reconnect can fire while an attempt is still running (a flapping Wi-Fi
        // link produces several), and sending the same file twice is worse than
        // sending it late.
        if (!inFlight.add(id)) return

        viewModelScope.launch {
            update(id) { it.copy(status = SENDING) }
            try {
                val host = currentHost ?: withTimeoutOrNull(SHARE_HOST_WAIT_MS) {
                    snapshotFlow { currentHost }.filterNotNull().first()
                }
                if (host == null) {
                    update(id) { it.copy(status = WAITING) }
                    return@launch
                }
                try {
                    transmit(host)
                    pending.remove(id)
                    update(id) { it.copy(status = null) }
                } catch (e: Exception) {
                    // Kept in `pending`: this is "not yet", not "never". The reason is
                    // still shown, since a 401 won't fix itself on reconnect and the
                    // user should be able to tell that from a dropped Wi-Fi link.
                    update(id) { it.copy(status = "$WAITING — ${e.message ?: "send failed"}") }
                }
            } finally {
                inFlight.remove(id)
            }
        }
    }

    /**
     * Re-send everything that hasn't made it across, oldest first so a burst of
     * shares arrives on the PC in the order they were made. Called on every
     * reconnect, so a share made with the PC asleep goes out by itself the moment
     * it wakes — the user should not have to remember which ones failed.
     */
    private fun retryPending() {
        pending.keys.sorted().forEach { deliver(it) }
    }

    /** Try a stuck share again now, from a tap on its row. */
    fun retryShare(id: Long) = deliver(id)

    private fun record(entry: ShareEntry) {
        val trimmed = (listOf(entry) + _shares.value).take(MAX_SHARES)
        _shares.value = trimmed
        // Anything that aged off the list is no longer retryable from the UI, so
        // holding its payload (and, for a file, its uri) would be a slow leak.
        val live = trimmed.mapTo(mutableSetOf()) { it.id }
        pending.keys.retainAll(live)
    }

    private fun update(id: Long, change: (ShareEntry) -> ShareEntry) {
        _shares.value = _shares.value.map { if (it.id == id) change(it) else it }
    }

    /**
     * Find the saved PC at whatever address it has now, and reconnect there.
     *
     * Matching is on the server's stable id, never on name or address: two PCs on
     * one network can share a name, and the address is the very thing that just
     * turned out to be wrong.
     */
    private fun followToNewAddress() {
        val host = currentHost ?: return
        // Nothing to match on — this pairing predates the id, or has never
        // completed a hello. Leave WsClient retrying the address we were given.
        val id = host.id ?: return
        if (followJob?.isActive == true) return

        followJob = viewModelScope.launch {
            // Let WsClient's own retry go first: most drops are a blip on an
            // address that is still correct, and a discovery sweep for those is
            // wasted radio.
            delay(FOLLOW_DELAY_MS)
            val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                discoverHosts()
                    .map { hosts -> hosts.firstOrNull { it.id == id } }
                    .filterNotNull()
                    .first()
            } ?: return@launch

            if (found.host == host.host && found.port == host.port) return@launch
            val moved = host.copy(host = found.host, port = found.port, name = found.name)
            prefs.save(moved)
            currentHost = moved
            ws.connect(moved)
        }
    }

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

        // Remember what the PC calls itself, and its stable id: the pairing screen
        // can then offer it back by name next launch instead of as an IP address,
        // and [followToNewAddress] has something to recognise it by if that IP
        // stops working.
        val host = currentHost ?: return
        val updated = host.copy(
            name = hello.name,
            id = hello.id ?: host.id,
            // Kept from before if this server is too old to send one: a MAC learned
            // once is still the same machine's.
            mac = hello.mac ?: host.mac,
        )
        if (updated == host) return
        currentHost = updated
        viewModelScope.launch { prefs.save(updated) }
    }

    fun send(json: JSONObject) = ws.send(json)

    /** Ask the PC whether the assistant's backend is up. [retry] is a person pressing
     *  the button, which is always allowed to skip the PC's backoff. */
    fun probeAi(retry: Boolean) = send(JSONObject().put("t", AI_STATE).put("retry", retry))

    /** Load the provider/model picker's contents. Cheap to call more than once — a
     *  second call while one is already in flight is a no-op, since two phones' worth
     *  of "which models exist" only needs asking once. */
    fun loadAiCatalog() {
        val host = currentHost ?: return
        if (_aiCatalogLoading.value) return
        _aiCatalogLoading.value = true
        _aiCatalogError.value = null
        viewModelScope.launch {
            try {
                _aiCatalog.value = aiModels.fetch(host)
            } catch (ex: AiModelsException) {
                _aiCatalogError.value = ex.message
            } finally {
                _aiCatalogLoading.value = false
            }
        }
    }

    /**
     * Switch which provider/model answers `/ai/chat`. [provider] blank means "let
     * agent-platform decide from [model] alone" — the same zero-setup state the PC
     * starts in (`docs/phase7-assistant.md` §9).
     *
     * The catalogue itself is trusted to stay put — only [AiCatalog.currentProvider] and
     * [AiCatalog.currentModel] move — so this updates them in place rather than
     * re-fetching a list that didn't change.
     */
    fun selectAiModel(provider: String?, model: String) {
        val host = currentHost ?: return
        val previous = _aiCatalog.value
        _aiCatalog.value = previous?.withCurrent(provider, model)
        viewModelScope.launch {
            try {
                aiModels.select(host, provider, model)
            } catch (ex: AiModelsException) {
                _aiCatalog.value = previous
                _aiCatalogError.value = ex.message
            }
        }
    }

    /**
     * Say something to the assistant.
     *
     * **One button, and the PC decides what it was.** There used to be two — Send and a
     * wand — because asking a question and asking for this PC to be touched are different
     * acts and guessing which was meant is a guess that presses keys when it is wrong. It
     * still is: the PC now asks *both* halves at once, streams the reply and works out
     * whether the same sentence maps onto an action, and anything it finds comes back as a
     * card with its own buttons. Nothing runs until somebody presses one of them, so there
     * is no wrong guess left to make.
     *
     * Nothing is added to the transcript here. The PC appends the turn and pushes it back,
     * so this phone and the PC's own window show the same message in the same order.
     */
    fun sendChat(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        send(JSONObject().put("t", AI_ASK).put("text", message))
    }

    /**
     * Run the actions the user ticked, by index.
     *
     * The subset is the point: a plan is not all-or-nothing, and approving "pause it" does
     * not have to mean approving "and shut down the PC" (§5).
     */
    fun confirmPlan(turnId: String, approved: List<Int>) = send(
        JSONObject()
            .put("t", AI_CONFIRM)
            .put("id", turnId)
            .put("approved", JSONArray(approved)),
    )

    /** Dismiss without running anything. The card stays in the transcript saying it was
     *  declined, because one that simply vanished reads as one that quietly went ahead. */
    fun cancelPlan(turnId: String) = send(JSONObject().put("t", AI_CANCEL).put("id", turnId))

    /**
     * Ask again for the last reply.
     *
     * Drops the previous answer rather than appending a second one: the user is saying
     * *that* was wrong or cut off, and two attempts at the same question stacked on top
     * of each other is a transcript nobody wants to read.
     */
    fun regenerateChat() = send(JSONObject().put("t", AI_REGENERATE))

    /** Stop the reply where it is. A deliberate stop is not a failure, so what arrived
     *  is kept as a whole answer rather than flagged as cut off. */
    fun stopChat() = send(JSONObject().put("t", AI_STOP))

    /** Wipe it everywhere — this phone, the PC's window, the file on the PC — since there
     *  is only one conversation to wipe now (§7). */
    fun clearChat() = send(JSONObject().put("t", AI_CLEAR))

    /**
     * Cast a file that lives on this phone — phase 4d of `docs/phase4-casting.md`.
     *
     * The phone becomes the server for the length of the film: it mints an id for the
     * picked document and hands the PC a URL pointing back here, which mpv or the
     * receiver page then pulls with range requests. Nothing is uploaded first; a
     * two-gigabyte film would otherwise have to cross the Wi-Fi before playing.
     *
     * Returns null when the cast went out, or the reason it didn't.
     */
    fun castLocalFile(uri: Uri): String? {
        val host = currentHost ?: return "Not connected to a PC"
        // Which of this phone's addresses the PC can dial back on. Getting this wrong
        // produces a cast that fails silently at the other end.
        val local = pickLocalAddress(localIpv4Addresses(), host.host)
            ?: return "This phone has no network address the PC could reach"

        val resolver = getApplication<Application>().contentResolver
        var name = "Video"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        // No length means no Content-Length and no seeking, and a player that can't
        // seek in a local film is worse than one that says it can't play it.
        if (size <= 0) return "Can't tell how big that file is, so it can't be streamed"

        val server = mediaServer(host.token, local) ?: return "Couldn't open a port on this phone"
        val id = server.offer(
            MediaServer.Item(name, resolver.getType(uri) ?: "application/octet-stream", size) { offset ->
                val stream = resolver.openInputStream(uri) ?: throw IOException("can't read $uri")
                // ponytail: skip() rather than a seekable descriptor. On a MediaStore or
                // file-backed document this *is* a seek; on an exotic provider it reads
                // and throws away. Swap to openFileDescriptor if that ever shows up.
                var skipped = 0L
                while (skipped < offset) {
                    val moved = stream.skip(offset - skipped)
                    if (moved <= 0) break
                    skipped += moved
                }
                stream
            }
        )

        val url = server.urlFor(id, local) ?: return "Couldn't open a port on this phone"
        send(Protocol.cast(url, name, _castTarget.value))
        return null
    }

    /**
     * Ask the PC what it can cast to, and sweep the LAN for Rokus and DLNA renderers if
     * [scan] — step 4k of `docs/phase4-casting.md`. The PC answers from its cache
     * immediately and pushes again a few seconds later with whatever the sweep found, so
     * the picker is never blank while it waits.
     */
    fun refreshCastTargets(scan: Boolean = true) = send(Protocol.castTargets(scan))

    /** Choose where the next cast goes. Null hands the decision back to the PC, which
     *  prefers a receiver page, then mpv, then the desktop's default player. */
    fun chooseCastTarget(id: String?) {
        _castTarget.value = id
    }

    /**
     * The file server, started on first use and rebuilt when the pairing or this
     * phone's address changes — a URL minted against the old address is dead anyway.
     */
    private fun mediaServer(token: String, address: String): MediaServer? {
        val key = "$token@$address"
        if (mediaKey != key) {
            mediaServer?.close()
            mediaServer = runCatching { MediaServer().apply { start(address) } }.getOrNull()
            mediaKey = if (mediaServer != null) key else null
        }
        return mediaServer
    }

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
        // The read grant on a picked document dies with the process anyway, so there
        // is nothing here worth outliving it.
        mediaServer?.close()
    }
}

fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Not connected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected to ${state.hello.name}"
    is ConnectionState.Failed -> "Failed: ${state.reason}"
    ConnectionState.Disconnected -> "Disconnected"
}
