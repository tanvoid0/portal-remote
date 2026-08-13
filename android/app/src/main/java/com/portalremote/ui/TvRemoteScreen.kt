package com.portalremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DesktopAccessDisabled
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.portalremote.net.NowPlaying
import com.portalremote.net.PowerTimerState
import com.portalremote.ui.theme.HapticPress
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.Motion
import com.portalremote.ui.theme.rememberPressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the power button can do. Split five ways because "power" on a PC isn't one
 * thing — and [destructive] is what decides whether the tap needs confirming, since
 * two of these close every unsaved document on the machine.
 *
 * [wire] values are the modes `Input/Power.cs` accepts; pinned by `PowerModeTest`,
 * because a rename on either side fails silently at the far end of a socket.
 */
/** [icon] is doing real work in the picker: five near-synonyms in a column ("Lock",
 *  "Sleep", "Shut down") are exactly the list a couch user has to read twice, and this
 *  is the one menu in the app where picking the wrong line costs unsaved work. */
internal enum class PowerMode(
    val wire: String,
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean,
) {
    // Cheapest of the five and the one a couch actually wants: the PC keeps playing,
    // the monitor stops lighting a dark room. Any input on the PC undoes it.
    SCREEN_OFF("screen_off", "Screen off", Icons.Filled.DesktopAccessDisabled, false),
    LOCK("lock", "Lock", Icons.Filled.Lock, false),
    SLEEP("sleep", "Sleep", Icons.Filled.Bedtime, false),
    RESTART("restart", "Restart", Icons.Filled.RestartAlt, true),
    SHUTDOWN("shutdown", "Shut down", Icons.Filled.PowerSettingsNew, true),
}

/** How long a D-pad arrow must be held before it starts repeating — Windows' own
 *  keyboard default, so a held arrow on the phone behaves like a held arrow on the PC. */
private const val REPEAT_DELAY_MS = 400L

/** Gap between repeats once they start (~16/sec). */
private const val REPEAT_INTERVAL_MS = 60L

/** The chips under the pad — a lounge-chair remote's "everything else" row. */
private data class RemoteKey(val label: String, val keys: List<String>)

private val REMOTE_KEYS = listOf(
    RemoteKey("Esc", listOf("esc")),
    RemoteKey("Tab", listOf("tab")),
    RemoteKey("Alt+Tab", listOf("alt", "tab")),
    RemoteKey("Page ↑", listOf("pgup")),
    RemoteKey("Page ↓", listOf("pgdn")),
    RemoteKey("Refresh", listOf("f5")),
    RemoteKey("Task view", listOf("win", "tab")),
    RemoteKey("Desktop", listOf("win", "d")),
    RemoteKey("Close", listOf("alt", "f4")),
)

/**
 * The couch mode: a TV remote for the PC. Everything here is a discrete press with a
 * visible result on the other screen — no tracking, no gestures — which is the point,
 * since it's the one control surface you can use without looking at the phone.
 */
@Composable
fun TvRemoteScreen(
    nowPlaying: NowPlaying?,
    onTap: (key: String) -> Unit,
    onCombo: (keys: List<String>) -> Unit,
    onMedia: (action: String) -> Unit,
    onPower: (mode: String) -> Unit,
    /** What's scheduled on the PC right now, or null — pushed by the server, so a
     *  reconnect (or a second phone) always agrees with what's actually counting down. */
    powerTimer: PowerTimerState?,
    onPowerTimerSet: (mode: String, seconds: Int) -> Unit,
    onPowerTimerCancel: () -> Unit,
) {
    var powerOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PowerMode?>(null) }
    // The mode being scheduled, while its quick-suggestion/custom-minutes sheet is up.
    var timerPicker by remember { mutableStateOf<PowerMode?>(null) }
    var customMinutes by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Power sits apart from the navigation cluster, in the error colour, at the
        // top-left corner your thumb doesn't rest on — the one button here you must
        // not press by accident.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { powerOpen = true },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Power")
            }
            // Visible without opening the picker: what's scheduled fires whether or not
            // anyone is looking at this tab, so the countdown has to be readable at a
            // glance, not buried a tap away. Tapping it opens the same picker, which is
            // where it can be edited or called off.
            powerTimer?.let { timer ->
                val mode = PowerMode.entries.firstOrNull { it.wire == timer.mode }
                AssistChip(
                    onClick = { powerOpen = true },
                    label = { Text("${mode?.label ?: timer.mode} · ${rememberCountdownText(timer.endsAtMs)}") },
                    leadingIcon = {
                        Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f))
            TransportButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onTap("browser_back") }
            TransportButton(Icons.Filled.Home, "Start menu") { onTap("win") }
            TransportButton(Icons.Filled.Menu, "Context menu") { onTap("apps") }
        }

        DirectionPad(onTap = onTap)

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TransportButton(Icons.Filled.SkipPrevious, "Previous") { onMedia("prev") }
            TransportButton(
                icon = if (nowPlaying?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (nowPlaying?.playing == true) "Pause" else "Play",
                size = 56.dp,
                filled = true,
                onClick = { onMedia("play_pause") },
            )
            TransportButton(Icons.Filled.SkipNext, "Next") { onMedia("next") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TransportButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") { onMedia("vol_down") }
            TransportButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { onMedia("mute") }
            TransportButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") { onMedia("vol_up") }
            TransportButton(Icons.Filled.Fullscreen, "Full screen") { onTap("f11") }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(REMOTE_KEYS) { key ->
                KeyButton(onClick = {
                    if (key.keys.size == 1) onTap(key.keys[0]) else onCombo(key.keys)
                }) { Text(key.label) }
            }
        }

        // F-keys are the "functional" half of a TV remote's coloured buttons: whatever
        // the app in front happens to bind them to.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items((1..12).toList()) { n ->
                KeyButton(
                    onClick = { onTap("f$n") },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("F$n") }
            }
        }
    }

    if (powerOpen) {
        AlertDialog(
            onDismissRequest = { powerOpen = false },
            icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
            title = { Text("Power") },
            text = {
                Column {
                    powerTimer?.let { timer ->
                        val mode = PowerMode.entries.firstOrNull { it.wire == timer.mode }
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(
                                "${mode?.label ?: timer.mode} in ${rememberCountdownText(timer.endsAtMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    powerOpen = false
                                    customMinutes = ""
                                    timerPicker = mode
                                }) { Text("Edit") }
                                TextButton(onClick = onPowerTimerCancel) { Text("Cancel timer") }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    }
                    PowerMode.entries.forEach { mode ->
                        val tint = if (mode.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    powerOpen = false
                                    // The reversible two go straight out; the two that lose
                                    // work get asked about, because a mis-tap here costs
                                    // whatever was open on a PC in another room.
                                    if (mode.destructive) confirming = mode else onPower(mode.wire)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    mode.icon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Text(
                                    mode.label,
                                    modifier = Modifier
                                        .padding(start = ButtonDefaults.IconSpacing)
                                        .fillMaxWidth(),
                                    color = tint,
                                )
                            }
                            // Same action, later: schedules this mode instead of firing
                            // it now. A separate control rather than a long-press, since
                            // nothing else in this picker is press-and-hold.
                            IconButton(onClick = {
                                powerOpen = false
                                customMinutes = ""
                                timerPicker = mode
                            }) {
                                Icon(
                                    Icons.Filled.Timer,
                                    contentDescription = "Schedule ${mode.label.lowercase()}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { powerOpen = false }) { Text("Cancel") }
            },
        )
    }

    confirming?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            icon = {
                Icon(
                    mode.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("${mode.label} the PC?") },
            text = { Text("Anything unsaved on the PC will be lost.") },
            confirmButton = {
                TextButton(onClick = { onPower(mode.wire); confirming = null }) {
                    Text(mode.label, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }

    timerPicker?.let { mode ->
        val tint = if (mode.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val minutes = customMinutes.toIntOrNull()
        AlertDialog(
            onDismissRequest = { timerPicker = null },
            icon = { Icon(mode.icon, contentDescription = null, tint = tint) },
            title = { Text("Schedule “${mode.label}”") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The countdown itself is the undo window a same-instant action
                    // doesn't have, so this warns rather than gates behind a second
                    // confirm the way the immediate button does.
                    if (mode.destructive) {
                        Text(
                            "Anything unsaved on the PC will be lost when this fires.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(QUICK_TIMER_MINUTES) { quick ->
                            AssistChip(
                                onClick = {
                                    onPowerTimerSet(mode.wire, quick * 60)
                                    timerPicker = null
                                },
                                label = { Text(quickTimerLabel(quick)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { text -> customMinutes = text.filter(Char::isDigit).take(4) },
                        label = { Text("Custom, in minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onPowerTimerSet(mode.wire, minutes!! * 60); timerPicker = null },
                    enabled = minutes != null && minutes > 0,
                ) { Text("Set", color = tint) }
            },
            dismissButton = {
                TextButton(onClick = { timerPicker = null }) { Text("Cancel") }
            },
        )
    }
}

/** Quick picks offered alongside the custom-minutes field — round numbers a couch
 *  actually reaches for, not every value the field itself would accept. */
private val QUICK_TIMER_MINUTES = listOf(5, 15, 30, 60)

private fun quickTimerLabel(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} hr" else "$minutes min"

/**
 * A live "4:32" that keeps counting down while its dialog or chip is on screen. Ticks
 * once a second rather than animating: a countdown is a reading, not a transition, and
 * §6's motion budget has nothing to say about a number that changes on its own clock.
 */
@Composable
private fun rememberCountdownText(endsAtMs: Long): String {
    var remainingMs by remember(endsAtMs) { mutableLongStateOf(endsAtMs - System.currentTimeMillis()) }
    LaunchedEffect(endsAtMs) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = endsAtMs - System.currentTimeMillis()
        }
    }
    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Arrow keys as a physical cross with OK in the middle. Laid out on a circle rather
 * than as a 3×3 grid of buttons because the shape *is* the affordance: this is the
 * one control on the phone you're meant to be able to hit without looking down.
 */
@Composable
private fun DirectionPad(onTap: (String) -> Unit) {
    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        DPadArrow(Icons.Filled.KeyboardArrowUp, "Up", Alignment.TopCenter) { onTap("up") }
        DPadArrow(Icons.Filled.KeyboardArrowDown, "Down", Alignment.BottomCenter) { onTap("down") }
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", Alignment.CenterStart) { onTap("left") }
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", Alignment.CenterEnd) { onTap("right") }
        OkButton { onTap("enter") }
    }
}

/**
 * An arrow that keeps firing while it's held, after [REPEAT_DELAY_MS] — the same
 * auto-repeat a real keyboard has, and the thing that makes walking down a long list
 * from the couch bearable instead of forty separate taps.
 *
 * Press-and-hold means bypassing the click gesture, so the scale pulse and the haptic
 * are driven off this button's own down/up tracking (as `TrackpadScreen`'s ClickButton
 * does). One buzz on the press and nothing during the repeat: §6a's rule is that
 * continuous output gets no continuous feedback, and 16 taps a second is a rattle.
 */
@Composable
private fun BoxScope.DPadArrow(
    icon: ImageVector,
    description: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    FilledTonalIconButton(
        onClick = {},
        modifier = Modifier
            .align(alignment)
            .padding(4.dp)
            .size(56.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .pointerInput(haptics) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptics.tap()
                    scope.launch { scale.animateTo(Motion.PressScale, Motion.pressSpec()) }
                    val repeat = scope.launch {
                        // The first press lands immediately — the repeat is what waits,
                        // so a plain tap is never delayed by the hold feature.
                        onClick()
                        delay(REPEAT_DELAY_MS)
                        while (true) {
                            onClick()
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                    scope.launch { scale.animateTo(1f, Motion.pressSpec()) }
                }
            },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
    }
}

/** Enter, wearing the name every remote gives it. */
@Composable
private fun OkButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interactionSource)
    HapticPress(interactionSource)
    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .size(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Text("OK", style = MaterialTheme.typography.titleMedium)
    }
}
