package com.portalremote.ui

import com.portalremote.ui.theme.DeviceOrientation
import com.portalremote.ui.theme.classifyDeviceOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

class RotationSuggestionTest {

    @Test
    fun `negative degrees means flat or unknown`() {
        assertEquals(DeviceOrientation.FLAT, classifyDeviceOrientation(-1))
    }

    @Test
    fun `upright and near-upright read as portrait`() {
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(0))
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(44))
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(315))
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(359))
    }

    @Test
    fun `on its side either direction reads as landscape`() {
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(90))
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(270))
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(45))
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(314))
    }

    @Test
    fun `quadrant boundaries are pinned`() {
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(135))
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(134))
        assertEquals(DeviceOrientation.PORTRAIT, classifyDeviceOrientation(225))
        assertEquals(DeviceOrientation.LANDSCAPE, classifyDeviceOrientation(226))
    }
}
