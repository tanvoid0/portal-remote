package com.portalremote.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.portalremote.data.AppSettings
import com.portalremote.data.SavedHost
import com.portalremote.net.CastState
import com.portalremote.net.CastStatus
import com.portalremote.net.CastTarget
import com.portalremote.net.NowPlaying
import com.portalremote.net.PowerTimerState
import com.portalremote.net.Volume
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.Motion
import org.json.JSONObject

/**
 * The four ways to drive the PC by hand. One page, because they are one activity —
 * you point at a thing, then type into it, then turn the volume down — and paying a
 * bottom-nav trip for each of those was making the phone feel like four apps.
 */
internal enum class ControlMode(val label: String, val icon: ImageVector) {
    TRACKPAD("Trackpad", Icons.Filled.Mouse),
    KEYBOARD("Keyboard", Icons.Filled.Keyboard),
    MEDIA("Media", Icons.Filled.PlayCircle),
    REMOTE("Remote", Icons.Filled.SettingsRemote),
}

/**
 * Trackpad / keyboard / media / TV remote under one tab row. The row is the only
 * chrome added by the merge, and it buys back two of the six bottom-nav slots — see
 * docs/design-system.md §13.
 */
@Composable
fun ControlScreen(
    host: SavedHost,
    settings: AppSettings,
    nowPlaying: NowPlaying?,
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Int) -> Unit,
    onClick: (button: String, down: Boolean?) -> Unit,
    onText: (String) -> Unit,
    onTap: (key: String) -> Unit,
    onCombo: (keys: List<String>) -> Unit,
    onMedia: (action: String) -> Unit,
    onSeek: (ms: Long) -> Unit,
    cast: CastState?,
    castStatus: CastStatus?,
    castTargets: List<CastTarget>,
    castTarget: String?,
    castScanning: Boolean,
    onCastTarget: (String?) -> Unit,
    onScanCastTargets: () -> Unit,
    onCast: (url: String) -> Unit,
    onCastFile: (Uri) -> String?,
    onPlayer: (JSONObject) -> Unit,
    onPower: (mode: String) -> Unit,
    powerTimer: PowerTimerState?,
    onPowerTimerSet: (mode: String, seconds: Int) -> Unit,
    onPowerTimerCancel: () -> Unit,
    volume: Volume?,
    onSetVolume: (Float) -> Unit,
) {
    // Saveable, unlike the shell's own tab: this one survives a rotation, and coming
    // back to the trackpad after turning the phone sideways mid-type is a real loss.
    var mode by rememberSaveable { mutableStateOf(ControlMode.TRACKPAD) }
    val haptics = LocalHaptics.current

    Column(modifier = Modifier.fillMaxSize()) {
        PortalSubTabRow(
            entries = ControlMode.entries,
            selected = mode,
            label = { it.label },
            icon = { it.icon },
            onSelect = { mode = it },
        )

        // Same cross-fade as the shell's tabs: switching modes is switching, not
        // navigating. See docs/design-system.md §6.
        Crossfade(
            targetState = mode,
            animationSpec = tween(Motion.TabSwitchDurationMs, easing = Motion.EaseOut),
            modifier = Modifier.fillMaxSize(),
            label = "control-mode",
        ) { selected ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (selected) {
                    ControlMode.TRACKPAD -> TrackpadScreen(
                        settings = settings,
                        onMove = onMove,
                        onScroll = onScroll,
                        onClick = onClick,
                        onShortcut = onCombo,
                        onText = onText,
                        onTap = onTap,
                        onFocusKeyboard = {
                            if (mode != ControlMode.KEYBOARD) haptics.tick()
                            mode = ControlMode.KEYBOARD
                        },
                    )
                    ControlMode.KEYBOARD -> KeyboardScreen(
                        onText = onText,
                        onTap = onTap,
                        onCombo = onCombo,
                        onSubmit = {
                            if (mode != ControlMode.TRACKPAD) haptics.tick()
                            mode = ControlMode.TRACKPAD
                        },
                    )
                    ControlMode.MEDIA -> MediaScreen(
                        host = host,
                        nowPlaying = nowPlaying,
                        onMedia = onMedia,
                        onSeek = onSeek,
                        cast = cast,
                        castStatus = castStatus,
                        castTargets = castTargets,
                        castTarget = castTarget,
                        castScanning = castScanning,
                        onCastTarget = onCastTarget,
                        onScanCastTargets = onScanCastTargets,
                        onCast = onCast,
                        onCastFile = onCastFile,
                        onPlayer = onPlayer,
                        volume = volume,
                        onSetVolume = onSetVolume,
                    )
                    ControlMode.REMOTE -> TvRemoteScreen(
                        nowPlaying = nowPlaying,
                        onTap = onTap,
                        onCombo = onCombo,
                        onMedia = onMedia,
                        onPower = onPower,
                        powerTimer = powerTimer,
                        onPowerTimerSet = onPowerTimerSet,
                        onPowerTimerCancel = onPowerTimerCancel,
                    )
                }
            }
        }
    }
}
