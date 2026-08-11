package com.portalremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.portalremote.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Mouse deltas are scaled down before sending because Windows applies pointer
 * acceleration to relative motion: a phone-swipe delta arrives at the desktop
 * amplified 2-4x (confirmed against a live server: dx=60 landed as 150-290px
 * depending on run). Without this, the pointer flies far past the finger.
 */
private const val MOVE_SENSITIVITY = 0.5f

/** Total movement (px) below which a gesture counts as a tap rather than a drag. */
private const val TAP_SLOP = 18f

/** Pixels of two-finger drag per wheel notch; matches a comfortable scroll feel. */
private const val SCROLL_PX_PER_NOTCH = 60f

private enum class GestureMode { WAITING, MOVE, DRAG, TWO_FINGER }

/**
 * Touch surface + explicit click buttons, matching how physical trackpads with
 * separate buttons behave: dragging on the pad moves the cursor, the buttons
 * below click independently so users can hold one and drag with another finger
 * to select/drag desktop content.
 */
@Composable
fun TrackpadScreen(
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Int) -> Unit,
    onClick: (button: String, down: Boolean?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrackpadSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onMove = onMove,
            onScroll = onScroll,
            onTapLeft = { onClick("left", null) },
            onTapRight = { onClick("right", null) },
            onDragStart = { onClick("left", true) },
            onDragEnd = { onClick("left", false) },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ClickButton(
                label = "Left",
                modifier = Modifier.weight(1f),
                onDown = { onClick("left", true) },
                onUp = { onClick("left", false) },
            )
            ClickButton(
                label = "Right",
                modifier = Modifier.weight(1f),
                onDown = { onClick("right", true) },
                onUp = { onClick("right", false) },
            )
        }
    }
}

@Composable
private fun TrackpadSurface(
    modifier: Modifier,
    onMove: (dx: Int, dy: Int) -> Unit,
    onScroll: (dy: Int) -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    var scrollRemainder by remember { mutableFloatStateOf(0f) }
    val tapScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // 100ms scale pulse confirming a resolved tap — see docs/design-system.md §7.
    // Drag/scroll never touch this: the pad tracks the finger 1:1 with no animation.
    fun pulse() {
        scope.launch {
            tapScale.animateTo(Motion.PressScale, Motion.pressSpec())
            tapScale.animateTo(1f, Motion.pressSpec())
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .graphicsLayer { scaleX = tapScale.value; scaleY = tapScale.value }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTimeMs = System.currentTimeMillis()
                    var totalMove = 0f
                    var mode = GestureMode.WAITING
                    var pointerCount = 1

                    while (true) {
                        val elapsed = System.currentTimeMillis() - downTimeMs
                        val waitBudget = (longPressTimeoutMs - elapsed).coerceAtLeast(0)

                        val event = if (mode == GestureMode.WAITING) {
                            withTimeoutOrNull(waitBudget) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // Long-press timeout elapsed with the finger essentially
                            // still: begin a click-and-drag instead of a plain move.
                            mode = GestureMode.DRAG
                            onDragStart()
                            continue
                        }

                        val pressed = event.changes.filter { it.pressed }
                        pointerCount = maxOf(pointerCount, pressed.size)

                        if (pressed.isEmpty()) {
                            when {
                                mode == GestureMode.DRAG -> onDragEnd()
                                totalMove < TAP_SLOP && pointerCount == 1 -> { pulse(); onTapLeft() }
                                totalMove < TAP_SLOP && pointerCount >= 2 -> { pulse(); onTapRight() }
                            }
                            break
                        }

                        if (pointerCount >= 2 && mode != GestureMode.DRAG) {
                            mode = GestureMode.TWO_FINGER
                        }

                        when (mode) {
                            GestureMode.TWO_FINGER -> {
                                val avgDy = pressed.map { it.positionChange().y }.average().toFloat()
                                event.changes.forEach { it.consume() }
                                if (avgDy != 0f) {
                                    scrollRemainder += avgDy
                                    val notches = (scrollRemainder / SCROLL_PX_PER_NOTCH)
                                    if (kotlin.math.abs(notches) >= 1f) {
                                        val whole = notches.toInt()
                                        scrollRemainder -= whole * SCROLL_PX_PER_NOTCH
                                        // Natural scrolling: drag down -> content follows finger.
                                        onScroll(-whole * 120)
                                    }
                                }
                            }

                            GestureMode.WAITING, GestureMode.MOVE, GestureMode.DRAG -> {
                                val primary: PointerInputChange =
                                    event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                                val delta = primary.positionChange()
                                primary.consume()
                                totalMove += delta.getDistance()

                                if (mode == GestureMode.WAITING) {
                                    if (totalMove > TAP_SLOP) mode = GestureMode.MOVE else continue
                                }
                                val dx = (delta.x * MOVE_SENSITIVITY).roundToInt()
                                val dy = (delta.y * MOVE_SENSITIVITY).roundToInt()
                                if (dx != 0 || dy != 0) onMove(dx, dy)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Text(
                "Drag to move · tap to click · hold to drag\ntwo fingers to scroll or right-click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A button that reports press and release separately so it can be held. Scales down
 * on press and back on release, per the standard §6 button-feedback spec — driven
 * directly off this button's own down/up tracking rather than an interactionSource,
 * since it already bypasses the normal click gesture to support press-and-hold. */
@Composable
private fun ClickButton(
    label: String,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Button(
        onClick = {},
        modifier = modifier
            .height(56.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onDown()
                    scope.launch { scale.animateTo(Motion.PressScale, Motion.pressSpec()) }
                    waitForUpOrCancellation()
                    onUp()
                    scope.launch { scale.animateTo(1f, Motion.pressSpec()) }
                }
            },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
