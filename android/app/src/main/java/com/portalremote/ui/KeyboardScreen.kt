package com.portalremote.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.KeyboardControlKey
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.KeyboardOptionKey
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalremote.net.Protocol
import com.portalremote.ui.theme.HapticPress
import com.portalremote.ui.theme.PortalRemoteTheme
import org.json.JSONObject

/** Non-empty placeholder so the field always has something for backspace to delete. */
private const val SENTINEL = " "

/**
 * The Keyboard tab: [KeyboardPanel] in a column that scrolls, because the IME takes
 * half the screen the moment this tab is opened — which is always.
 */
@Composable
fun KeyboardScreen(send: (JSONObject) -> Unit) {
    KeyboardPanel(
        send = send,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

/** The two ways to type: through the system keyboard, or on a drawn PC keyboard. */
private enum class KeyboardLayout { TYPE, KEYS }

/**
 * The capture field, the quick phrases, the key grid and the arrows — everything the
 * Keyboard tab shows, as one block, so the mirror can float the same thing over its
 * picture (see ScreenScreen's FloatingKeyboard) rather than growing a second, lesser
 * keyboard of its own.
 *
 * Ctrl, Alt and Shift here are real keys, not one-shot combos: pressing one sends
 * key-down and releasing it sends key-up, the way a PC keyboard does — see
 * [ModifierKeys] for how a quick tap latches one for the next key.
 *
 * Two layouts, switched from a key on each: the system keyboard behind a capture field,
 * for sentences; and [PcKeyboard], every key a PC has, for everything that is not a
 * sentence — a shell, a shortcut, an editor. Takes the raw [send] rather than one
 * callback per message type because the drawn keyboard builds five kinds of message
 * and threading each one through the shell buys nothing.
 */
@Composable
fun KeyboardPanel(
    send: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
    /** False hides the key grid and the arrows, leaving the field and the phrases —
     *  the mirror's compact default, where the grid would cover the picture. The PC
     *  keyboard layout ignores it: there the keys are the point. */
    showKeys: Boolean = true,
    /** Height of the PC keyboard's caps — see [PcKeyboard]. */
    keyHeight: Dp = 48.dp,
) {
    val latestSend by rememberUpdatedState(send)
    val onText: (String) -> Unit = { latestSend(Protocol.text(it)) }
    val onTap: (String) -> Unit = { latestSend(Protocol.tap(it)) }
    val onCombo: (List<String>) -> Unit = { latestSend(Protocol.combo(*it.toTypedArray())) }
    // Every key below the field moves the PC's caret without the field knowing: tap the
    // grid's Backspace and the PC loses a character the buffer still thinks is there, so
    // the *next* backspace deletes the wrong one; the arrows are worse, since they move
    // the caret away from the text the buffer is diffing against entirely. Bumping this
    // empties the buffer, which is the only honest thing it can do — it can no longer
    // know what the PC holds.
    var caretMoved by remember { mutableIntStateOf(0) }
    val mods = remember { ModifierKeys { key, down -> latestSend(Protocol.key(key, down)) } }
    // Survives rotation, not relaunch: the layout is a per-task choice — a shell wants
    // the keys, a chat wants the IME — not a setting.
    var layout by rememberSaveable { mutableStateOf(KeyboardLayout.TYPE) }
    // Leaving with Ctrl still down on the PC would turn every later click and keystroke
    // from the trackpad into a Ctrl-something — with nothing on the phone saying so.
    DisposableEffect(Unit) { onDispose { mods.releaseAll() } }
    LaunchedEffect(showKeys, layout) { if (!showKeys || layout == KeyboardLayout.KEYS) mods.releaseAll() }

    // Bottom-anchored when there is room — the Keyboard tab gives this column the whole
    // screen's height — so the keys sit where the thumb is, not under the title bar.
    val stacked = Arrangement.spacedBy(8.dp, Alignment.Bottom)

    if (layout == KeyboardLayout.KEYS) {
        // What has been typed on the drawn keyboard since the caret last moved, so the
        // eyes can stay on the phone. Kept from the messages themselves rather than a
        // second channel out of PcKeyboard: every keystroke passes through here anyway.
        var preview by rememberSaveable { mutableStateOf("") }
        val watched: (JSONObject) -> Unit = { msg ->
            when (msg.optString("t")) {
                "text" -> preview += msg.optString("s")
                "tap" -> preview = when (msg.optString("key")) {
                    "backspace" -> preview.dropLastCodePoint()
                    "space" -> "$preview "
                    else -> ""   // Enter, arrows, Esc, a chord by VK: the caret is elsewhere now
                }
                "combo" -> preview = ""
            }
            send(msg)
        }
        Column(modifier = modifier, verticalArrangement = stacked) {
            QuickPhraseRow(onSend = { phrase -> mods.releaseAll(); onText(phrase); onTap("enter"); preview = "" })
            TypedPreview(
                text = preview,
                onClear = { count -> repeat(count) { onTap("backspace") }; preview = "" },
            )
            PcKeyboard(mods = mods, send = watched, onIme = { layout = KeyboardLayout.TYPE }, keyHeight = keyHeight)
        }
        return
    }

    val external: (String) -> Unit = { key -> caretMoved++; onTap(key); mods.keyed() }
    // A modifier already down on the PC is not pressed again: the combo would release
    // it on the way out while the phone still shows it held.
    val combo: (List<String>) -> Unit = { keys ->
        caretMoved++; onCombo(keys.filterNot { it in mods.down }); mods.keyed()
    }
    val typed: (String) -> Unit = { s ->
        if (mods.down.isEmpty()) {
            onText(s)
        } else {
            // A letter under Ctrl has to arrive as the *key*, not the character: `text`
            // types through KEYEVENTF_UNICODE, which apps read as a character typed and
            // never as the accelerator Ctrl+C is. Code points, not chars, so an emoji is
            // not split into two lone surrogates; and the latch is spent on the first
            // key, so a multi-character commit does not get all of it — a held
            // modifier still covers the lot, which is what holding means.
            var cp = 0
            while (cp < s.length) {
                val code = s.codePointAt(cp)
                val ch = String(Character.toChars(code))
                val name = keyNameFor(ch)
                if (name != null && mods.down.isNotEmpty()) onTap(name) else onText(ch)
                mods.keyed()
                cp += ch.length
            }
            caretMoved++
        }
        mods.keyed()
    }

    Column(modifier = modifier, verticalArrangement = stacked) {
        KeyCaptureField(
            onText = typed,
            // Not `external`: the field's own backspace diff is already in step with the
            // buffer and must not clear it mid-word — unless a modifier is down, when
            // that backspace was Ctrl+Backspace and took a word the buffer never saw.
            // The IME's Enter comes through here too, so Ctrl+Enter is a latched Ctrl
            // and the return key.
            onTap = { key -> onTap(key); if (mods.down.isNotEmpty()) caretMoved++; mods.keyed() },
            resetKey = caretMoved,
            modifier = Modifier.fillMaxWidth(),
        )
        // Above the keys rather than below: these are the fastest path to a sent line,
        // so they sit where the thumb already is after reaching for the field. A latched
        // modifier is let go first: Chromium drops typed text while Ctrl is down.
        QuickPhraseRow(onSend = { phrase -> mods.releaseAll(); onText(phrase); external("enter") })
        if (showKeys) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                KeyCap("Esc", Icons.Filled.Cancel, Modifier.weight(1f)) { external("esc") }
                KeyCap("Tab", Icons.AutoMirrored.Filled.KeyboardTab, Modifier.weight(1f)) { external("tab") }
                KeyCap("Space", Icons.Filled.SpaceBar, Modifier.weight(1f)) { external("space") }
                KeyCap("Enter", Icons.AutoMirrored.Filled.KeyboardReturn, Modifier.weight(1f)) { external("enter") }
                KeyCap("Backspace", Icons.AutoMirrored.Filled.Backspace, Modifier.weight(1f)) { external("backspace") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                ModifierKey("Ctrl", Icons.Filled.KeyboardControlKey, "ctrl", mods, Modifier.weight(1f))
                ModifierKey("Alt", Icons.Filled.KeyboardOptionKey, "alt", mods, Modifier.weight(1f))
                ModifierKey("Shift", Icons.Filled.KeyboardCapslock, "shift", mods, Modifier.weight(1f))
                // A tap, not a modifier: Win released on its own is the Start menu, which
                // a latch would hold back until the second tap.
                KeyCap("Win", Icons.Filled.Window, modifier = Modifier.weight(1f)) { external("win") }
                KeyCap("Keys", Icons.Filled.KeyboardAlt, modifier = Modifier.weight(1f)) {
                    layout = KeyboardLayout.KEYS
                }
            }
            // The one-tap chords stay even though Ctrl is a key now: Ctrl+C is the
            // most-tapped thing on this screen, and two taps for it is one too many.
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                KeyCap("Ctrl+C", Icons.Filled.ContentCopy, Modifier.weight(1f)) { combo(listOf("ctrl", "c")) }
                KeyCap("Ctrl+V", Icons.Filled.ContentPaste, Modifier.weight(1f)) { combo(listOf("ctrl", "v")) }
                KeyCap("Ctrl+Z", Icons.AutoMirrored.Filled.Undo, Modifier.weight(1f)) { combo(listOf("ctrl", "z")) }
                KeyCap("Alt+Tab", Icons.Filled.SwapHoriz, Modifier.weight(1f)) { combo(listOf("alt", "tab")) }
            }
            // One row, not a cross: a cross spends two rows on four keys, and this
            // column is fighting the IME for every one of them.
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", Modifier.weight(1f)) { external("left") }
                ArrowKey(Icons.Filled.KeyboardArrowUp, "Up", Modifier.weight(1f)) { external("up") }
                ArrowKey(Icons.Filled.KeyboardArrowDown, "Down", Modifier.weight(1f)) { external("down") }
                ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", Modifier.weight(1f)) { external("right") }
            }
        }
    }
}

/**
 * Ctrl, Alt and Shift the way a PC keyboard has them. Pressing one sends key-down at
 * once and holding it keeps it down, so a second finger can tap the key it modifies. A
 * quick tap with nothing pressed in between *latches* it instead — held down on the PC
 * until the next key, typed character or combo goes out, or until it is tapped again.
 * Two latched together (Ctrl, then Shift) both wait for the same next key.
 */
internal class ModifierKeys(private val send: (key: String, down: Boolean) -> Unit) {
    /** Key → latched (true) or physically held (false). Compose state, so the key
     *  caps can tint from it. */
    val down = mutableStateMapOf<String, Boolean>()
    /** Held keys that have modified something since they went down — per key, since
     *  pressing Shift must not forget that Ctrl already fired. */
    private val used = mutableSetOf<String>()
    private var cancelled: String? = null

    /** Pointer down on the cap. */
    fun press(key: String) {
        if (key in down) {
            // A second tap on a latched key lets it go; the release that follows this
            // press must then do nothing.
            release(key)
            cancelled = key
            return
        }
        used -= key
        send(key, true)
        down[key] = false
    }

    /** Pointer up on the cap. */
    fun lift(key: String) {
        if (cancelled == key) { cancelled = null; return }
        if (down[key] != false) return
        if (key in used) release(key) else down[key] = true
    }

    /** The gesture ended somewhere else — a scroll took it, the thumb slid off. A held
     *  key lets go; it never latches, or a flick that started on Ctrl would leave Ctrl
     *  down on the PC. */
    fun cancel(key: String) {
        if (cancelled == key) { cancelled = null; return }
        if (down[key] == false) release(key)
    }

    /** A screen reader's activate: toggle the latch, since it has no press and release. */
    fun toggle(key: String) {
        if (key in down) release(key) else { send(key, true); down[key] = true }
    }

    /** Something else went out. Held keys stay down under the finger; latched ones let go. */
    fun keyed() {
        used += down.filterValues { !it }.keys
        down.filterValues { it }.keys.forEach { release(it) }
    }

    fun releaseAll() {
        down.keys.toList().forEach { release(it) }
        used.clear()
        cancelled = null
    }

    private fun release(key: String) {
        send(key, false)
        down.remove(key)
    }
}

/** Gap between key caps. 6dp rather than the 8dp rhythm: five caps across a phone are
 *  ~64dp each, and every 2dp of gap comes straight out of the label. */
private val KEY_GAP = 6.dp

/**
 * Icon over label, so five fit across a phone: side by side, "Backspace" plus its
 * glyph is a 100dp key and the grid was three ragged rows. The glyph is the one a
 * physical keyboard prints (⇥, ↵, ⌫), or for a chord the icon of what it *does*; the
 * label stays so the shortcut is still learnable from here.
 */
@Composable
private fun KeyCap(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    KeyButton(
        onClick = onClick,
        active = active,
        contentPadding = PaddingValues(4.dp),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A cap whose keystroke is split into press and release — see [ModifierKeys]. The
 * pointer handler sits *outside* the button's own click gesture (which stays a no-op),
 * the same arrangement as the trackpad's hold-to-click buttons; the semantics `onClick`
 * is what a screen reader gets instead, since it never produces a press.
 */
@Composable
private fun ModifierKey(
    label: String,
    icon: ImageVector,
    key: String,
    mods: ModifierKeys,
    modifier: Modifier = Modifier,
) {
    KeyCap(
        label = label,
        icon = icon,
        active = key in mods.down,
        modifier = modifier
            .semantics { onClick(label = label) { mods.toggle(key); true } }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    mods.press(key)
                    if (waitForUpOrCancellation() == null) mods.cancel(key) else mods.lift(key)
                }
            },
        onClick = {},
    )
}

/** [name] is the only thing a screen reader has to go on: these four keys have no
 *  label, and four buttons announced as "button" are four keys nobody can use. */
@Composable
private fun ArrowKey(icon: ImageVector, name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    KeyButton(onClick = onClick, contentPadding = PaddingValues(4.dp), modifier = modifier) {
        Icon(icon, contentDescription = name)
    }
}

/**
 * The soft-keyboard capture buffer, shared by [KeyboardPanel]'s two homes. Not a
 * document: it resets to [SENTINEL] after every keystroke so the cursor stays put and
 * insert/delete can be inferred from the length change, which is what makes backspace
 * work without a real text buffer to delete from.
 *
 * Grabs focus (and the soft keyboard) as soon as it composes, and hides the keyboard
 * again when it leaves — a field that is gone with the IME still up over the trackpad
 * is the thing the trailing button below exists to prevent.
 */
@Composable
private fun KeyCaptureField(
    onText: (String) -> Unit,
    onTap: (key: String) -> Unit,
    /** Bump this whenever something *else* has moved the PC's caret — a special key, an
     *  arrow, a quick phrase. The buffer below is a claim about what the PC currently
     *  holds, and once that claim is false the only safe thing is to start over. */
    resetKey: Any,
    modifier: Modifier = Modifier,
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reset() {
        field = TextFieldValue(SENTINEL, selection = TextRange(SENTINEL.length))
        sent[0] = SENTINEL
    }

    // What the PC holds of this run of typing, in characters a backspace removes — code
    // points, since one backspace takes a whole emoji. Zero once anything else has
    // moved the caret ([resetKey]), which is also when the clear button goes away.
    val typed = field.text.drop(SENTINEL.length)
    val typedCount = typed.codePointCount(0, typed.length)

    /** Takes back everything typed since the last reset: one backspace per character
     *  the buffer says the PC has. Nothing cleverer — Ctrl+A would select a whole
     *  document in an editor, and the buffer is the only thing here that knows how far
     *  this run of typing reaches. */
    fun clearTyped() {
        repeat(typedCount) { onTap("backspace") }
        reset()
    }

    OutlinedTextField(
        value = field,
        onValueChange = { new ->
            val previous = sent[0]
            // Diff on the common prefix rather than on length alone, so an autocorrect
            // that rewrites the middle of a word replays as backspaces + retype
            // instead of silently sending the wrong tail.
            val shared = previous.commonPrefixWith(new.text).length
            val removed = previous.substring(shared)
            repeat(removed.codePointCount(0, removed.length)) { onTap("backspace") }
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
        // "Type here", not "Tap to type": the field takes focus as it appears, so the
        // tap has already happened by the time anyone reads this.
        label = { Text("Type here") },
        // The one field in the app that types onto the *other* machine. The glyph is
        // what separates it at a glance from the browser's address bar and Share's
        // composer, which look the same and go somewhere else entirely.
        leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
        // Two things a thumb needs while typing and nothing else on screen offers: take
        // back this run of typing, and put the keyboard away. The IME's own return key
        // is Enter (below), and the back gesture is the only other way out — which
        // nothing on screen says. Clear only shows while there is something to clear.
        trailingIcon = if (focused || typedCount > 0) {
            {
                Row {
                    if (typedCount > 0) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear typed text")
                        }
                    }
                    if (focused) {
                        IconButton(onClick = { focusManager.clearFocus(); keyboard?.hide() }) {
                            Icon(Icons.Filled.KeyboardHide, contentDescription = "Hide keyboard")
                        }
                    }
                }
            }
        } else {
            null
        },
        // Grows with the text instead of scrolling sideways in one line; past
        // maxLines the field scrolls internally rather than growing forever.
        minLines = 1,
        maxLines = 6,
        // The IME's own return key sends Enter to the PC. Without this it would be a
        // "done" button that closes the keyboard and types nothing, which is the one
        // key people reach for most after typing a line.
        // Autocorrect off, and no automatic capital: this field types onto *another
        // machine*, where `cd /usr/bin` rewritten to `/user/bin` is a wrong command the
        // diff below faithfully replays as backspaces and a retype, with nothing on
        // either screen admitting what changed it.
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Send,
        ),
        // Enter only. The field keeps focus and the IME stays up: this is a keystroke
        // pipe, not a form, and a line sent is almost always followed by another one.
        // Bouncing to the trackpad here — which is what used to happen — meant
        // reopening the keyboard by hand for every "yes" and "continue".
        // Enter moves the PC's caret to a fresh line, so whatever the buffer was holding
        // no longer describes anything there — same reason as [resetKey] below.
        keyboardActions = KeyboardActions(onSend = { onTap("enter"); reset() }),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(resetKey) { reset() }
    DisposableEffect(keyboard) { onDispose { keyboard?.hide() } }

    if (confirmClear) {
        ClearTypedDialog(
            count = typedCount,
            onConfirm = { clearTyped() },
            // The dialog took focus and the IME with it, whichever way it closes; typing
            // on is what follows, so hand both straight back.
            onClose = { confirmClear = false; focusRequester.requestFocus() },
        )
    }
}

/** Drops the last code point, not the last char — an emoji is one backspace. */
private fun String.dropLastCodePoint(): String =
    if (isEmpty()) this else substring(0, offsetByCodePoints(length, -1))

/** The key name the server knows [ch] by, for the chord path; null for anything that
 *  has to go as text. */
private fun keyNameFor(ch: String): String? {
    val c = ch.singleOrNull() ?: return null
    return when {
        c in 'a'..'z' || c in '0'..'9' -> c.toString()
        c in 'A'..'Z' -> c.lowercaseChar().toString()
        c == ' ' -> "space"
        c == '\t' -> "tab"
        c == '\n' -> "enter"
        else -> null
    }
}

/**
 * Guarded, because it is the one control on this screen that deletes on the PC — and
 * it deletes wherever the PC's caret *is*, which is where the buffer last saw it only
 * if nothing over there has been clicked since. The count is in the prompt so the user
 * can check it against what they see.
 */
@Composable
private fun ClearTypedDialog(count: Int, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Clear what you typed?") },
        text = {
            Text(
                "Sends $count backspace${if (count == 1) "" else "s"} to the PC to remove the " +
                    "text typed here. Whatever is at the PC's cursor now is what actually " +
                    "gets deleted.",
            )
        },
        confirmButton = { TextButton(onClick = { onClose(); onConfirm() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * What the drawn keyboard has typed since the caret last moved — the PC keyboard's
 * answer to the capture field, so the eyes can stay on the phone. Scrolls to keep the
 * end in view; the clear button takes it back with one backspace per character, behind
 * the same confirmation as the field's.
 */
@Composable
private fun TypedPreview(text: String, onClear: (count: Int) -> Unit) {
    val scroll = rememberScrollState()
    var confirm by remember { mutableStateOf(false) }
    val count = text.codePointCount(0, text.length)
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(PortalRemoteTheme.extendedColors.surfaceMuted)
            .border(1.dp, PortalRemoteTheme.extendedColors.borderStrong, MaterialTheme.shapes.small)
            .padding(start = 12.dp),
    ) {
        Text(
            text = if (text.isEmpty()) "What you type shows here" else "$text\u258F",
            style = MaterialTheme.typography.bodyLarge,
            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f).horizontalScroll(scroll),
        )
        if (count > 0) {
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear typed text")
            }
        }
    }
    if (confirm) ClearTypedDialog(count = count, onConfirm = { onClear(count) }, onClose = { confirm = false })
}

/**
 * A key that tints instantly on press — the press interaction fires on pointer-down,
 * so it still *reads* at physical-key latency (docs/design-system.md §7) — but that
 * only drives the tint, with no animation: §6 calls for zero decoration on anything
 * that fires this often. [active] holds the same tint on a key that is currently down
 * on the PC — a latched modifier.
 *
 * The keystroke itself goes out on release, through the button's own `onClick`. It
 * used to fire on pointer-down, which is wrong for two reasons that both cost the user
 * something irreversible: this column scrolls now, so a thumb landing on `continue` and
 * flicking to reach the arrow row sent `continue` + Enter to the PC before the scroll
 * began; and an empty `onClick` is a key that does nothing at all under TalkBack, which
 * announces it as a button and then sends no keystroke. A key that speaks 60ms later is
 * a trade worth making against a key that speaks when nobody asked it to.
 *
 * `defaultMinSize` guarantees the §9 48dp touch target explicitly — an arrow's 4dp
 * content padding around a 24dp icon would otherwise land under that. Applied *after*
 * [modifier], so a caller that sizes the key smaller on purpose (the quick phrases) wins.
 */
@Composable
internal fun KeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    active: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Same source as the tint above, so the buzz and the highlight land together.
    HapticPress(interactionSource)
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        // A key is a key *cap*, not a pill: Material's default full rounding made Esc,
        // Tab and Ctrl+C read as chips you filter a list with rather than keys you
        // strike. The chamfer is §5's, so a key face is cut from the same 45° as every
        // panel. The arrow row draws through here too and is chamfered with the rest,
        // which is right — arrow keys are square caps on a real keyboard. The round
        // buttons in this app are the TV remote's D-pad, which imitates hardware that
        // genuinely is round.
        shape = MaterialTheme.shapes.small,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        // A key has a face. Transparent-on-transparent left every key in the app defined
        // by its 1px outline alone — which in light mode was a near-white hairline on
        // white, i.e. nothing — so the fill is `surface-muted` and the outline is the
        // 3:1 `borderStrong` Material takes from `outline` (§3). The press tint sits on
        // top of the face rather than replacing it, so a held key reads as lit, not as
        // suddenly existing.
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (pressed || active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                PortalRemoteTheme.extendedColors.surfaceMuted
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}
