package com.openminis.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-assistant-home] Rules behind the home page's 2×2 grid: which cards
 * exist, how a persisted selection decodes (including corrupted / future
 * values), how toggling behaves, the greeting bucket, and the memory numbers.
 */
class AssistantQuickActionTest {

    @Test
    fun `absent or unreadable selection falls back to the roadmap order`() {
        assertEquals(AssistantQuickAction.DEFAULT_ORDER, AssistantQuickAction.parseSelection(null))
        assertEquals(AssistantQuickAction.DEFAULT_ORDER, AssistantQuickAction.parseSelection(""))
        assertEquals(AssistantQuickAction.DEFAULT_ORDER, AssistantQuickAction.parseSelection("   "))
        assertEquals(AssistantQuickAction.DEFAULT_ORDER, AssistantQuickAction.parseSelection("nope,also_nope"))
    }

    @Test
    fun `selection keeps order, drops duplicates and unknown ids`() {
        val parsed = AssistantQuickAction.parseSelection("memory,wechat,memory,from_the_future")
        assertEquals(
            listOf(AssistantQuickAction.MEMORY_PRESSURE, AssistantQuickAction.OPEN_WECHAT),
            parsed,
        )
        // A future build's unknown id must not resurrect the defaults either.
        assertFalse(parsed.contains(AssistantQuickAction.BROWSE_WEB))
    }

    @Test
    fun `selection is capped at the four cards the grid can show`() {
        // Only four actions exist today, so the cap is exercised through the
        // serialized form: a fifth entry cannot be represented.
        val serialized = AssistantQuickAction.serialize(
            AssistantQuickAction.DEFAULT_ORDER + AssistantQuickAction.ANALYZE_SCREEN,
        )
        assertEquals("screen,wechat,web,memory", serialized)
        assertEquals(4, AssistantQuickAction.parseSelection(serialized).size)
    }

    @Test
    fun `toggle adds, removes and refuses to empty the grid`() {
        val two = listOf(AssistantQuickAction.ANALYZE_SCREEN, AssistantQuickAction.BROWSE_WEB)
        assertEquals(
            listOf(AssistantQuickAction.ANALYZE_SCREEN, AssistantQuickAction.BROWSE_WEB, AssistantQuickAction.MEMORY_PRESSURE),
            AssistantQuickAction.toggle(two, AssistantQuickAction.MEMORY_PRESSURE),
        )
        assertEquals(
            listOf(AssistantQuickAction.BROWSE_WEB),
            AssistantQuickAction.toggle(two, AssistantQuickAction.ANALYZE_SCREEN),
        )
        // The last remaining card cannot be switched off — a home page with an
        // empty grid would be a dead end.
        val single = listOf(AssistantQuickAction.BROWSE_WEB)
        assertEquals(single, AssistantQuickAction.toggle(single, AssistantQuickAction.BROWSE_WEB))
    }

    @Test
    fun `greeting bucket covers the clock and clamps out-of-range hours`() {
        assertEquals(GreetingPeriod.NIGHT, greetingPeriodFor(0))
        assertEquals(GreetingPeriod.NIGHT, greetingPeriodFor(4))
        assertEquals(GreetingPeriod.MORNING, greetingPeriodFor(5))
        assertEquals(GreetingPeriod.MORNING, greetingPeriodFor(11))
        assertEquals(GreetingPeriod.AFTERNOON, greetingPeriodFor(12))
        assertEquals(GreetingPeriod.AFTERNOON, greetingPeriodFor(17))
        assertEquals(GreetingPeriod.EVENING, greetingPeriodFor(18))
        assertEquals(GreetingPeriod.EVENING, greetingPeriodFor(22))
        assertEquals(GreetingPeriod.NIGHT, greetingPeriodFor(23))
        // Clamped: a nonsense hour must not produce a nonsense bucket. A
        // negative hour folds to 0 (night), an hour past 23 to night as well.
        assertEquals(GreetingPeriod.NIGHT, greetingPeriodFor(-3))
        assertEquals(GreetingPeriod.NIGHT, greetingPeriodFor(99))
    }

    @Test
    fun `memory snapshot reports pressure only on real signals`() {
        val calm = MemoryPressureSnapshot(
            totalBytes = 8L * 1024 * 1024 * 1024,
            availBytes = 4L * 1024 * 1024 * 1024,
            thresholdBytes = 256L * 1024 * 1024,
            lowMemory = false,
            appPssBytes = 300L * 1024 * 1024,
        )
        assertFalse(calm.underPressure)
        assertEquals(50, calm.usedPercent)

        assertTrue(calm.copy(lowMemory = true).underPressure)
        assertTrue(calm.copy(availBytes = 100L * 1024 * 1024).underPressure)
        // Degenerate readings never divide by zero and never claim pressure.
        val empty = calm.copy(totalBytes = 0, availBytes = 0, thresholdBytes = 0)
        assertEquals(0, empty.usedPercent)
        assertFalse(empty.underPressure)
    }

    @Test
    fun `byte formatting switches units at the right thresholds`() {
        assertEquals("512 B", formatMemoryBytes(512))
        assertEquals("2 KB", formatMemoryBytes(2048))
        assertEquals("3 MB", formatMemoryBytes(3L * 1024 * 1024))
        assertEquals("1.5 GB", formatMemoryBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("0 B", formatMemoryBytes(-1))
    }
}
