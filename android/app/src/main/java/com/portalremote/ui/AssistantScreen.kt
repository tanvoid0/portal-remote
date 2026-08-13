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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalremote.net.AiCatalog
import com.portalremote.net.AiModel
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
    catalog: AiCatalog?,
    catalogLoading: Boolean,
    catalogError: String?,
    onProbe: (retry: Boolean) -> Unit,
    onSend: (String) -> Unit,
    onAct: (String) -> Unit,
    onConfirm: (List<Int>) -> Unit,
    onCancelPlan: () -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onLoadCatalog: () -> Unit,
    onSelectModel: (provider: String?, model: String) -> Unit,
) {
    // Probe on open, not on a timer. The PC pushes changes after this, so one is enough.
    LaunchedEffect(Unit) { onProbe(false) }

    if (state?.ready == true) {
        ChatPane(
            chat = chat,
            streaming = streaming,
            error = error,
            deciding = deciding,
            catalog = catalog,
            catalogLoading = catalogLoading,
            catalogError = catalogError,
            onSend = onSend,
            onAct = onAct,
            onRegenerate = onRegenerate,
            onStop = onStop,
            onClear = onClear,
            onLoadCatalog = onLoadCatalog,
            onSelectModel = onSelectModel,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    chat: List<ChatTurn>,
    streaming: Boolean,
    error: String?,
    deciding: Boolean,
    catalog: AiCatalog?,
    catalogLoading: Boolean,
    catalogError: String?,
    onSend: (String) -> Unit,
    onAct: (String) -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onLoadCatalog: () -> Unit,
    onSelectModel: (provider: String?, model: String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the reply as it grows. Keyed on the last turn's length as well as the count,
    // because a streaming reply is one item getting longer rather than new items arriving.
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ModelBar(
            currentModel = catalog?.currentModel,
            onClick = {
                showModelPicker = true
                // A catalogue read can hit live provider APIs on the PC's side, so it
                // isn't fetched until the sheet that shows it is actually opened — and
                // not fetched again on a second open, since the list doesn't change
                // just because the sheet closed and reopened.
                if (catalog == null && !catalogLoading) onLoadCatalog()
            },
        )

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

    if (showModelPicker) {
        ModalBottomSheet(onDismissRequest = { showModelPicker = false }) {
            ModelPickerSheet(
                catalog = catalog,
                loading = catalogLoading,
                error = catalogError,
                onRetry = onLoadCatalog,
                onSelect = { provider, model ->
                    onSelectModel(provider, model)
                    showModelPicker = false
                },
            )
        }
    }
}

/** The chip that opens the model picker. Sits above the transcript rather than in a
 *  settings screen, because which model answers is a thing this app's other "chips, not
 *  a sheet" surfaces (the mirror's monitor/quality row) already treat as something you
 *  change *while looking at the result* — except here the result is the whole
 *  conversation, so a sheet (below) rather than an inline chip row is the right size for
 *  "browse every provider and model", not just "flip between two or three presets". */
@Composable
private fun ModelBar(currentModel: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextButton(onClick = onClick) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                currentModel ?: "Model",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Spacer(Modifier.size(2.dp))
            Icon(Icons.Filled.ExpandMore, contentDescription = "Change model", modifier = Modifier.size(16.dp))
        }
    }
}

/** Every provider/model the phone could switch `/ai/chat` to, grouped by provider.
 *  Neither an unconfigured provider nor a model belonging to one is hidden — they're
 *  shown disabled with the reason, same rule as everywhere else in this app that a
 *  capability might not be available (`docs/design-system.md` §4.5's empty-state rule,
 *  applied to a single disabled row instead of a whole screen). */
@Composable
private fun ModelPickerSheet(
    catalog: AiCatalog?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (provider: String?, model: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            "Model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) }

            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            catalog == null || catalog.models.isEmpty() -> Text(
                "Nothing to switch to — agent-platform isn't reporting any chat models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            else -> catalog.providers.forEachIndexed { index, provider ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ProviderGroup(
                    provider = provider.id,
                    configured = provider.configured,
                    models = catalog.models.filter { it.provider == provider.id },
                    currentModel = catalog.currentModel.takeIf { catalog.currentProvider == provider.id },
                    onSelect = { model -> onSelect(provider.id, model) },
                )
            }
        }
    }
}

@Composable
private fun ProviderGroup(
    provider: String,
    configured: Boolean,
    models: List<AiModel>,
    currentModel: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            Text(
                provider,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!configured) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "not configured on the PC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        models.forEach { model ->
            val selected = model.id == currentModel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = model.configured) { onSelect(model.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(20.dp)) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = "Current", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.configured) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** One turn, both sides in a colored bubble so a reply reads as a distinct message
 *  rather than blending into the screen background — the same shape as ShareScreen's
 *  device-to-device chat, so both surfaces in this app "read as a conversation" the
 *  same way. Sided by role: the user's is tinted and right-shifted, the assistant's is
 *  neutral and left-shifted. */
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
                    if (turn.fromUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .padding(12.dp),
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
