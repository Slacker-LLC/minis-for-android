package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-settings-type-guard] The only type signal a setting carries is its
 * current value, so the guard is exactly this predicate: a key holding an integer
 * accepts only integers. Writing `not-a-number` over `system/screen_brightness`
 * succeeded on device (the platform does not type-check) and left the device
 * reporting that literal string as its brightness.
 */
class AndroidSettingsTypeGuardTest {

    @Test
    fun `integers are recognised with sign and padding`() {
        assertTrue(AndroidSettingsOps.isInteger("2"))
        assertTrue(AndroidSettingsOps.isInteger("-15"))
        assertTrue(AndroidSettingsOps.isInteger(" 007 "))
    }

    @Test
    fun `everything else is not an integer`() {
        for (text in listOf("not-a-number", "", "2.5", "0x10", "true", "1 2")) {
            assertFalse(text, AndroidSettingsOps.isInteger(text))
        }
    }
}

