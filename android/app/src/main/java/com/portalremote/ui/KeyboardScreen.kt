package com.portalremote.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Non-empty placeholder so the field always has something for backspace to delete. */
private const val SENTINEL = " "

/** Length at which the capture buffer is trimmed back to [SENTINEL]. Only about
 *  keeping the visible field short — nothing depends on the text that came before. */
private const val MAX_CAPTURE_CHARS = 120

/**
 * Captures soft-keyboard input and special keys, forwarding each as a protocol
 * message. The visible text field is a capture buffer, not a document: it is
 * reset to [SENTINEL] after every keystroke so the cursor always stays put and
 * insert/delete can be inferred from the length change.
 */
@Composable
fun KeyboardScreen(
    onText: (String) -> Unit,
    onTap: (key: String) -> Unit,
    onCombo: (keys: List<String>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KeyCaptureField(onText = onText, onTap = onTap, modifier = Modifier.fillMaxWidth())
        SpecialKeyRow(onTap = onTap, onCombo = onCombo)
        ArrowPad(onTap = onTap)
    }
}

/**
 * The soft-keyboard capture buffer, shared by [KeyboardScreen] and the mirror's
 * inline keyboard. Not a document: it resets to [SENTINEL] after every keystroke so
 * the cursor stays put and insert/delete can be inferred from the length change,
 * which is what makes backspace work without a real text buffer to delete from.
 */
@Composable
fun KeyCaptureField(
    onText: (String) -> Unit,
    onTap: (key: String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Tap to type",
) {
    var field by remember {
        mutableStateOf(TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length)))
    }
    // Deliberately NOT Compose state: onValueChange can fire several times before a
    // recomposition lands, and diffing against a stale value is what used to drop and
    // duplicate characters under fast input (measured: "phase3-keyboard-works"
    // arriving at the PC as "paasase3kyybarrd-ok").
    val sent = remember { arrayOf(SENTINEL) }
    val focusRequester = remember { FocusRequester() }

    OutlinedTextField(
        value = field,
        onValueChange = { new ->
            val previous = sent[0]
            // Diff on the common prefix rather than on length alone, so an autocorrect
            // that rewrites the middle of a word replays as backspaces + retype
            // instead of silently sending the wrong tail.
            val shared = previous.commonPrefixWith(new.text).length
            repeat(previous.length - shared) { onTap("backspace") }
            if (new.text.length > shared) onText(new.text.substring(shared))

            // Let the IME keep its own buffer — forcing the value back every keystroke
            // is what desynchronised it. Only re-arm the sentinel once the field is
            // empty (so the next backspace still has something to delete) or the
            // buffer has grown long enough to be worth trimming.
            if (new.text.isEmpty() || new.text.length > MAX_CAPTURE_CHARS) {
                field = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
                sent[0] = SENTINEL
            } else {
                field = new
                sent[0] = new.text
            }
        },
        label = { Text(label) },
        singleLine = true,
        // The IME's own return key sends Enter to the PC. Without this it would be a
        // "done" button that closes the keyboard and types nothing, which is the one
        // key people reach for most after typing a line.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onTap("enter") }),
        modifier = modifier.focusRequester(focusRequester),
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private data class SpecialKey(val label: String, val send: () -> Unit)

@Composable
private fun SpecialKeyRow(onTap: (String) -> Unit, onCombo: (List<String>) -> Unit) {
    val keys = remember {
        listOf(
            SpecialKey("Esc") { onTap("esc") },
            SpecialKey("Tab") { onTap("tab") },
            SpecialKey("Enter") { onTap("enter") },
            SpecialKey("Space") { onTap("space") },
            SpecialKey("Backspace") { onTap("backspace") },
            SpecialKey("Win") { onTap("win") },
            SpecialKey("Alt+Tab") { onCombo(listOf("alt", "tab")) },
            SpecialKey("Ctrl+C") { onCombo(listOf("ctrl", "c")) },
            SpecialKey("Ctrl+V") { onCombo(listOf("ctrl", "v")) },
            SpecialKey("Ctrl+Z") { onCombo(listOf("ctrl", "z")) },
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(keys) { key ->
            KeyButton(onClick = key.send, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(key.label)
            }
        }
    }
}

@Composable
private fun ArrowPad(onTap: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onTap("left") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowButton(Icons.Filled.KeyboardArrowUp) { onTap("up") }
            ArrowButton(Icons.Filled.KeyboardArrowDown) { onTap("down") }
        }
        ArrowButton(Icons.AutoMirrored.Filled.KeyboardArrowRight) { onTap("right") }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, onClick: () -> Unit) {
    KeyButton(onClick = onClick, contentPadding = PaddingValues(4.dp)) {
        Icon(icon, contentDescription = null)
    }
}

/**
 * A key that tints instantly on press rather than waiting for the click gesture to
 * resolve, so it reads as close to physical key latency as a touchscreen allows —
 * see docs/design-system.md §7. The tint is driven straight off this button's own
 * [MutableInteractionSource] (press interaction fires on pointer-down), with no
 * animation: §6 calls for zero decoration on anything that fires this often.
 *
 * `defaultMinSize` guarantees the §9 48dp touch target explicitly — ArrowButton's
 * 4dp content padding around a 24dp icon would otherwise land under that.
 */
@Composable
private fun KeyButton(
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        ),
        content = content,
    )
}
