package com.openminis.app.xposed.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/hyperos/HyperOsPowerPolicy.kt` (Mangi-11/Eta @
 * c15de97). HyperOS sends every power-key shortcut through one dispatcher, so both halves of the
 * match are load-bearing.
 */
class HyperOsPowerPolicyTest {

    @Test
    fun `the assistant power-key gestures match`() {
        HyperOsPowerPolicy.POWER_KEY_SOURCES.forEach { source ->
            assertTrue(source, HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", source))
        }
    }

    @Test
    fun `other functions on the same dispatcher are left alone`() {
        assertFalse(
            HyperOsPowerPolicy.isAssistantShortcut("launch_camera", "long_press_power_key"),
        )
        assertFalse(
            HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "double_press_power_key"),
        )
        assertFalse(HyperOsPowerPolicy.isAssistantShortcut(null, "long_press_power_key"))
        assertFalse(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", null))
    }
}
