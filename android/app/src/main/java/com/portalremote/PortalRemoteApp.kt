package com.portalremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.portalremote.ui.theme.PortalRemoteTheme

@Composable
fun PortalRemoteApp(viewModel: AppViewModel = viewModel()) {
    PortalRemoteTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val state by viewModel.connectionState.collectAsState()
            var triedSavedHost by remember { mutableStateOf(false) }

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

            // Already connected once this session and not a hard failure (e.g. a
            // rejected token): WsClient is auto-reconnecting to the same host in the
            // background, so keep showing the remote UI — using the last-known hello
            // while the blip lasts — instead of bouncing to PairScreen.
            val showRemote = state is ConnectionState.Connected ||
                (viewModel.hasEverConnected && viewModel.currentHost != null && state !is ConnectionState.Failed)

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
                        send = { viewModel.send(it) },
                        onDisconnect = { viewModel.forgetHost() },
                    )

                else -> PairScreen(
                    connecting = state is ConnectionState.Connecting,
                    errorMessage = (state as? ConnectionState.Failed)?.reason,
                    onPaired = { viewModel.pairAndConnect(it) },
                )
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
