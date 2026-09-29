package com.openminis.app.tools.android.vscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenDisplaySettingsPolicyTest {
    @Test fun defaultsMatchTheLowResourceProfile() {
        val settings = VirtualScreenDisplaySettings()
        assertTrue(VirtualScreenDisplaySettingsPolicy.isValid(settings))
        assertTrue(settings.width == 720 && settings.height == 1600 && settings.dpi == 320)
    }

    @Test fun dimensionsAndDensityAreBounded() {
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(width = 319)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(width = 1921)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(height = 479)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(height = 2561)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(dpi = 119)))
        assertFalse(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(dpi = 641)))
        assertTrue(VirtualScreenDisplaySettingsPolicy.isValid(VirtualScreenDisplaySettings(width = 1080, height = 2340, dpi = 440)))
    }
}
