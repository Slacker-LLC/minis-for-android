package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GLOBAL.md is user-maintained and can grow forever, so the injected form is
 * budgeted by the model's context window and keeps a heading index when it is
 * cut. These cases pin the clamp, the index and the unchanged fast path.
 */
class MemoryInjectionBudgetTest {

    @Test
    fun `budget scales with the context window and clamps at both ends`() {
        assertEquals(
            MemoryInjectionBudget.MIN_CORE_CHARS,
            MemoryInjectionBudget.coreBudgetChars(1_000),
        )
        assertEquals(8_000, MemoryInjectionBudget.coreBudgetChars(128_000))
        assertEquals(16_000, MemoryInjectionBudget.coreBudgetChars(256_000))
        assertEquals(
            MemoryInjectionBudget.MAX_CORE_CHARS,
            MemoryInjectionBudget.coreBudgetChars(8_000_000),
        )
    }

    @Test
    fun `missing or nonsense window falls back to the default`() {
        assertEquals(
            MemoryInjectionBudget.coreBudgetChars(MemoryInjectionBudget.DEFAULT_CONTEXT_WINDOW),
            MemoryInjectionBudget.coreBudgetChars(null),
        )
        assertEquals(
            MemoryInjectionBudget.coreBudgetChars(MemoryInjectionBudget.DEFAULT_CONTEXT_WINDOW),
            MemoryInjectionBudget.coreBudgetChars(0),
        )
        assertEquals(
            MemoryInjectionBudget.coreBudgetChars(MemoryInjectionBudget.DEFAULT_CONTEXT_WINDOW),
            MemoryInjectionBudget.coreBudgetChars(-5),
        )
    }

    @Test
    fun `content inside the budget is injected verbatim`() {
        val content = "# Preferences\n- prefers tabs\n"

        assertEquals(content, MemoryInjectionBudget.bound(content, 128_000))
    }

    @Test
    fun `truncated content keeps a heading index and a pointer to memory_get`() {
        val content = buildString {
            append("# Preferences\n")
            append("x".repeat(10_000))
            append("\n## Projects\n")
            append("y".repeat(10_000))
        }

        val bounded = MemoryInjectionBudget.bound(content, 128_000)

        assertTrue(bounded.length < content.length + 2_000)
        assertTrue(bounded.startsWith("# Preferences"))
        assertTrue(bounded.contains("truncated at 8000 characters"))
        assertTrue(bounded.contains("## Projects"))
        assertTrue(bounded.contains("memory_get"))
    }

    @Test
    fun `truncation without headings still explains itself`() {
        val content = "z".repeat(40_000)

        val bounded = MemoryInjectionBudget.bound(content, 128_000)

        assertTrue(bounded.contains("truncated at 8000 characters"))
        assertTrue(bounded.contains("memory_get"))
        assertEquals(false, bounded.contains("headings in the full file"))
    }

    @Test
    fun `heading index only takes markdown headings`() {
        val index = MemoryInjectionBudget.headingIndex(
            "# One\nnot a heading\n## Two\n### Too deep\n#nospace\n",
        )

        assertEquals("# One\n## Two", index)
    }
}
