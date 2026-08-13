package com.portalremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalremote.net.ConnectionState
import com.portalremote.ui.PairScreen
import com.portalremote.ui.RemoteScreen
import com.portalremote.ui.theme.HudAccent
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.hudCanvas
import com.portalremote.ui.theme.rememberHaptics

@Composable
fun PortalRemoteApp(viewModel: AppViewModel = viewModel()) {
    // Read before the theme rather than inside it: the accent is a stored preference, and
    // the theme has to be built from it before anything under it composes, or the app
    // repaints itself a frame after launch.
    val accentName by viewModel.settings.collectAsState()
    PortalRemoteTheme(accent = HudAccent.from(accentName.accent)) {
        // The ruled ground, once, for the whole app — see docs/design-system.md §3. Every
        // panel in every screen rests on this one surface rather than carrying its own
        // texture, which is what makes a tab switch read as moving across one machine
        // instead of between separate documents. Screens with their own opaque content
        // (the trackpad, the mirror) simply cover it.
        Surface(
            color = PortalRemoteTheme.hud.background,
            modifier = Modifier.fillMaxSize().hudCanvas(),
        ) {
            val state by viewModel.connectionState.collectAsState()
            val settings by viewModel.settings.collectAsState()
            val savedHost by viewModel.savedHost.collectAsState()
            val shares by viewModel.shares.collectAsState()
            val nowPlaying by viewModel.nowPlaying.collectAsState()
            val cast by viewModel.cast.collectAsState()
            val castStatus by viewModel.castStatus.collectAsState()
            val castTargets by viewModel.castTargets.collectAsState()
            val castTarget by viewModel.castTarget.collectAsState()
            val castScanning by viewModel.castScanning.collectAsState()
            val powerTimer by viewModel.powerTimer.collectAsState()
            val volume by viewModel.volume.collectAsState()
            val aiState by viewModel.aiState.collectAsState()
            val chat by viewModel.chat.collectAsState()
            val chatStreaming by viewModel.chatStreaming.collectAsState()
            val aiCatalog by viewModel.aiCatalog.collectAsState()
            val aiCatalogLoading by viewModel.aiCatalogLoading.collectAsState()
            val aiCatalogError by viewModel.aiCatalogError.collectAsState()
            val stats by viewModel.stats.collectAsState()
            val statsHistory by viewModel.statsHistory.collectAsState()
            val deckItems by viewModel.deckItems.collectAsState()
            val activeWindow by viewModel.activeWindow.collectAsState()
            var triedSavedHost by remember { mutableStateOf(false) }
            val haptics = rememberHaptics(settings.haptics)

            // The two rare, "something happened to the connection" haptics live here
            // rather than in PairScreen: both pairing paths (QR and discovery) and the
            // silent reconnect all funnel through this one state flow.
            LaunchedEffect(state) {
                when (state) {
                    is ConnectionState.Connected -> haptics.confirm()
                    is ConnectionState.Failed -> haptics.reject()
                    else -> Unit
                }
            }

            // On first launch, silently try the last-paired host rather than
            // forcing a rescan every time the app opens.
            LaunchedEffect(Unit) {
                val hadSaved = viewModel.reconnectSavedHost()
                triedSavedHost = true
                if (!hadSaved) return@LaunchedEffect
            }

            // Record every successful hello so a later transient blip (see below) has
            // something to keep showing.
            LaunchedEffect(state) {
                val connected = state as? ConnectionState.Connected ?: return@LaunchedEffect
                viewModel.onConnected(connected.hello)
            }

            // Already connected once this session: WsClient is auto-reconnecting to the
            // same host in the background, so keep showing the remote UI — using the
            // last-known hello while the blip lasts — instead of bouncing to PairScreen.
            //
            // A hard failure (a rejected token) is included deliberately. It used to
            // drop straight back to the pairing screen, which threw away the folder
            // Files was showing and anything in flight *and* replaced the screen under
            // the user's finger with no statement of what happened. RemoteScreen shows
            // the reason instead and offers the one action that fixes it.
            val showRemote = state is ConnectionState.Connected ||
                (viewModel.hasEverConnected && viewModel.currentHost != null)

            CompositionLocalProvider(LocalHaptics provides haptics) {
                when {
                    !triedSavedHost -> LoadingScreen()

                    // A single RemoteScreen call site, not one per state branch: Compose
                    // keys composables by call site, so branching into a *different*
                    // RemoteScreen(...) call for "reconnecting" would tear down and
                    // recreate this whole subtree on every blip — losing the selected
                    // tab and cancelling anything in flight in FilesScreen (e.g. an
                    // upload) exactly the thing this reconnect logic exists to prevent.
                    showRemote ->
                        RemoteScreen(
                            hello = (state as? ConnectionState.Connected)?.hello
                                ?: requireNotNull(viewModel.lastHello),
                            // currentHost is always set before the socket can reach Connected —
                            // see pairAndConnect/reconnectSavedHost, both set it before ws.connect().
                            host = requireNotNull(viewModel.currentHost),
                            reconnecting = state !is ConnectionState.Connected,
                            failure = (state as? ConnectionState.Failed)?.reason,
                            settings = settings,
                            onSettingsChange = { change -> viewModel.updateSettings(change) },
                            send = { viewModel.send(it) },
                            nowPlaying = nowPlaying,
                            cast = cast,
                            castStatus = castStatus,
                            castTargets = castTargets,
                            castTarget = castTarget,
                            castScanning = castScanning,
                            onCastTarget = { viewModel.chooseCastTarget(it) },
                            onScanCastTargets = { viewModel.refreshCastTargets() },
                            powerTimer = powerTimer,
                            volume = volume,
                            shares = shares,
                            onCastFile = { viewModel.castLocalFile(it) },
                            stats = stats,
                            statsHistory = statsHistory,
                            onWatchStats = { viewModel.watchStats(it) },
                            deckItems = deckItems,
                            onDeckItemsChange = { viewModel.updateDeckItems(it) },
                            activeWindow = activeWindow,
                            onWatchActiveWindow = { viewModel.watchActiveWindow(it) },
                            aiState = aiState,
                            chat = chat,
                            chatStreaming = chatStreaming,
                            aiCatalog = aiCatalog,
                            aiCatalogLoading = aiCatalogLoading,
                            aiCatalogError = aiCatalogError,
                            onProbeAi = { viewModel.probeAi(it) },
                            onSendChat = { viewModel.sendChat(it) },
                            onConfirmPlan = { turnId, approved -> viewModel.confirmPlan(turnId, approved) },
                            onCancelPlan = { viewModel.cancelPlan(it) },
                            onRegenerateChat = { viewModel.regenerateChat() },
                            onStopChat = { viewModel.stopChat() },
                            onClearChat = { viewModel.clearChat() },
                            onLoadAiCatalog = { viewModel.loadAiCatalog() },
                            onSelectAiModel = { provider, model -> viewModel.selectAiModel(provider, model) },
                            onShareText = { viewModel.shareText(it) },
                            onShareUri = { viewModel.shareUri(it) },
                            onRetryShare = { viewModel.retryShare(it) },
                            onForget = { viewModel.forgetHost() },
                        )

                    else -> PairScreen(
                        savedHost = savedHost,
                        connecting = state is ConnectionState.Connecting,
                        errorMessage = (state as? ConnectionState.Failed)?.reason,
                        onPaired = { viewModel.pairAndConnect(it) },
                        onForget = { viewModel.forgetHost() },
                        // Pairing with a *different* PC has to stop WsClient retrying
                        // the old one first, or that retry can land mid-approval and
                        // drop the user into a session they didn't ask for.
                        onStopReconnecting = { viewModel.disconnect() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}
