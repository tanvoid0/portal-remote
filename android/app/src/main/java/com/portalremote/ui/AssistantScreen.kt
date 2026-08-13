package com.portalremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.portalremote.net.AiPlan
import com.portalremote.net.AiState
import com.portalremote.net.ChatTurn

/**
 * The assistant — step 7b of `docs/phase7-assistant.md`.
 *
 * Two screens in one, chosen by whether the backend is up. That split is the point: the
 * tab is shown disabled rather than hidden (§4.5), because a tab that vanishes reads as a
 * bug, and "it isn't running, here's why" is a better answer than an input box that will
 * fail when someone types in it.
 */
@Composable
fun AssistantScreen(
    state: AiState?,
    chat: List<ChatTurn>,
    streaming: Boolean,
    error: String?,
    plan: AiPlan?,
    deciding: Boolean,
    onProbe: (retry: Boolean) -> Unit,
    onSend: (String) -> Unit,
    onAct: (String) -> Unit,
    onConfirm: (List<Int>) -> Unit,
    onCancelPlan: () -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    // Probe on open, not on a timer. The PC pushes changes after this, so one is enough.
    LaunchedEffect(Unit) { onProbe(false) }

    if (state?.ready == true) {
        ChatPane(
            chat = chat,
            streaming = streaming,
            error = error,
            deciding = deciding,
            onSend = onSend,
            onAct = onAct,
            onRegenerate = onRegenerate,
            onStop = onStop,
            onClear = onClear,
        )
    } else {
        BackendDown(state = state, onProbe = onProbe)
    }

    // Outside the pane split on purpose: a plan asked for a moment ago must not be
    // dismissed by the backend blinking, because the actions in it are still ours to run.
    plan?.let { ConfirmPlan(plan = it, onConfirm = onConfirm, onCancel = onCancelPlan) }
}

/**
 * The confirmation — step 7c, and structurally the whole of §7.
 *
 * **Nothing auto-executes.** Every action is listed in plain language with its parameters,
 * approval is per-action, and the two power modes that lose unsaved work take a second
 * confirm on top — the same rule the TV remote's power menu already follows, for the same
 * reason: a mis-tap here costs whatever was open on a machine in another room.
 */
@Composable
private fun ConfirmPlan(plan: AiPlan, onConfirm: (List<Int>) -> Unit, onCancel: () -> Unit) {
    // Everything starts ticked: the model was asked to do this, and a sheet that starts
    // empty makes the common case — "yes, all of that" — the fiddly one.
    val approved = remember(plan.id) { mutableStateListOf<Int>().apply { addAll(plan.actions.map { it.index }) } }
    var confirmingDestructive by remember(plan.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text("Do this on the PC?") },
        text = {
            Column {
                // The model's own reasoning, which is the only thing that explains *why*
                // these actions and not others.
                if (plan.thought.isNotBlank()) {
                    Text(
                        plan.thought,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                plan.actions.forEach { action ->
                    val ticked = action.index in approved
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (ticked) approved.remove(action.index) else approved.add(action.index)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            action.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val destructive = plan.actions.any { it.destructive && it.index in approved }
                if (destructive) confirmingDestructive = true else onConfirm(approved.toList())
            }) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )

    if (confirmingDestructive) {
        AlertDialog(
            onDismissRequest = { confirmingDestructive = false },
            title = { Text("Shut down or restart the PC?") },
            text = { Text("Anything unsaved on the PC will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDestructive = false
                    onConfirm(approved.toList())
                }) { Text("Do it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDestructive = false }) { Text("Cancel") }
            },
        )
    }
}

/** Why there is no chat here, and what would fix it. */
@Composable
private fun BackendDown(state: AiState?, onProbe: (retry: Boolean) -> Unit) {
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

        if (state?.starting != true) {
            Spacer(Modifier.height(16.dp))
            // A person pressing this is always allowed to skip the PC's backoff.
            TextButton(onClick = { onProbe(true) }) { Text("Try again") }
        }
    }
}

@Composable
private fun ChatPane(
    chat: List<ChatTurn>,
    streaming: Boolean,
    error: String?,
    deciding: Boolean,
    onSend: (String) -> Unit,
    onAct: (String) -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the reply as it grows. Keyed on the last turn's length as well as the count,
    // because a streaming reply is one item getting longer rather than new items arriving.
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (chat.isEmpty()) {
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Ask the assistant something.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(chat) { turn -> Bubble(turn) }
            }
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) {
                TextButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Stop")
                }
            } else if (chat.lastOrNull()?.incomplete == true) {
                // The reply was cut off. Offering this beside what did arrive is the
                // whole reason the partial text is kept rather than discarded (§4.4).
                TextButton(onClick = onRegenerate) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Regenerate")
                }
            }
            if (chat.isNotEmpty() && !streaming) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
            // A local model deciding takes tens of seconds. Without this, "Do it" is a
            // button that looks like it did nothing for most of a minute.
            if (deciding) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Working out what to do…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                // The draft is never cleared by a failure — only by a send that started.
                // A question retyped because the backend blinked is the rudest possible
                // way to report that it blinked (§4.5).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank() && !streaming) {
                        onSend(draft)
                        draft = ""
                    }
                }),
                maxLines = 4,
            )
            // Two buttons, because asking a question and asking for this PC to be touched
            // are different acts. Guessing which was meant from the wording is a guess
            // this app doesn't have to make — and the wrong guess presses keys.
            TransportButton(
                icon = Icons.Filled.AutoAwesome,
                description = "Do it on the PC",
                filled = false,
                enabled = draft.isNotBlank() && !streaming && !deciding,
            ) {
                onAct(draft)
                draft = ""
            }
            TransportButton(
                icon = Icons.AutoMirrored.Filled.Send,
                description = "Send",
                filled = true,
                enabled = draft.isNotBlank() && !streaming,
            ) {
                onSend(draft)
                draft = ""
            }
        }
    }
}

/** One turn. The user's is tinted and right-shifted; the assistant's runs full width,
 *  because that is the one that gets long. */
@Composable
private fun Bubble(turn: ChatTurn) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(
                    if (turn.fromUser) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                .padding(if (turn.fromUser) 12.dp else 0.dp),
        ) {
            Text(
                // An assistant turn is empty for the instant between the request going
                // out and the first token landing; an empty bubble looks like a bug.
                turn.text.ifEmpty { "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (turn.fromUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (turn.incomplete && turn.text.isNotEmpty()) {
                Text(
                    "Cut off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
