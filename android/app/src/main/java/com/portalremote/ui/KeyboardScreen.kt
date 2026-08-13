package com.portalremote.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardTab
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Window
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.portalremote.ui.theme.HapticPress
import com.portalremote.ui.theme.PortalRemoteTheme

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
    /** Fired after Enter goes out — the shell uses this to hand focus back to the
     *  trackpad, since sending a line is usually the "I'm done typing" signal. */
    onSubmit: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KeyCaptureField(
            onText = onText,
            onTap = onTap,
            onSubmit = onSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
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
    /** Grabs focus (and the soft keyboard) as soon as this composes. Off for the
     *  trackpad's own type bar, which exists to hand focus *away* to the Keyboard
     *  tab rather than to open the IME in place. */
    autoFocus: Boolean = true,
    /** Fired after Enter goes out. */
    onSubmit: () -> Unit = {},
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
            // empty, so the next backspace still has something to delete; otherwise it
            // keeps growing — the field is a scrollable text area now, not a one-line
            // buffer that has to be trimmed to stay legible.
            if (new.text.isEmpty()) {
                field = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
                sent[0] = SENTINEL
            } else {
                field = new
                sent[0] = new.text
            }
        },
        label = { Text(label) },
        // The one field in the app that types onto the *other* machine. The glyph is
        // what separates it at a glance from the browser's address bar and Share's
        // composer, which look the same and go somewhere else entirely.
        leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
        // Grows with the text instead of scrolling sideways in one line; past
        // maxLines the field scrolls internally rather than growing forever.
        minLines = 1,
        maxLines = 6,
        // The IME's own return key sends Enter to the PC. Without this it would be a
        // "done" button that closes the keyboard and types nothing, which is the one
        // key people reach for most after typing a line.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onTap("enter"); onSubmit() }),
        modifier = modifier.focusRequester(focusRequester),
    )

    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
}

private data class SpecialKey(val label: String, val icon: ImageVector, val send: () -> Unit)

/**
 * Ten keys, each with the glyph a physical keyboard (or the app it drives) already
 * prints on it — ⇥, ↵, ⌫ — and for the three combos, the icon of what they *do*
 * rather than of the keys they press. `Ctrl+C` is the most-tapped thing on this row
 * and "copy" is a shape everyone knows; the label stays so the shortcut is still
 * learnable from here.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
private fun SpecialKeyRow(onTap: (String) -> Unit, onCombo: (List<String>) -> Unit) {
    val keys = remember {
        listOf(
            SpecialKey("Esc", Icons.Filled.Cancel) { onTap("esc") },
            SpecialKey("Tab", Icons.Filled.KeyboardTab) { onTap("tab") },
            SpecialKey("Enter", Icons.AutoMirrored.Filled.KeyboardReturn) { onTap("enter") },
            SpecialKey("Space", Icons.Filled.SpaceBar) { onTap("space") },
            SpecialKey("Backspace", Icons.AutoMirrored.Filled.Backspace) { onTap("backspace") },
            SpecialKey("Win", Icons.Filled.Window) { onTap("win") },
            SpecialKey("Alt+Tab", Icons.Filled.SwapHoriz) { onCombo(listOf("alt", "tab")) },
            SpecialKey("Ctrl+C", Icons.Filled.ContentCopy) { onCombo(listOf("ctrl", "c")) },
            SpecialKey("Ctrl+V", Icons.Filled.ContentPaste) { onCombo(listOf("ctrl", "v")) },
            SpecialKey("Ctrl+Z", Icons.AutoMirrored.Filled.Undo) { onCombo(listOf("ctrl", "z")) },
        )
    }
    // Wraps rather than scrolls. As a LazyRow about half of these were off the right
    // edge with nothing on screen saying so — no gradient, no guaranteed half-item —
    // so Ctrl+C/V/Z simply didn't exist for anyone who never dragged the row. Laying
    // them out on two lines costs vertical space this screen has and removes the
    // discoverability problem instead of signposting it.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { key ->
            // 12dp rather than 16dp of side padding: the glyph costs each key ~24dp of
            // width, and this row has to keep fitting on two lines above the arrow pad.
            KeyButton(onClick = key.send, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(key.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(key.label, modifier = Modifier.padding(start = 6.dp))
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
internal fun KeyButton(
    onClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Same source as the tint above, so the buzz and the highlight land together.
    HapticPress(interactionSource)
    val latestOnClick = rememberUpdatedState(onClick)
    OutlinedButton(
        // The real action fires on press below — a key that only speaks on release
        // reads a beat behind a physical one, felt most on the keys people tap fastest
        // (backspace, arrows). Left as a no-op so the button's own clickable still
        // drives `interactionSource` for the press tint above.
        onClick = {},
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        // A key is a key *cap*, not a pill: Material's default full rounding made Esc,
        // Tab and Ctrl+C read as chips you filter a list with rather than keys you
        // strike. The chamfer is §5's, so a key face is cut from the same 45° as every
        // panel. The arrow cluster below draws through here too and is chamfered with
        // the rest, which is right — arrow keys are square caps on a real keyboard. The
        // round buttons in this app are the TV remote's D-pad, which imitates hardware
        // that genuinely is round.
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    latestOnClick.value()
                }
            },
        // A key has a face. Transparent-on-transparent left every key in the app defined
        // by its 1px outline alone — which in light mode was a near-white hairline on
        // white, i.e. nothing — so the fill is `surface-muted` and the outline is the
        // 3:1 `borderStrong` Material takes from `outline` (§3). The press tint sits on
        // top of the face rather than replacing it, so a held key reads as lit, not as
        // suddenly existing.
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (pressed) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                PortalRemoteTheme.extendedColors.surfaceMuted
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}
