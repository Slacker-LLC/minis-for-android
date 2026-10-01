package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class UiGenerationFenceTest {
    @Test
    fun `ref is valid only for its generation and fingerprint`() {
        var now = 1_000L
        val fence = UiGenerationFence(maxEntries = 2, ttlMs = 500L) { now }
        val generation = fence.nextGeneration()
        fence.install(generation, "screen-a", setOf("u1", "u2"))
        assertEquals(UiGenerationFence.Verdict.VALID, fence.validate(generation, "u1", "screen-a"))
        assertEquals(UiGenerationFence.Verdict.REF_NOT_FOUND, fence.validate(generation, "u3", "screen-a"))
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(generation, "u1", "screen-b"))
    }

    @Test
    fun `expired and evicted generations are stale`() {
        var now = 0L
        val fence = UiGenerationFence(maxEntries = 1, ttlMs = 100L) { now }
        val first = fence.nextGeneration()
        fence.install(first, "a", setOf("u1"))
        now = 101L
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(first, "u1", "a"))

        val second = fence.nextGeneration()
        fence.install(second, "b", setOf("u1"))
        val third = fence.nextGeneration()
        fence.install(third, "c", setOf("u1"))
        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(second, "u1", "b"))
        assertEquals(UiGenerationFence.Verdict.VALID, fence.validate(third, "u1", "c"))
    }

    @Test
    fun `refs are scoped to both session and display`() {
        val fence = UiGenerationFence()
        val generation = fence.nextGeneration()
        fence.install(generation, "same-screen", setOf("u1"), sessionId = "session-a", displayId = 7)

        assertEquals(
            UiGenerationFence.Verdict.VALID,
            fence.validate(generation, "u1", "same-screen", sessionId = "session-a", displayId = 7),
        )
        assertEquals(
            UiGenerationFence.Verdict.STALE,
            fence.validate(generation, "u1", "same-screen", sessionId = "session-b", displayId = 7),
        )
        assertEquals(
            UiGenerationFence.Verdict.STALE,
            fence.validate(generation, "u1", "same-screen", sessionId = "session-a", displayId = 0),
        )
    }

    @Test
    fun `clearing one display invalidates only its observations`() {
        val fence = UiGenerationFence()
        val virtual = fence.nextGeneration()
        fence.install(virtual, "vscreen", setOf("u1"), sessionId = "session-a", displayId = 7)
        val physical = fence.nextGeneration()
        fence.install(physical, "physical", setOf("u1"), sessionId = "session-a", displayId = 0)

        fence.clearDisplay(7)

        assertEquals(UiGenerationFence.Verdict.STALE, fence.validate(virtual, "u1", "vscreen", sessionId = "session-a", displayId = 7))
        assertEquals(UiGenerationFence.Verdict.VALID, fence.validate(physical, "u1", "physical", sessionId = "session-a", displayId = 0))
    }

    @Test
    fun `freshness separates a changed screen from an unusable ref`() {
        var now = 0L
        val fence = UiGenerationFence(maxEntries = 4, ttlMs = 100L) { now }
        val generation = fence.nextGeneration()
        fence.install(generation, "screen-a", setOf("u1"), sessionId = "s", displayId = 7)

        assertEquals(UiGenerationFence.Freshness.FRESH, fence.freshness(generation, "u1", "screen-a", sessionId = "s", displayId = 7))
        // A different fingerprint is a question, not a refusal.
        assertEquals(UiGenerationFence.Freshness.CONTENT_CHANGED, fence.freshness(generation, "u1", "screen-b", sessionId = "s", displayId = 7))
        // A cut-off scan can never prove "unchanged".
        assertEquals(UiGenerationFence.Freshness.CONTENT_CHANGED, fence.freshness(generation, "u1", "screen-a", currentTruncated = true, sessionId = "s", displayId = 7))
        // Unknown ref, foreign session/display and expiry stay refusals.
        assertEquals(UiGenerationFence.Freshness.REF_NOT_FOUND, fence.freshness(generation, "u9", "screen-a", sessionId = "s", displayId = 7))
        assertEquals(UiGenerationFence.Freshness.STALE, fence.freshness(generation, "u1", "screen-a", sessionId = "other", displayId = 7))
        assertEquals(UiGenerationFence.Freshness.STALE, fence.freshness(generation, "u1", "screen-a", sessionId = "s", displayId = 8))
        now = 101L
        assertEquals(UiGenerationFence.Freshness.STALE, fence.freshness(generation, "u1", "screen-a", sessionId = "s", displayId = 7))
    }

    @Test
    fun `lookup ignores screen content entirely`() {
        val fence = UiGenerationFence(maxEntries = 4, ttlMs = 1_000L) { 0L }
        val generation = fence.nextGeneration()
        fence.install(generation, "screen-a", setOf("u1"), truncated = true, sessionId = "s", displayId = 7)
        assertEquals(UiGenerationFence.Freshness.FRESH, fence.lookup(generation, "u1", "s", 7))
        assertEquals(UiGenerationFence.Freshness.REF_NOT_FOUND, fence.lookup(generation, "u2", "s", 7))
        assertEquals(UiGenerationFence.Freshness.STALE, fence.lookup(generation + 1, "u1", "s", 7))
    }
}
