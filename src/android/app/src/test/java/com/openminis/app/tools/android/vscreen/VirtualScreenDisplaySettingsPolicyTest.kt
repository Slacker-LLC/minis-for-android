package com.openminis.app.tools.android.vscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenDisplaySettingsPolicyTest {
    @Test fun defaultUsesTheFullDeviceResolution() {
        val settings = VirtualScreenDisplaySettings()
        assertTrue(VirtualScreenDisplaySettingsPolicy.isValid(settings))
        assertTrue(settings.scale == 1.0f)
    }

    @Test fun scaleIsBounded() {
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(scale = 0.49f)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(scale = 1.01f)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(scale = Float.NaN)))
        assertTrue(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(scale = 0.5f)))
        assertTrue(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(scale = 1.0f)))
    }
}
