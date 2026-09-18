package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from the ColorOS memory entries in Eta
 * `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97). The bounds are the point: the
 * query reaches another app's database, so what a caller may ask for is a decision, not a guess.
 */
class ColorOsMemoryQueryPolicyTest {

    @Test
    fun `a keyword is trimmed and an empty one is no keyword at all`() {
        assertEquals("快递", ColorOsMemoryQueryPolicy.query("  快递 "))
        assertNull(ColorOsMemoryQueryPolicy.query("   "))
        assertNull(ColorOsMemoryQueryPolicy.query(null))
    }

    @Test
    fun `a keyword past the bound is refused rather than cut`() {
        val atBound = "a".repeat(ColorOsMemoryQueryPolicy.MAX_QUERY_CHARS)

        assertFalse(ColorOsMemoryQueryPolicy.isQueryTooLong(atBound))
        assertTrue(
            ColorOsMemoryQueryPolicy.isQueryTooLong(
                "a".repeat(ColorOsMemoryQueryPolicy.MAX_QUERY_CHARS + 1),
            ),
        )
        assertFalse(
            "the bound is on the trimmed keyword",
            ColorOsMemoryQueryPolicy.isQueryTooLong("  $atBound  "),
        )
    }

    @Test
    fun `the row count is clamped into the declared range`() {
        assertEquals(ColorOsMemoryQueryPolicy.DEFAULT_LIMIT, ColorOsMemoryQueryPolicy.clampLimit(null))
        assertEquals(1, ColorOsMemoryQueryPolicy.clampLimit(0))
        assertEquals(1, ColorOsMemoryQueryPolicy.clampLimit(-5))
        assertEquals(7, ColorOsMemoryQueryPolicy.clampLimit(7))
        assertEquals(ColorOsMemoryQueryPolicy.MAX_LIMIT, ColorOsMemoryQueryPolicy.clampLimit(500))
    }
}
