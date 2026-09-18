package com.openminis.app.xposed.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/accessibility/AccessibilityProtectionClient.kt`
 * (Mangi-11/Eta @ c15de97). What matters is the mapping the UI acts on: anything the backend does
 * not explicitly confirm is UNAVAILABLE, never a silently assumed success.
 */
class AccessibilityProtectionClientTest {

    @Test
    fun `only an explicit applied answer counts as applied`() {
        assertEquals(
            AccessibilityProtectionClient.ControlStatus.APPLIED,
            AccessibilityProtectionClient.statusOf(AccessibilityProtectionProtocol.RESULT_APPLIED),
        )
        assertEquals(
            AccessibilityProtectionClient.ControlStatus.REJECTED,
            AccessibilityProtectionClient.statusOf(AccessibilityProtectionProtocol.RESULT_REJECTED),
        )
    }

    @Test
    fun `an unanswered or unknown result is unavailable`() {
        assertEquals(
            AccessibilityProtectionClient.ControlStatus.UNAVAILABLE,
            AccessibilityProtectionClient.statusOf(
                AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
            ),
        )
        assertEquals(
            "a result code this version does not know must not read as success",
            AccessibilityProtectionClient.ControlStatus.UNAVAILABLE,
            AccessibilityProtectionClient.statusOf(99),
        )
        assertEquals(
            AccessibilityProtectionClient.ControlStatus.UNAVAILABLE,
            AccessibilityProtectionClient.statusOf(-1),
        )
    }
}
