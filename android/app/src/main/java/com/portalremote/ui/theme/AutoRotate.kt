package com.portalremote.ui.theme

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Lets the phone's own rotation drive the layout for as long as the caller is composed,
 * and hands portrait back on the way out.
 *
 * The app is locked portrait in the manifest: it is a control surface held one-handed,
 * and every other screen is a column of controls that gains nothing from turning. The
 * mirror is the exception — its content is a picture of a desktop, and a desktop is
 * wide. Portrait spends most of the display on letterbox either side of a strip of
 * someone's screen.
 *
 * This was a *suggestion* before: a Samsung-style "Rotate to landscape" pill, offered
 * for five seconds when the sensor and the layout disagreed, that had to be tapped to
 * be honoured. Turning the phone sideways in front of a wide picture is already an
 * unambiguous request, and making the user then aim at a pill to have it granted was
 * chrome charging admission for the obvious. The sensor listener, the timeout and the
 * quadrant classifier all went with it — the OS does that part.
 *
 * [ActivityInfo.SCREEN_ORIENTATION_SENSOR] rather than `..._USER`: it follows the
 * sensor even when the system's own rotation lock is on. That is the same exception a
 * video player takes full screen and for the same reason — the lock is a preference
 * about screens you *read*, and this is a screen whose whole shape is landscape. It is
 * also what the old chip did on accept (`SENSOR_LANDSCAPE`), so no behaviour the user
 * could already reach is being widened here.
 */
@Composable
fun AutoRotate() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context as Activity
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        // Leaving the mirror always locks back, so the one screen that earned the
        // unlock is the only one that ever holds it.
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
}
