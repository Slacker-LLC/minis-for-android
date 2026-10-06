package com.openminis.app.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceWaitTest {
    private class Clock { var t = 0L }

    private fun wait(
        c: Clock,
        budgetMs: Long,
        speaking: (Long) -> Boolean,
        paused: (Long) -> Boolean,
    ) = runBlocking {
        awaitUtteranceEnd(
            budgetMs = budgetMs,
            isSpeaking = { speaking(c.t) },
            isPaused = { paused(c.t) },
            now = { c.t },
            pollMs = 100,
            pause = { c.t += it },
        )
    }

    @Test
    fun `an utterance that finishes is done`() {
        val c = Clock()
        assertEquals(UtteranceEnd.DONE, wait(c, 10_000, { it < 2_000 }, { false }))
        assertTrue(c.t in 2_000..2_100)
    }

    @Test
    fun `a pause holds the queue even though the engine is silent, and does not eat the budget`() {
        val c = Clock()
        // Speaks until 1 s, paused from 1 s to 1 hour, speaks again until 1 h + 2 s.
        val hour = 3_600_000L
        val outcome = wait(
            c, budgetMs = 10_000,
            speaking = { it < 1_000 || (it in hour until hour + 2_000) },
            paused = { it in 1_000 until hour },
        )
        assertEquals("a long pause is not a broken progress listener", UtteranceEnd.DONE, outcome)
        assertTrue("it waited for the resume", c.t >= hour + 2_000)
    }

    @Test
    fun `a stop while paused ends the wait`() {
        val c = Clock()
        assertEquals(UtteranceEnd.DONE, wait(c, 10_000, { false }, { it < 5_000 }))
        assertTrue(c.t in 5_000..5_100)
    }

    @Test
    fun `an engine that never reports finishing still times out`() {
        val c = Clock()
        assertEquals(UtteranceEnd.TIMED_OUT, wait(c, 3_000, { true }, { false }))
        assertTrue(c.t in 3_000..3_200)
    }

    @Test
    fun `nothing playing and not paused is done at once`() {
        val c = Clock()
        assertEquals(UtteranceEnd.DONE, wait(c, 3_000, { false }, { false }))
        assertEquals(0L, c.t)
    }
}
