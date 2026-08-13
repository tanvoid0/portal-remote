package com.portalremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.portalremote.net.PlanAction
import com.portalremote.ui.theme.accentGlow

/**
 * The assistant — `docs/phase7-assistant.md` §7b/§7c, rebuilt around one input.
 *
 * Two screens in one, chosen by whether the backend is up. That split is the point: the
 * tab is shown disabled rather than hidden (§4.5), because a tab that vanishes reads as a
 * bug, and "it isn't running, here's why" is a better answer than an input box that will
 * fail when someone types in it.
 *
 * **The transcript is the PC's.** Everything on this screen is a render of what the PC
 * pushed, including replies to something typed in the PC's own assistant window — so the
 * two surfaces are one conversation rather than two.
 */
@Composable
fun AssistantScreen(
    state: AiState?,
    chat: List<ChatTurn>,
    streaming: Boolean,
    catalog: AiCatalog?,
    catalogLoading: Boolean,
    catalogError: String?,
    onProbe: (retry: Boolean) -> Unit,
    onSend: (String) -> Unit,
    onConfirm: (turnId: String, approved: List<Int>) -> Unit,
    onCancelPlan: (turnId: String) -> Unit,
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
            catalog = catalog,
            catalogLoading = catalogLoading,
            catalogError = catalogError,
            onSend = onSend,
            onConfirm = onConfirm,
            onCancelPlan = onCancelPlan,
            onRegenerate = onRegenerate,
            onStop = onStop,
            onClear = onClear,
            onLoadCatalog = onLoadCatalog,
            onSelectModel = onSelectModel,
        )
    } else {
        BackendDown(state = state, onProbe = onProbe)
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
            TextButton(onClick = { onProbe(true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Try again")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    chat: List<ChatTurn>,
    streaming: Boolean,
    catalog: AiCatalog?,
    catalogLoading: Boolean,
    catalogError: String?,
    onSend: (String) -> Unit,
    onConfirm: (turnId: String, approved: List<Int>) -> Unit,
    onCancelPlan: (turnId: String) -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onLoadCatalog: () -> Unit,
    onSelectModel: (provider: String?, model: String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Pair<String, List<Int>>?>(null) }
    val listState = rememberLazyListState()

    // Follow the reply as it grows. Keyed on the last turn's length as well as the count,
    // because a streaming reply is one item getting longer rather than new items arriving.
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length, chat.lastOrNull()?.plan) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ModelBar(
            currentModel = catalog?.currentModel,
            streaming = streaming,
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
            EmptyChat(modifier = Modifier
                .weight(1f)
                .fillMaxWidth())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(chat, key = { it.id }) { turn ->
                    Turn(
                        turn = turn,
                        onRun = { approved ->
                            val destructive = turn.plan?.actions
                                ?.any { it.destructive && it.index in approved } == true
                            if (destructive) confirming = turn.id to approved
                            else onConfirm(turn.id, approved)
                        },
                        onCancel = { onCancelPlan(turn.id) },
                    )
                }
            }
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
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Clear")
                }
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
                placeholder = { Text("Ask, or say what to do") },
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
            // **One button.** The PC answers and works out whether the same sentence is
            // also something it can do; anything it finds arrives as a card with its own
            // buttons, and nothing runs until one of those is pressed. There is no longer
            // a guess for this screen to make about which kind of message this was.
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

    // The second confirm on the two power modes that lose unsaved work — the same rule the
    // TV remote's power menu already follows, for the same reason: a mis-tap here costs
    // whatever was open on a machine in another room (§7).
    confirming?.let { (turnId, approved) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Shut down or restart the PC?") },
            text = { Text("Anything unsaved on the PC will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onConfirm(turnId, approved)
                }) { Text("Do it") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
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

/** §11 rule 2: an empty screen states the state. What this is, and the one thing worth
 *  knowing before typing into it — that it can act on the PC, and that it will ask. */
@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("Ask, or say what to do", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Questions get an answer. \"Pause the music\", \"lock the PC\", \"press ctrl+s\" get "
                + "buttons — nothing happens on the PC until you press one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One turn: the bubble, and — on an assistant turn that also proposed something — the plan
 * card under it.
 *
 * They are drawn together because they answer one question. A card that arrived detached
 * from the sentence it belongs to would read as a second, unrelated event.
 */
@Composable
private fun Turn(turn: ChatTurn, onRun: (List<Int>) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Bubble(turn)

        // A local model deciding takes tens of seconds. Without this the gap between a
        // finished answer and a card appearing under it looks like nothing happening.
        AnimatedVisibility(visible = turn.deciding) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Working out what to do on the PC…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        turn.plan?.let {
            Spacer(Modifier.height(8.dp))
            PlanCard(plan = it, onRun = onRun, onCancel = onCancel)
        }
    }
}

/**
 * The plan, inline in the transcript rather than in a dialog.
 *
 * **This is what makes it an agent rather than a chatbot that occasionally raises a
 * sheet.** The proposal sits in the conversation it came from, each action is a row you
 * can untick, the primary button says what it will actually do, and after it runs the card
 * stays exactly where it was saying what happened. Nothing auto-executes, approval is
 * still per action, and the destructive modes still take a second confirmation (§7).
 */
@Composable
private fun PlanCard(plan: AiPlan, onRun: (List<Int>) -> Unit, onCancel: () -> Unit) {
    // Everything starts ticked: the model was asked to do this, and a card that starts
    // empty makes the common case — "yes, all of that" — the fiddly one.
    val approved = remember(plan) {
        mutableStateListOf<Int>().apply { if (plan.pending) addAll(plan.actions.map { it.index }) }
    }

    val accent = when (plan.state) {
        AiPlan.PENDING -> MaterialTheme.colorScheme.primary
        AiPlan.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, accent, MaterialTheme.shapes.extraLarge)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (plan.state == AiPlan.RAN) Icons.Filled.Check else Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (plan.state == AiPlan.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when (plan.state) {
                    AiPlan.PENDING -> if (plan.actions.size == 1) "Do this on the PC?" else "Do these on the PC?"
                    AiPlan.RAN -> "Done on the PC"
                    AiPlan.CANCELLED -> "Not run"
                    AiPlan.EXPIRED -> "This was never run"
                    else -> "Couldn't work out an action"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // The model's own reasoning, which is the only thing that explains *why* these
        // actions and not others. Its error takes the same slot when there is one.
        val detail = plan.error ?: plan.thought
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (plan.pending) {
            Spacer(Modifier.height(4.dp))
            plan.actions.forEach { action ->
                ActionRow(
                    action = action,
                    ticked = action.index in approved,
                    onToggle = {
                        if (action.index in approved) approved.remove(action.index)
                        else approved.add(action.index)
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The primary button says what pressing it does. A one-action plan is
                // approved by pressing "Mute" or "Shut down", not by pressing "Run" beside
                // a sentence that already said it — and the word comes from the PC, which
                // is the side that knows what the action presses.
                val only = approved.singleOrNull()?.let { index -> plan.actions.firstOrNull { it.index == index } }
                Button(
                    onClick = { onRun(approved.toList()) },
                    enabled = approved.isNotEmpty(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(only?.verb ?: "Run")
                }
                TextButton(onClick = onCancel) { Text("Not now") }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            // Cancelled and expired have no results, so the actions themselves are the
            // record of what was proposed and never done.
            if (plan.results.isNotEmpty()) {
                plan.results.forEach { result -> ResultRow(ok = result.ok, text = result.detail) }
            } else {
                plan.actions.forEach { action -> ResultRow(ok = null, text = action.summary) }
            }
        }
    }
}

/** One proposed action, with the icon for what it touches — the same recognition rule
 *  §11 applies to the Files list: a column of identical rows makes every row look the
 *  same, and "media key" and "shut down" should not need reading to be told apart. */
@Composable
private fun ActionRow(action: PlanAction, ticked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = iconForAction(action.actionId),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (action.destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.size(10.dp))
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

/** What one action did. [ok] is null for an action that was never attempted. */
@Composable
private fun ResultRow(ok: Boolean?, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (ok) {
                true -> Icons.Filled.Check
                false -> Icons.Filled.Close
                null -> Icons.Filled.Stop
            },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Derived from the action id the PC sent, not passed alongside it, so an action added
 *  later cannot ship with an icon claiming something the PC isn't doing. */
private fun iconForAction(actionId: String): ImageVector = when (actionId) {
    "media_control" -> Icons.Filled.MusicNote
    "press_keys" -> Icons.Filled.Keyboard
    "type_text" -> Icons.Filled.TextFields
    "cast_url" -> Icons.Filled.Cast
    "player_transport" -> Icons.Filled.PlayArrow
    "power" -> Icons.Filled.PowerSettingsNew
    else -> Icons.Filled.AutoAwesome
}

/** The chip that opens the model picker. Sits above the transcript rather than in a
 *  settings screen, because which model answers is a thing this app's other "chips, not
 *  a sheet" surfaces (the mirror's monitor/quality row) already treat as something you
 *  change *while looking at the result* — except here the result is the whole
 *  conversation, so a sheet (below) rather than an inline chip row is the right size for
 *  "browse every provider and model", not just "flip between two or three presets". */
@Composable
private fun ModelBar(currentModel: String?, streaming: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextButton(onClick = onClick) {
            // The only place this screen says "generating" without a sentence: a reply
            // in flight lights the model glyph the same way a live HUD reading does,
            // and it's dark again the instant the stream ends.
            val glow = if (streaming) {
                Modifier.accentGlow(MaterialTheme.colorScheme.primary, CircleShape, 6.dp)
            } else {
                Modifier
            }
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp).then(glow),
            )
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

/** Every provider/model the phone could switch the assistant to, grouped by provider.
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
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Try again")
                }
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
    val shape = MaterialTheme.shapes.extraLarge
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
                turn.text.ifEmpty { if (turn.error != null) "—" else "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (turn.fromUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            // Why it stopped, under whatever did arrive. An error is the turn's own
            // failure — the backend went away, the model was refused — and belongs on the
            // turn it happened to rather than in a banner over the whole screen.
            turn.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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
