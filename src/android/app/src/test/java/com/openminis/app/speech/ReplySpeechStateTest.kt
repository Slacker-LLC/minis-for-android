package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-reply-speech-state] State machine and progress contract tests for
 * message-level AI reply speech.
 */
class ReplySpeechStateTest {

    @Test
    fun `idle state is not active`() {
        val idle = ReplySpeechState()
        assertFalse(idle.isActive)
        assertEquals(ReplySpeechState.Status.IDLE, idle.status)
        assertEquals(0, idle.currentSentence)
    }

    @Test
    fun `reading and paused states are active`() {
        val reading = ReplySpeechState(
            activeMessageId = "msg-1",
            displayIndex = 1,
            status = ReplySpeechState.Status.READING,
            currentSentence = 1,
            totalSentences = 5,
        )
        assertTrue(reading.isActive)

        val paused = reading.copy(status = ReplySpeechState.Status.PAUSED)
        assertTrue(paused.isActive)
    }

    @Test
    fun `pause toggle switches between reading and paused`() {
        var state = ReplySpeechState(
            activeMessageId = "msg-1",
            displayIndex = 2,
            status = ReplySpeechState.Status.READING,
            currentSentence = 2,
            totalSentences = 4,
        )
        // Pause
        state = state.copy(status = ReplySpeechState.Status.PAUSED)
        assertEquals(ReplySpeechState.Status.PAUSED, state.status)

        // Resume
        state = state.copy(status = ReplySpeechState.Status.READING)
        assertEquals(ReplySpeechState.Status.READING, state.status)
        assertEquals(2, state.currentSentence)
    }

    @Test
    fun `switching message restarts from sentence 1 with new id`() {
        val stateA = ReplySpeechState(
            activeMessageId = "msg-A",
            displayIndex = 1,
            status = ReplySpeechState.Status.READING,
            currentSentence = 3,
            totalSentences = 5,
        )
        // User clicks speak on message B
        val stateB = ReplySpeechState(
            activeMessageId = "msg-B",
            displayIndex = 2,
            status = ReplySpeechState.Status.READING,
            currentSentence = 1,
            totalSentences = 3,
        )
        assertEquals("msg-B", stateB.activeMessageId)
        assertEquals(2, stateB.displayIndex)
        assertEquals(1, stateB.currentSentence)
        assertEquals(3, stateB.totalSentences)
    }

    @Test
    fun `completion state transitions to completed`() {
        val completed = ReplySpeechState(
            activeMessageId = "msg-1",
            displayIndex = 1,
            status = ReplySpeechState.Status.COMPLETED,
            currentSentence = 3,
            totalSentences = 3,
        )
        assertTrue(completed.isActive)
        assertEquals(ReplySpeechState.Status.COMPLETED, completed.status)
    }
}

