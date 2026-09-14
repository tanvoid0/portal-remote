package com.portalremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalremote.net.Protocol
import com.portalremote.ui.theme.LocalHaptics
import com.portalremote.ui.theme.PortalRemoteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Hold-to-repeat timing, same as the TV remote's D-pad: Windows' own defaults. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

private val KEY_GAP = 3.dp

/**
 * One key of the drawn keyboard. [units] is its width relative to a plain key; every
 * row below adds up to the same total, so the caps line up column to column.
 * [fn] is what the Fn layer turns this key into — F1 for `1`, Home for ←, `\` for `/` —
 * the way a laptop's Fn row works, and the reason a 13-unit row can carry a full PC's
 * worth of keys.
 */
private sealed class PcKey(val units: Float, val fn: PcKey?) {
    /** A printing key. Goes out as text, or as its [vk] when a chord modifier is down. */
    class Char(val base: kotlin.Char, val shifted: kotlin.Char, val vk: Int, fn: PcKey? = null, units: Float = 1f) : PcKey(units, fn)

    /** A named key the server already knows — Enter, F5, pgup. */
    class Named(
        val label: String,
        val key: String,
        val icon: ImageVector? = null,
        /** What the cap prints when [label] will not fit in 25dp: ⇱ for Home. */
        val glyph: String? = null,
        /** Keeps firing while held: Backspace, Delete, the arrows, Space. */
        val repeat: Boolean = false,
        fn: PcKey? = null,
        units: Float = 1f,
    ) : PcKey(units, fn)

    /** Ctrl / Alt / Shift / Win — held or latched, see [ModifierKeys]. */
    class Mod(val label: String, val key: String, val icon: ImageVector? = null, units: Float = 1f) : PcKey(units, null)

    /** The Fn layer switch: one-shot, like the modifiers' tap-latch. */
    class Fn(units: Float = 1f) : PcKey(units, null)

    /** Hands over to the system keyboard — for a sentence, this layout is the slow way. */
    class Ime(units: Float = 1f) : PcKey(units, null)
}

private fun letter(c: Char) = PcKey.Char(c, c.uppercaseChar(), vk = 0x41 + (c - 'a'))
private fun digit(c: Char, shifted: Char, f: Int) =
    PcKey.Char(c, shifted, vk = c.code, fn = PcKey.Named("F$f", "f$f"))
private fun sym(base: Char, shifted: Char, vk: Int, fn: PcKey? = null) = PcKey.Char(base, shifted, vk, fn)

/**
 * US ANSI, with what does not fit on 13 units folded into the Shift and Fn layers: the
 * digit row is F1–F12 under Fn, the arrows are Home/End/PgUp/PgDn, Delete is Insert,
 * and the two keys with nowhere to go — `\|` and `` `~ `` — sit under Fn on `/` and `'`.
 * VK codes are the US layout's OEM keys; they only matter for chords, where Ctrl+/ has
 * to press the key the PC calls `/`.
 */
private val ROWS: List<List<PcKey>> = listOf(
    listOf(
        digit('1', '!', 1), digit('2', '@', 2), digit('3', '#', 3), digit('4', '$', 4), digit('5', '%', 5),
        digit('6', '^', 6), digit('7', '&', 7), digit('8', '*', 8), digit('9', '(', 9), digit('0', ')', 10),
        sym('-', '_', 0xBD, fn = PcKey.Named("F11", "f11")),
        sym('=', '+', 0xBB, fn = PcKey.Named("F12", "f12")),
        PcKey.Named("Backspace", "backspace", Icons.AutoMirrored.Filled.Backspace, repeat = true),
    ),
    listOf(
        PcKey.Named("Tab", "tab", Icons.AutoMirrored.Filled.KeyboardTab),
        letter('q'), letter('w'), letter('e'), letter('r'), letter('t'),
        letter('y'), letter('u'), letter('i'), letter('o'), letter('p'),
        sym('[', '{', 0xDB), sym(']', '}', 0xDD),
    ),
    listOf(
        letter('a'), letter('s'), letter('d'), letter('f'), letter('g'),
        letter('h'), letter('j'), letter('k'), letter('l'),
        sym(';', ':', 0xBA), sym('\'', '"', 0xDE, fn = sym('`', '~', 0xC0)),
        PcKey.Named("Enter", "enter", Icons.AutoMirrored.Filled.KeyboardReturn, units = 2f),
    ),
    listOf(
        PcKey.Mod("Shift", "shift", Icons.Filled.KeyboardCapslock),
        letter('z'), letter('x'), letter('c'), letter('v'), letter('b'), letter('n'), letter('m'),
        sym(',', '<', 0xBC), sym('.', '>', 0xBE), sym('/', '?', 0xBF, fn = sym('\\', '|', 0xDC)),
        PcKey.Named("Up", "up", Icons.Filled.KeyboardArrowUp, repeat = true, fn = PcKey.Named("PgUp", "pgup", glyph = "\u21DE")),
        PcKey.Named("Del", "delete", repeat = true, fn = PcKey.Named("Ins", "insert")),
    ),
    listOf(
        PcKey.Named("Esc", "esc"),
        // A shade wider than a letter: "Ctrl" is four glyphs of monospace in 25dp.
        PcKey.Mod("Ctrl", "ctrl", units = 1.2f),
        PcKey.Mod("Win", "win", units = 1.2f),
        PcKey.Mod("Alt", "alt", units = 1.2f),
        PcKey.Ime(),
        PcKey.Named("Space", "space", Icons.Filled.SpaceBar, repeat = true, units = 3.4f),
        PcKey.Fn(),
        PcKey.Named("Left", "left", Icons.AutoMirrored.Filled.KeyboardArrowLeft, repeat = true, fn = PcKey.Named("Home", "home", glyph = "\u21F1")),
        PcKey.Named("Down", "down", Icons.Filled.KeyboardArrowDown, repeat = true, fn = PcKey.Named("PgDn", "pgdn", glyph = "\u21DF")),
        PcKey.Named("Right", "right", Icons.AutoMirrored.Filled.KeyboardArrowRight, repeat = true, fn = PcKey.Named("End", "end", glyph = "\u21F2")),
    ),
)

/**
 * A PC keyboard drawn on the phone — Hacker's Keyboard's idea, done in-app: every key
 * a physical keyboard has, sent as the events a physical keyboard sends, with nothing
 * the system IME puts in between (autocorrect, capitalisation, a Send key). 13 units
 * across, so the caps are Hacker's-Keyboard small in portrait and comfortable on the
 * mirror sideways; the Shift and Fn layers carry what the width could not.
 *
 * What a key sends is decided per press, from what is held:
 *  - a character with nothing but Shift down goes as `text` — the exact character,
 *    whatever layout the PC has;
 *  - a character with Ctrl, Alt or Win down goes as the *key* (a VK code), so Ctrl+C
 *    is copy and not a "c" typed;
 *  - everything else — Enter, arrows, F-keys — is always a key.
 * Modifiers are [mods], the same tap-latch keys as the Keyboard tab's grid, so
 * Ctrl+Shift+T is three taps. Printing keys fire on release, like every soft keyboard;
 * the repeating ones (Backspace, arrows) fire on press and again while held.
 */
@Composable
internal fun PcKeyboard(
    mods: ModifierKeys,
    send: (JSONObject) -> Unit,
    onIme: () -> Unit,
    modifier: Modifier = Modifier,
    /** 48dp on the Keyboard tab, which has the height to spare and 25dp-wide caps that
     *  need every bit of target they can get; 40dp floating over the mirror. */
    keyHeight: Dp = 48.dp,
) {
    var fn by remember { mutableStateOf(false) }
    val shift = "shift" in mods.down
    val latestSend by rememberUpdatedState(send)

    // Read at press time, not composition time: a latched Ctrl and the key it modifies
    // can be a frame apart.
    fun fire(key: PcKey) {
        val resolved = if (fn) key.fn ?: key else key
        // Shift is a chord partner, not a chord: Shift+a is "A", which is text.
        val chord = mods.down.keys.any { it != "shift" }
        when (resolved) {
            is PcKey.Char -> if (chord) {
                latestSend(Protocol.tapVk(resolved.vk))
            } else {
                val shifted = "shift" in mods.down
                latestSend(Protocol.text((if (shifted) resolved.shifted else resolved.base).toString()))
            }
            is PcKey.Named -> latestSend(Protocol.tap(resolved.key))
            else -> return
        }
        fn = false
        mods.keyed()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEach { key ->
                    val shown = if (fn) key.fn ?: key else key
                    when (key) {
                        is PcKey.Char -> {
                            val c = shown as? PcKey.Char
                            Cap(
                                units = key.units,
                                height = keyHeight,
                                label = when {
                                    c == null -> (shown as PcKey.Named).label
                                    shift -> c.shifted.toString()
                                    else -> c.base.toString()
                                },
                                onTap = { fire(key) },
                            )
                        }
                        is PcKey.Named -> {
                            val n = shown as PcKey.Named
                            if (key.repeat) {
                                // Acts on press, the way a real one does — holding
                                // Backspace to eat a word has to start at once.
                                Cap(units = key.units, height = keyHeight, label = n.label, glyph = n.glyph, icon = n.icon, repeat = true, onPress = { fire(key) })
                            } else {
                                Cap(units = key.units, height = keyHeight, label = n.label, glyph = n.glyph, icon = n.icon, onTap = { fire(key) })
                            }
                        }
                        is PcKey.Mod -> Cap(
                            units = key.units,
                            height = keyHeight,
                            label = key.label,
                            icon = key.icon,
                            active = key.key in mods.down,
                            onPress = { mods.press(key.key) },
                            onRelease = { up -> if (up) mods.lift(key.key) else mods.cancel(key.key) },
                            onActivate = { mods.toggle(key.key) },
                        )
                        is PcKey.Fn -> Cap(units = key.units, height = keyHeight, label = "Fn", active = fn, onTap = { fn = !fn })
                        is PcKey.Ime -> Cap(
                            units = key.units,
                            height = keyHeight,
                            label = "Type",
                            icon = Icons.Filled.Keyboard,
                            onTap = onIme,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A key cap. Draws its own face (the §7 key face: `surface-muted` under `border-strong`,
 * lit on press and while [active]) and tracks its own press and release, because the
 * modifiers need both halves and the repeating keys need the press to start a timer —
 * a Button's single `onClick` fits neither.
 *
 * Two kinds of key: [onTap] fires on a release *inside* the cap and not on a cancel —
 * a finger that slid off, or the Keyboard tab's scroll taking the gesture — which is
 * what makes flicking past this keyboard safe. [onPress]/[onRelease] are the stateful
 * pair for modifiers and repeaters: press on pointer-down, release on *any* end,
 * cancel included (it says which), because a Ctrl whose finger slid off must not stay
 * down on the PC.
 *
 * TalkBack gets [onActivate], or the tap, or press-then-release: it activates by
 * accessibility action, never by touch.
 */
@Composable
private fun RowScope.Cap(
    units: Float,
    height: Dp,
    label: String,
    glyph: String? = null,
    icon: ImageVector? = null,
    active: Boolean = false,
    repeat: Boolean = false,
    onTap: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: ((up: Boolean) -> Unit)? = null,
    onActivate: (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val face = if (pressed || active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        PortalRemoteTheme.extendedColors.surfaceMuted
    }

    Box(
        modifier = Modifier
            .weight(units)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(face)
            .border(1.dp, PortalRemoteTheme.extendedColors.borderStrong, MaterialTheme.shapes.small)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    when {
                        onActivate != null -> onActivate()
                        tap != null -> tap?.invoke()
                        else -> { press?.invoke(); release?.invoke(true) }
                    }
                    true
                }
            }
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptics.tap()
                    press?.invoke()
                    val repeater = if (repeat) {
                        scope.launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                press?.invoke()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                    } else {
                        null
                    }
                    val up = waitForUpOrCancellation()
                    repeater?.cancel()
                    pressed = false
                    release?.invoke(up != null)
                    if (up != null) tap?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        } else {
            Text(
                glyph ?: label,
                // 11sp: "Ctrl" has 25dp to live in on a portrait phone.
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
