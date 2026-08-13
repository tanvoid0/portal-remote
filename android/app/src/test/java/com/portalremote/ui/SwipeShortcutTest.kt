package com.portalremote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule worth pinning down: **you go the way you swipe**. A branch table like
 * this inverts silently — back and forward still both "work", just backwards — and
 * nothing about the app would tell you, since the result appears on the PC.
 */
class SwipeShortcutTest {

    @Test
    fun `two fingers left goes back, right goes forward`() {
        assertEquals(
            listOf("browser_back"),
            swipeShortcut(GestureMode.TWO_FINGER, Axis.HORIZONTAL, -120f),
        )
        assertEquals(
            listOf("browser_forward"),
            swipeShortcut(GestureMode.TWO_FINGER, Axis.HORIZONTAL, 120f),
        )
    }

    @Test
    fun `three fingers sideways switches desktop in the direction swiped`() {
        assertEquals(
            listOf("win", "ctrl", "left"),
            swipeShortcut(GestureMode.THREE_FINGER, Axis.HORIZONTAL, -120f),
        )
        assertEquals(
            listOf("win", "ctrl", "right"),
            swipeShortcut(GestureMode.THREE_FINGER, Axis.HORIZONTAL, 120f),
        )
    }

    @Test
    fun `three fingers up is task view, down is show desktop`() {
        // Y grows downwards, so a negative travel is an upwards swipe.
        assertEquals(
            listOf("win", "tab"),
            swipeShortcut(GestureMode.THREE_FINGER, Axis.VERTICAL, -120f),
        )
        assertEquals(
            listOf("win", "d"),
            swipeShortcut(GestureMode.THREE_FINGER, Axis.VERTICAL, 120f),
        )
    }
}
