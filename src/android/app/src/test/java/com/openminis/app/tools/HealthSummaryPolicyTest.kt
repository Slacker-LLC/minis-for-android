package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @
 * c15de97). The window is the only thing a caller chooses, and the unit conversion is the one place
 * where a wrong number would still look like a plausible one.
 */
class HealthSummaryPolicyTest {

    @Test
    fun `the window is clamped to the declared range`() {
        assertEquals(HealthSummaryPolicy.DEFAULT_DAYS, HealthSummaryPolicy.clampDays(null))
        assertEquals(1, HealthSummaryPolicy.clampDays(0))
        assertEquals(1, HealthSummaryPolicy.clampDays(-3))
        assertEquals(14, HealthSummaryPolicy.clampDays(14))
        assertEquals(HealthSummaryPolicy.MAX_DAYS, HealthSummaryPolicy.clampDays(365))
    }

    @Test
    fun `the cutoff is the window before now`() {
        val now = 1_700_000_000_000L

        assertEquals(now - 7 * HealthSummaryPolicy.DAY_MS, HealthSummaryPolicy.cutoffFor(now, 7))
        assertEquals(now - HealthSummaryPolicy.DAY_MS, HealthSummaryPolicy.cutoffFor(now, 1))
    }

    @Test
    fun `weight is reported in kilograms`() {
        assertEquals(72.5, HealthSummaryPolicy.weightKgFromRecord(72_500.0), 0.0001)
        assertEquals(0.0, HealthSummaryPolicy.weightKgFromRecord(0.0), 0.0001)
    }
}
