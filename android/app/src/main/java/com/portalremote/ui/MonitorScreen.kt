package com.portalremote.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalremote.data.AppSettings
import com.portalremote.data.SavedHost
import com.portalremote.net.PcStats
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.PortalRemoteTheme
import org.json.JSONObject

/** The two ways of looking at the PC rather than driving it. */
internal enum class MonitorMode(val label: String, val icon: ImageVector) {
    SCREEN("Screen", Icons.Filled.ScreenShare),
    STATS("Stats", Icons.Filled.Insights),
}

/**
 * Watching the PC: its desktop, and its vitals.
 *
 * Grouped for the same reason trackpad and keyboard are (docs/design-system.md §13) —
 * they are one activity, "how is that machine doing", and they were the two tabs most
 * often flipped between. Folding them here is also what made room in the bottom bar for
 * the Stats screen at all: six icons was already at the limit §13 sets, and adding a
 * seventh would have made the bar the most crowded thing on the phone.
 *
 * In full screen the row goes with the rest of the chrome — the mirror is the one screen
 * whose content is itself a screen, and a tab row around it is a frame around a picture
 * of a frame.
 */
@Composable
fun MonitorScreen(
    host: SavedHost,
    settings: AppSettings,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onPresetChange: (MirrorPreset) -> Unit,
    send: (JSONObject) -> Unit,
    stats: PcStats?,
    statsHistory: List<PcStats>,
    onWatchStats: (Boolean) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var mode by rememberSaveable { mutableStateOf(MonitorMode.SCREEN) }

    Column(modifier = Modifier.fillMaxSize().background(PortalRemoteTheme.hud.background)) {
        if (!fullscreen) {
            PortalSubTabRow(
                entries = MonitorMode.entries,
                selected = mode,
                label = { it.label },
                icon = { it.icon },
                onSelect = { mode = it },
            )
        }

        Crossfade(
            targetState = mode,
            animationSpec = tween(Motion.TabSwitchDurationMs, easing = Motion.EaseOut),
            modifier = Modifier.fillMaxSize(),
            label = "monitor-mode",
        ) { selected ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (selected) {
                    MonitorMode.SCREEN -> ScreenScreen(
                        host = host,
                        settings = settings,
                        fullscreen = fullscreen,
                        onFullscreen = onFullscreen,
                        onPresetChange = onPresetChange,
                        send = send,
                    )
                    // Only mounted while this is the visible half, which is exactly what
                    // stops the PC sampling itself for a screen nobody is looking at —
                    // StatsScreen subscribes on composition and releases on disposal.
                    MonitorMode.STATS -> StatsScreen(
                        stats = stats,
                        history = statsHistory,
                        onWatch = onWatchStats,
                        bottomInset = bottomInset,
                    )
                }
            }
        }
    }
}
