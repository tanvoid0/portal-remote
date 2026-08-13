package com.portalremote.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * The app's haptic vocabulary — four strengths, deliberately few, so a session has a
 * consistent feel rather than a different buzz per screen.
 *
 * Built on [View.performHapticFeedback] rather than `Vibrator`: the platform picks the
 * right waveform for the device's actuator, it needs no `VIBRATE` permission, and it
 * already obeys the system "touch feedback" setting — so this class only has to carry
 * the app's own on/off preference on top.
 *
 * The rule for where these belong (an extension of docs/design-system.md §1's
 * frequency argument): a haptic marks a *discrete* event the user caused — a click
 * that went to the PC, a gesture crossing into a new mode, a scroll notch. Continuous
 * tracking — pointer moves, pan, pinch — gets nothing: the actuator can't keep up with
 * a 120Hz gesture stream, and trying turns precision work into a rattle.
 */
class Haptics(private val view: View?, private val enabled: Boolean) {

    /** Smallest bump available, for events that repeat inside one gesture: a scroll
     *  notch, the end of a drag. */
    fun tick() = play(TICK)

    /** A discrete hit — a key, a button, a click sent to the PC. */
    fun tap() = play(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Heavier: a gesture just changed meaning (a hold became a click-and-drag). */
    fun press() = play(HapticFeedbackConstants.LONG_PRESS)

    /** Rare and positive — a PC accepted the pairing. */
    fun confirm() = play(CONFIRM)

    /** Rare and negative — the connection failed or was refused. */
    fun reject() = play(REJECT)

    private fun play(constant: Int) {
        if (!enabled) return
        view?.performHapticFeedback(constant)
    }

    companion object {
        /** The default before anything provides a real one — silent, so a preview or a
         *  screen rendered outside the app's provider never buzzes. */
        val Off = Haptics(null, enabled = false)

        // SEGMENT_TICK (API 34) is the crisp detent-style tick; CLOCK_TICK is the
        // closest thing older devices have. CONFIRM/REJECT arrived in API 30.
        private val TICK = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }

        private val CONFIRM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }

        private val REJECT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    }
}

/** Ambient haptics for the whole app — provided once in `PortalRemoteApp` from the
 *  user's setting, so no screen has to thread it through its parameters. */
val LocalHaptics = staticCompositionLocalOf { Haptics.Off }

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val view = LocalView.current
    return remember(view, enabled) { Haptics(view, enabled) }
}

/** Fires [Haptics.tap] on *pointer-down* for any component that exposes its own
 *  [InteractionSource] — press feedback has to land with the finger, not when the click
 *  gesture resolves on release (same reasoning as `KeyboardScreen`'s instant tint). */
@Composable
fun HapticPress(interactionSource: InteractionSource) {
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    LaunchedEffect(pressed) { if (pressed) haptics.tap() }
}
