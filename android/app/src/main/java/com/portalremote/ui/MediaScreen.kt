package com.portalremote.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.portalremote.ui.theme.rememberPressScale

@Composable
fun MediaScreen(onMedia: (action: String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                icon = Icons.Filled.SkipPrevious,
                description = "Previous",
                size = 56.dp,
                onClick = { onMedia("prev") },
            )

            TransportButton(
                icon = Icons.Filled.PlayArrow,
                description = "Play/Pause",
                size = 72.dp,
                filled = true,
                onClick = { onMedia("play_pause") },
            )

            TransportButton(
                icon = Icons.Filled.SkipNext,
                description = "Next",
                size = 56.dp,
                onClick = { onMedia("next") },
            )
        }

        Text("Volume", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TransportButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", onClick = { onMedia("mute") })
            TransportButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", onClick = { onMedia("vol_down") })
            TransportButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", onClick = { onMedia("vol_up") })
        }
    }
}

/** Transport/volume button with the standard 100-120ms press-scale feedback — see
 * docs/design-system.md §6/§7. */
@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interactionSource)
    val modifier = Modifier
        .size(size)
        .graphicsLayer { scaleX = scale; scaleY = scale }

    if (filled) {
        FilledIconButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onPrimary)
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            interactionSource = interactionSource,
            modifier = modifier,
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}
