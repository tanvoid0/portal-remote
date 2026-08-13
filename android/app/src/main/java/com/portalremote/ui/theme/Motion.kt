package com.portalremote.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue

// Motion system — see docs/design-system.md §6. Only animate transform/alpha
// equivalents (graphicsLayer scaleX/scaleY/alpha), never size/padding. Never ease-in
// on anything UI-initiated.

object Motion {
    /** Matches CSS ease-out; used for every UI-initiated tween below. */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Matches CSS ease-in-out; status color/icon morphs only. */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** Button/nav-item press feedback: scale to 0.97 (not 0.95 — small touch targets
     * shouldn't shrink much), 100-120ms ease-out. */
    const val PressDurationMs = 110
    const val PressScale = 0.97f
    fun pressSpec(): FiniteAnimationSpec<Float> = tween(PressDurationMs, easing = EaseOut)

    /** Bottom nav tab switch: cross-fade only, no slide. */
    const val TabSwitchDurationMs = 150
    fun tabSwitchSpec(): FiniteAnimationSpec<Float> = tween(TabSwitchDurationMs, easing = EaseOut)

    /** Bottom-nav selection pill travelling between tabs, and the selected icon's lift.
     * Slight overshoot: this is the only piece of shell chrome that moves, and a
     * critically damped slide reads as a redraw rather than as a thing that moved. */
    fun navIndicatorSpec(): FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    /** Connect/disconnect status change: color + icon morph, never abrupt. */
    const val StatusMorphDurationMs = 200
    fun statusMorphSpec(): FiniteAnimationSpec<Float> = tween(StatusMorphDurationMs, easing = EaseInOut)

    /** Pairing success: the one moment worth a little bounce — rare, celebratory. */
    fun pairingSuccessSpec(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f)

    /** Programmatic repositioning after a gesture (e.g. snap-back). Critically damped:
     * settles without overshoot. */
    fun snapBackSpec(): FiniteAnimationSpec<Float> = spring(dampingRatio = 1f)

    /** Rubber-band resistance for a trackpad surface clamped at an edge, per Apple's
     * UIScrollView formula. `overshoot` is the raw (unclamped) distance past the edge. */
    fun rubberBandResistance(overshoot: Float, k: Float = 0.55f): Float {
        val magnitude = kotlin.math.abs(overshoot)
        val resisted = (magnitude * k) / (1 + k * magnitude)
        return resisted * kotlin.math.sign(overshoot)
    }

    /** Android has no `prefers-reduced-motion`; this is the closest system signal
     * (Settings > Accessibility > Remove animations). When true, skip the pairing
     * spring and status morph duration — jump directly to end state. */
    fun reducedMotionEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Drives the standard button press-scale (§6) off a component's own
 * [InteractionSource] — apply the result via `Modifier.graphicsLayer { scaleX =
 * v; scaleY = v }`. Works with any Material3 clickable that exposes its
 * interactionSource (Button, IconButton, NavigationBarItem, ...). */
@Composable
fun rememberPressScale(interactionSource: InteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else 1f,
        animationSpec = Motion.pressSpec(),
        label = "press-scale",
    )
}
