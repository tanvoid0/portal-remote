package com.portalremote.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.portalremote.data.Prefs
import com.portalremote.data.defaultQuickPhrases
import kotlinx.coroutines.launch

/**
 * One-tap phrases, each sent as text followed by Enter.
 *
 * The thing this screen is most used for is answering something already waiting on the
 * PC — a prompt, a chat, a confirmation — and those answers are a handful of words
 * typed over and over. Typing "continue" on glass, one letter at a time, to say a
 * thing the phone could have said in one tap is the single worst interaction in the
 * app; this is that tap.
 *
 * Reads and writes its own [Prefs] rather than taking the list as a parameter: the
 * two callers (the Keyboard tab and the mirror's inline typing bar) sit five and four
 * composables deep respectively, and threading a list plus a save callback down both
 * chains buys nothing — DataStore is already a process-wide singleton, so this is the
 * same store the ViewModel reads.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
fun QuickPhraseRow(
    /** The phrase, verbatim. The caller appends Enter — it is the caller that knows
     *  which message types text and which taps a key. */
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val scope = rememberCoroutineScope()
    val phrases by prefs.quickPhrases.collectAsState(initial = defaultQuickPhrases())
    var editing by remember { mutableStateOf(false) }

    // Wraps rather than scrolls, same reasoning as the key grid below it: a phrase
    // parked off the right edge is a phrase that doesn't exist. 40dp rather than the
    // grid's 48: these are words, not caps, and the row sits between the field and the
    // keys on a screen the IME has already taken half of.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        phrases.forEach { phrase ->
            KeyButton(
                onClick = { onSend(phrase) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text(phrase, style = MaterialTheme.typography.labelLarge)
            }
        }
        KeyButton(
            onClick = { editing = true },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit phrases", modifier = Modifier.size(18.dp))
        }
    }

    if (editing) {
        QuickPhraseEditor(
            phrases = phrases,
            onDismiss = { editing = false },
            onSave = { edited ->
                scope.launch { prefs.saveQuickPhrases(edited) }
                editing = false
            },
        )
    }
}

/**
 * The editor is the stored format: one phrase per line, in a text box. A per-row list
 * with add/remove/reorder buttons would be more app-like and strictly worse — adding
 * four phrases is four taps of "+" and four dialogs, against typing four lines.
 */
@Composable
private fun QuickPhraseEditor(
    phrases: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val original = remember { phrases.joinToString("\n") }
    var text by rememberSaveable { mutableStateOf(original) }
    AlertDialog(
        // Only an outside tap with nothing typed dismisses for free — an edit dropped
        // by a stray tap is a rewritten line lost for nothing.
        onDismissRequest = { if (text == original) onDismiss() },
        title = { Text("Quick phrases") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("One per line") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
