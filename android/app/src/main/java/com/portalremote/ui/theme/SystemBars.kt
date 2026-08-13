package com.portalremote.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The status bar stays up — clock, battery, notifications, all where a glance already
 * expects them — and blends into the app's own top row (`themes.xml` already makes its
 * background transparent, and every top bar in the app paints `safeDrawing.only(Top)`
 * behind it in the same colour). See docs/design-system.md §13.
 *
 * The system *navigation* bar stays hidden behind the app's own floating capsule
 * (`RemoteNavBar`) — that's still real chrome this app replaces with its own, not
 * something worth showing twice. [immersive] additionally drops the status bar too, for
 * the one screen (the mirror, full screen) where the picture should own every pixel;
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` still brings both back with an edge swipe.
 */
@Composable
fun SystemBars(immersive: Boolean = false) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    if (view.isInEditMode) return
    DisposableEffect(immersive, dark) {
        val activity = view.context as Activity
        val controller = WindowInsetsControllerCompat(activity.window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // true = dark icons, for a light top row; false = light icons, for a dark one.
        controller.isAppearanceLightStatusBars = !dark
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        onDispose {}
    }
}
