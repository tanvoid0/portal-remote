package com.portalremote.ui.theme

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the suggestion stays up before it gives up asking — the same order as
 *  Samsung's own "auto rotate" popup. */
private const val SUGGESTION_TIMEOUT_MS = 5_000L

enum class DeviceOrientation { PORTRAIT, LANDSCAPE, FLAT }

/**
 * Buckets a raw [OrientationEventListener] angle (0..359, or -1 for "flat/unknown") into
 * the orientation a layout would use. Pure so the quadrant boundaries can be pinned
 * without a device — see RotationSuggestionTest.
 *
 * The landscape span is wider than a plain four-way split (45..134 / 226..314 rather
 * than 45..135 / 225..315 either side of 90/270): a phone genuinely turned sideways to
 * watch the mirror settles closer to "on its side" than the even split assumes, and
 * `PORTRAIT` is the safer default for the angles in between — a spurious landscape
 * suggestion while someone is still holding the phone upright is worse than a beat's
 * delay before a real one appears.
 */
fun classifyDeviceOrientation(degrees: Int): DeviceOrientation = when {
    degrees < 0 -> DeviceOrientation.FLAT
    degrees in 45..134 || degrees in 226..314 -> DeviceOrientation.LANDSCAPE
    else -> DeviceOrientation.PORTRAIT
}

/** What [rememberRotationSuggestion] hands back to the chip that renders it. */
class RotationSuggestionState internal constructor(
    val visible: Boolean,
    val toLandscape: Boolean,
    val onAccept: () -> Unit,
)

/**
 * Watches the phone's physical rotation independently of the app's own (manifest-locked
 * portrait) layout, and offers a few-second "rotate the screen" suggestion when they
 * disagree — the same shape as Samsung's own auto-rotate popup, for the one screen (the
 * mirror) that is genuinely better sideways. Accepting flips [Activity.requestedOrientation]
 * to sensor-driven landscape; leaving composition (switching tabs) always hands portrait
 * back, so the lock this exists to relax never outlives the screen that earned it.
 *
 * [enabled] gates the sensor listener itself, not just the popup — `OrientationEventListener`
 * is a continuous stream, so a screen that doesn't need this shouldn't pay for it just
 * because the composable is in the tree.
 */
@Composable
fun rememberRotationSuggestion(enabled: Boolean): RotationSuggestionState {
    val view = LocalView.current
    if (view.isInEditMode) {
        return RotationSuggestionState(visible = false, toLandscape = true, onAccept = {})
    }
    val activity = view.context as Activity

    var deviceOrientation by remember { mutableStateOf(DeviceOrientation.PORTRAIT) }
    var landscape by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }

    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                val next = classifyDeviceOrientation(orientation)
                if (next != DeviceOrientation.FLAT) deviceOrientation = next
            }
        }
        listener.enable()
        onDispose {
            listener.disable()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            landscape = false
        }
    }

    val mismatched = enabled &&
        ((deviceOrientation == DeviceOrientation.LANDSCAPE && !landscape) ||
            (deviceOrientation == DeviceOrientation.PORTRAIT && landscape))

    // Restarts on every mismatch/agreement flip — which is what makes this double as
    // both the auto-hide timeout and the "phone turned back, never mind" cancel: an
    // agreement sets show = false with no delay, a mismatch schedules the same outcome
    // after the popup's on-screen budget.
    LaunchedEffect(mismatched) {
        show = mismatched
        if (mismatched) {
            delay(SUGGESTION_TIMEOUT_MS)
            show = false
        }
    }

    return RotationSuggestionState(
        visible = show,
        toLandscape = !landscape,
        onAccept = {
            landscape = !landscape
            activity.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            show = false
        },
    )
}

/** The popup itself — a dark pill (this app's floating-over-the-mirror chrome, per
 *  [ScreenScreen]'s [FloatingMirrorControls] and gesture hint) that fades in, sits for
 *  [SUGGESTION_TIMEOUT_MS], and fades out again without the caller doing anything once
 *  the state above says so. */
@Composable
fun RotationSuggestionChip(state: RotationSuggestionState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(Motion.statusMorphSpec()),
        exit = fadeOut(Motion.statusMorphSpec()),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .clickable(onClick = state.onAccept)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.ScreenRotation,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                if (state.toLandscape) "Rotate to landscape" else "Rotate to portrait",
                color = Color.White,
            )
        }
    }
}
