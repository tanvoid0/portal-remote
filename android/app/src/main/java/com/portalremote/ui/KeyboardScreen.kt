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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Non-empty placeholder so the field always has something for backspace to delete. */
private const val SENTINEL = " "

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
    var field by remember {
        mutableStateOf(TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length)))
    }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                when {
                    new.text.length > SENTINEL.length && new.text.startsWith(SENTINEL) ->
                        onText(new.text.removePrefix(SENTINEL))
                    new.text.length > field.text.length ->
                        // Autocorrect/predictive text replaced rather than appended; best
                        // effort is to send whatever is new at the end of the buffer.
                        onText(new.text.takeLast(new.text.length - field.text.length))
                    new.text.length < field.text.length ->
                        repeat((field.text.length - new.text.length).coerceAtLeast(1)) {
                            onTap("backspace")
                        }
                }
                field = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
            },
            label = { Text("Tap to type") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        SpecialKeyRow(onTap = onTap, onCombo = onCombo)
        ArrowPad(onTap = onTap)
    }
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
