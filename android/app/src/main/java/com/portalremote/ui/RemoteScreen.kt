package com.portalremote.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.portalremote.data.SavedHost
import com.portalremote.net.Protocol
import com.portalremote.net.ServerHello
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import org.json.JSONObject

private enum class RemoteTab(val label: String) {
    TRACKPAD("Trackpad"), KEYBOARD("Keyboard"), MEDIA("Media"), FILES("Files")
}

/**
 * Post-pairing shell: bottom nav between the trackpad, keyboard, media and
 * files screens. Trackpad/keyboard/media send through the same [send] callback
 * into the live socket; files talks to the server's HTTP endpoints directly
 * using [host].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    hello: ServerHello,
    host: SavedHost,
    reconnecting: Boolean = false,
    send: (JSONObject) -> Unit,
    onDisconnect: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { RemoteTitle(deviceName = hello.name, reconnecting = reconnecting) },
                actions = {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Disconnect")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PortalRemoteTheme.extendedColors.surfaceRaised,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = PortalRemoteTheme.extendedColors.surfaceRaised) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.TouchApp, contentDescription = null) },
                    label = { Text(RemoteTab.TRACKPAD.label) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                    label = { Text(RemoteTab.KEYBOARD.label) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                    label = { Text(RemoteTab.MEDIA.label) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text(RemoteTab.FILES.label) },
                )
            }
        },
    ) { padding ->
        // Cross-fade only, no slide — a tab switch should read as switching, not
        // navigating. See docs/design-system.md §6.
        Crossfade(
            targetState = tab,
            animationSpec = tween(Motion.TabSwitchDurationMs, easing = Motion.EaseOut),
            modifier = Modifier.padding(padding).fillMaxSize(),
            label = "remote-tab",
        ) { selected ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (selected) {
                    0 -> TrackpadScreen(
                        onMove = { dx, dy -> send(Protocol.mouseMove(dx, dy)) },
                        onScroll = { dy -> send(Protocol.scroll(dy = dy)) },
                        onClick = { button, down -> send(Protocol.mouseClick(button, down)) },
                    )
                    1 -> KeyboardScreen(
                        onText = { s -> send(Protocol.text(s)) },
                        onTap = { key -> send(Protocol.tap(key)) },
                        onCombo = { keys -> send(Protocol.combo(*keys.toTypedArray())) },
                    )
                    2 -> MediaScreen(onMedia = { action -> send(Protocol.media(action)) })
                    else -> FilesScreen(host = host)
                }
            }
        }
    }
}

/** Device name + a small status dot, per docs/design-system.md §7. RemoteScreen stays
 * mounted through a brief control-socket blip (see [reconnecting]) so screen state —
 * current Files folder, an in-flight upload — survives instead of bouncing the user
 * back to PairScreen every time the socket hiccups. The dot morphs color over 200ms
 * ease-in-out rather than snapping, per §6's "connect/disconnect status change" spec
 * — gated behind the reduced-motion check per §6/§9, an instant swap instead when the
 * system "remove animations" setting is on. */
@Composable
private fun RemoteTitle(deviceName: String, reconnecting: Boolean) {
    val context = LocalContext.current
    val spec: AnimationSpec<Color> = if (Motion.reducedMotionEnabled(context)) {
        snap()
    } else {
        tween(Motion.StatusMorphDurationMs, easing = Motion.EaseInOut)
    }
    val dotColor by animateColorAsState(
        targetValue = if (reconnecting) {
            PortalRemoteTheme.extendedColors.warning
        } else {
            PortalRemoteTheme.extendedColors.success
        },
        animationSpec = spec,
        label = "status-dot",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Box(modifier = Modifier.width(8.dp))
        Column {
            Text(deviceName, style = MaterialTheme.typography.titleLarge)
            Text(
                if (reconnecting) "Reconnecting…" else "Connected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
