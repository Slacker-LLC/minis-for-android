package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (Mangi-11/Eta @
 * c15de97). The argv is the whole contract here: the wrong service name toggles the wrong radio, and
 * an unknown target must never be turned into a guess.
 */
class DeviceStatePolicyTest {

    @Test
    fun `wifi is one argv for the service command`() {
        assertEquals(
            listOf("svc", "wifi", "enable"),
            DeviceStatePolicy.argv(DeviceStatePolicy.TARGET_WIFI, enabled = true),
        )
        assertEquals(
            listOf("svc", "wifi", "disable"),
            DeviceStatePolicy.argv(DeviceStatePolicy.TARGET_WIFI, enabled = false),
        )
    }

    @Test
    fun `bluetooth goes through the manager command`() {
        assertEquals(
            listOf("cmd", "bluetooth_manager", "enable"),
            DeviceStatePolicy.argv(DeviceStatePolicy.TARGET_BLUETOOTH, enabled = true),
        )
        assertEquals(
            listOf("cmd", "bluetooth_manager", "disable"),
            DeviceStatePolicy.argv(DeviceStatePolicy.TARGET_BLUETOOTH, enabled = false),
        )
    }

    @Test
    fun `an unknown target is refused, not guessed`() {
        assertNull(DeviceStatePolicy.argv("nfc", enabled = true))
        assertNull(DeviceStatePolicy.argv("", enabled = true))
        assertNull(DeviceStatePolicy.argv(null, enabled = true))
        assertNull(DeviceStatePolicy.argv("wifi; reboot", enabled = true))
    }

    @Test
    fun `the target is matched the way a caller writes it`() {
        assertEquals(
            DeviceStatePolicy.argv(DeviceStatePolicy.TARGET_WIFI, enabled = true),
            DeviceStatePolicy.argv("  WiFi  ", enabled = true),
        )
    }
}
