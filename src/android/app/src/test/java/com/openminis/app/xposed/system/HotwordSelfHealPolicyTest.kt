package com.openminis.app.xposed.system

import com.openminis.app.xposed.ModuleTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/system/HotwordSelfHealHooks.kt` (Mangi-11/Eta @
 * c15de97). The retry budget and the cooldown are the whole point of the policy: a repair that keeps
 * poking the service that listens for the wake word is worse than no repair.
 */
class HotwordSelfHealPolicyTest {

    @Test
    fun `only the default display starts a heal`() {
        assertTrue(HotwordSelfHealPolicy.isPrimaryDisplay(0))
        assertFalse(HotwordSelfHealPolicy.isPrimaryDisplay(1))
        assertFalse(HotwordSelfHealPolicy.isPrimaryDisplay(-1))
    }

    @Test
    fun `a second screen-off inside the cooldown is the same event`() {
        val first = 10_000L

        assertTrue(
            "one millisecond short of the window is still inside it",
            HotwordSelfHealPolicy.withinCooldown(
                nowMs = first + HotwordSelfHealPolicy.SCREEN_OFF_COOLDOWN_MS - 1,
                lastScheduleMs = first,
            ),
        )
        assertFalse(
            HotwordSelfHealPolicy.withinCooldown(
                nowMs = first + HotwordSelfHealPolicy.SCREEN_OFF_COOLDOWN_MS,
                lastScheduleMs = first,
            ),
        )
        assertFalse(
            "nothing scheduled yet is not a cooldown",
            HotwordSelfHealPolicy.withinCooldown(nowMs = first, lastScheduleMs = 0L),
        )
    }

    @Test
    fun `the attempts are exactly the ones the schedule describes`() {
        assertEquals(3, HotwordSelfHealPolicy.RETRY_COUNT)
        assertEquals(1_200L, HotwordSelfHealPolicy.INITIAL_DELAY_MS)
        assertEquals(1_400L, HotwordSelfHealPolicy.STEP_DELAY_MS)

        assertTrue(HotwordSelfHealPolicy.hasAttemptsLeft(1))
        assertTrue(HotwordSelfHealPolicy.hasAttemptsLeft(2))
        assertFalse("the third attempt is the last one", HotwordSelfHealPolicy.hasAttemptsLeft(3))
    }

    @Test
    fun `only Google's assistant has the session this repairs`() {
        assertTrue(
            HotwordSelfHealPolicy.isGoogleAssistantComponent(ModuleTargets.GOOGLE_SEARCH_PACKAGE),
        )
        assertFalse(HotwordSelfHealPolicy.isGoogleAssistantComponent(ModuleTargets.XIAOAI_PACKAGE))
        assertFalse(HotwordSelfHealPolicy.isGoogleAssistantComponent(null))
    }
}
