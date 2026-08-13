package com.portalremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.portalremote.net.AiState

/**
 * The assistant, and — for now — only the honest version of it: whether its backend is
 * up, why it isn't, and what to do about that. Step 7a of `docs/phase7-assistant.md`,
 * built before the chat it will hold, because everything after it assumes a correct
 * answer to "is it up?".
 *
 * The tab is shown disabled rather than hidden (§4.5): a tab that vanishes reads as a
 * bug, and "there is no assistant here" is a worse answer than "it isn't running".
 */
@Composable
fun AssistantScreen(state: AiState?, onProbe: (retry: Boolean) -> Unit) {
    // Probe on open, not on a timer. The PC pushes changes after this, so one is enough.
    LaunchedEffect(Unit) { onProbe(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state?.starting == true) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        } else {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            state?.headline ?: "Asking the PC…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        // The reason names the real cause — a port that isn't answering, or a config
        // field nobody filled in — and either way it belongs on screen rather than in
        // a log on the other machine.
        state?.detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (state?.ready == true) {
            Text(
                "Chat is not built yet — this tab currently only reports whether the " +
                    "backend is reachable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else if (state?.starting != true) {
            // A person pressing this is always allowed to skip the PC's backoff.
            TextButton(onClick = { onProbe(true) }) { Text("Try again") }
        }
    }
}
