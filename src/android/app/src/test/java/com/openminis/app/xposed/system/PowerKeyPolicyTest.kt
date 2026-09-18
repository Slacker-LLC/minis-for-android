package com.openminis.app.xposed.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/system/PowerHooks.kt` (Mangi-11/Eta @ c15de97). The
 * message id is the trigger and the dedup window is what keeps one press from opening the assistant
 * twice, so both are pinned.
 */
class PowerKeyPolicyTest {

    @Test
    fun `only the assist message belongs to this hook`() {
        assertTrue(PowerKeyPolicy.isAssistMessage(PowerKeyPolicy.ASSIST_MESSAGE_WHAT))
        assertTrue(PowerKeyPolicy.isAssistMessage(0x3F3))
        assertFalse(PowerKeyPolicy.isAssistMessage(0x3F2))
        assertFalse(PowerKeyPolicy.isAssistMessage(0))
        assertFalse(PowerKeyPolicy.isAssistMessage(-1))
    }

    @Test
    fun `a second press inside the window is the same press`() {
        val first = 20_000L

        assertTrue(
            PowerKeyPolicy.withinDedupWindow(
                nowMs = first + PowerKeyPolicy.DEDUP_WINDOW_MS,
                lastLaunchMs = first,
            ),
        )
        assertFalse(
            PowerKeyPolicy.withinDedupWindow(
                nowMs = first + PowerKeyPolicy.DEDUP_WINDOW_MS + 1,
                lastLaunchMs = first,
            ),
        )
        assertFalse(
            "nothing launched yet is not a dedup window",
            PowerKeyPolicy.withinDedupWindow(nowMs = first, lastLaunchMs = 0L),
        )
    }
}
