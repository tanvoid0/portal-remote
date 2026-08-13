package com.portalremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.portalremote.data.AppSettings
import com.portalremote.data.SavedHost
import com.portalremote.net.ServerHello
import com.portalremote.ui.theme.PortalRemoteTheme
import com.portalremote.ui.theme.rememberHaptics

/**
 * The app's one settings surface. Everything here is either a preference the
 * gesture code reads live (pointer speed, scroll direction) or a fact about the
 * pairing the user might need to check — deliberately not a second home for
 * controls that belong next to the thing they affect (the mirror's monitor and
 * quality chips stay on the mirror, per docs/design-system.md §7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    hello: ServerHello,
    host: SavedHost,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }

    Scaffold(
        // System bars are hidden app-wide (see MainActivity), so `safeDrawing` here is
        // just the display cutout — without it this bar would draw under a notch.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PortalRemoteTheme.extendedColors.surfaceRaised,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeader("Pointer")

            SettingRow(
                title = "Pointer speed",
                subtitle = "How far the cursor travels per swipe · " +
                    "%.2f×".format(settings.pointerSpeed),
            ) {
                Slider(
                    value = settings.pointerSpeed,
                    onValueChange = { speed -> onSettingsChange { it.copy(pointerSpeed = speed) } },
                    valueRange = 0.5f..2.5f,
                    // 0.25x notches: fine enough to tune, coarse enough to land on a
                    // round number without fighting the thumb.
                    steps = 7,
                )
            }

            SwitchRow(
                title = "Fine control",
                subtitle = "Damp the pointer when the finger is barely moving, so a " +
                    "careful nudge lands on a pixel instead of past it",
                checked = settings.precisionPointer,
                onCheckedChange = { on -> onSettingsChange { it.copy(precisionPointer = on) } },
            )

            SettingRow(
                title = "Scroll momentum",
                subtitle = "How far a flick keeps scrolling after the finger leaves. " +
                    "A long document wants a throw that carries; code usually doesn't",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MomentumLevel.entries.forEach { option ->
                        FilterChip(
                            selected = MomentumLevel.from(settings.momentum) == option,
                            onClick = { onSettingsChange { it.copy(momentum = option.name) } },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            SwitchRow(
                title = "Natural scrolling",
                subtitle = "Two-finger drag down moves the content down, as on a phone",
                checked = settings.naturalScroll,
                onCheckedChange = { on -> onSettingsChange { it.copy(naturalScroll = on) } },
            )

            HorizontalDivider(color = PortalRemoteTheme.extendedColors.border)
            // Reference, not a control — but this is where someone looks for "what can
            // this thing do", and the pad's own legend is deliberately gone after the
            // first touch. Everything past two fingers was otherwise undiscoverable.
            SectionHeader("Trackpad gestures")

            TrackpadGestures.forEach { (gesture, effect) ->
                GestureRow(gesture, effect)
            }

            HorizontalDivider(color = PortalRemoteTheme.extendedColors.border)
            SectionHeader("Feel")

            // Fires the real thing on the way *on*, since the ambient Haptics is still
            // the old (possibly silent) one at the moment of the tap — and "what does
            // this feel like" is the only question this row has.
            val preview = rememberHaptics(enabled = true)
            SwitchRow(
                title = "Haptic feedback",
                subtitle = "A tick for every click, key and scroll notch — the only " +
                    "confirmation the glass can give that a tap reached the PC",
                checked = settings.haptics,
                onCheckedChange = { on ->
                    if (on) preview.confirm()
                    onSettingsChange { it.copy(haptics = on) }
                },
            )

            HorizontalDivider(color = PortalRemoteTheme.extendedColors.border)
            SectionHeader("Screen")

            SwitchRow(
                title = "Keep the phone awake",
                subtitle = "While a PC is connected. Watching the mirror or holding the " +
                    "trackpad does not always count as activity to the system timer",
                checked = settings.keepScreenOn,
                onCheckedChange = { on -> onSettingsChange { it.copy(keepScreenOn = on) } },
            )

            SettingRow(
                title = "Mirror quality",
                subtitle = "What the Screen tab opens with — still switchable while watching",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MirrorPreset.entries.forEach { option ->
                        FilterChip(
                            selected = settings.mirrorPreset == option.name,
                            onClick = { onSettingsChange { it.copy(mirrorPreset = option.name) } },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            HorizontalDivider(color = PortalRemoteTheme.extendedColors.border)
            SectionHeader("This PC")

            InfoRow("Name", hello.name)
            InfoRow("Address", "${host.host}:${host.port}")
            InfoRow("Desktop", "${hello.screenWidth} × ${hello.screenHeight}")
            InfoRow("Server version", hello.version)
            InfoRow("App version", appVersion())

            TextButton(
                onClick = { confirmForget = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Forget this PC", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // Forgetting drops the token, so the only way back is the QR code — worth a
    // confirmation, unlike the tap-to-disconnect it replaces.
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${hello.name}?") },
            text = { Text("You will need to scan its pairing QR code again to reconnect.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Title + explanation with a control underneath — for anything wider than a switch. */
@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        control()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Gesture on top, what it does underneath — not [InfoRow]'s two columns, since a
 *  couple of these wrap and a wrapped right-hand column reads as a broken table. */
@Composable
private fun GestureRow(gesture: String, effect: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(gesture, style = MaterialTheme.typography.bodyMedium)
        Text(
            effect,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Read off the installed package rather than BuildConfig, which this module
 *  doesn't generate (buildFeatures.buildConfig is off). */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
