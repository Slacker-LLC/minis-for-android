package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt`
 * (`app_state_control`, Mangi-11/Eta @ c15de97). Each of these argv values is one command against
 * somebody else's package, so the exact verbs and the user scope are the cases worth pinning.
 */
class AppStatePolicyTest {

    private val pkg = "com.example.notes"

    @Test
    fun `force stop goes through the activity manager`() {
        assertEquals(
            listOf("am", "force-stop", "--user", "current", pkg),
            AppStatePolicy.argv(AppStatePolicy.FORCE_STOP, pkg, userId = null),
        )
        assertEquals(
            listOf("am", "force-stop", "--user", "10", pkg),
            AppStatePolicy.argv(AppStatePolicy.FORCE_STOP, pkg, userId = 10),
        )
    }

    @Test
    fun `freezing disables the package and unfreezing enables it`() {
        assertEquals(
            listOf("pm", "disable-user", "--user", "current", pkg),
            AppStatePolicy.argv(AppStatePolicy.FREEZE, pkg, userId = null),
        )
        assertEquals(
            listOf("pm", "enable", "--user", "current", pkg),
            AppStatePolicy.argv(AppStatePolicy.UNFREEZE, pkg, userId = null),
        )
    }

    @Test
    fun `an action this policy does not know is refused`() {
        assertNull(AppStatePolicy.argv("disable", pkg, userId = null))
        assertNull(AppStatePolicy.argv("", pkg, userId = null))
        assertNull(AppStatePolicy.argv(null, pkg, userId = null))
    }

    @Test
    fun `the action is matched the way a caller writes it`() {
        assertEquals(
            AppStatePolicy.argv(AppStatePolicy.FREEZE, pkg, userId = null),
            AppStatePolicy.argv("  FREEZE ", pkg, userId = null),
        )
        assertEquals(
            AppStatePolicy.ACTIONS,
            listOf(AppStatePolicy.FORCE_STOP, AppStatePolicy.FREEZE, AppStatePolicy.UNFREEZE),
        )
    }
}
