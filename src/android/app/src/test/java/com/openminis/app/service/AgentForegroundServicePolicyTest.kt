package com.openminis.app.service

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentForegroundServicePolicyTest {

    @Test
    fun presenceOnlyDoesNotRequireForegroundService() {
        assertFalse(
            AgentForegroundServicePolicy.shouldRun(
                activeSessionCount = 0,
                presentSessionCount = 1,
            ),
        )
    }

    @Test
    fun activeAgentTurnRequiresForegroundService() {
        assertTrue(
            AgentForegroundServicePolicy.shouldRun(
                activeSessionCount = 1,
                presentSessionCount = 0,
            ),
        )
    }

    @Test
    fun stopThenResumeRequiresNewActiveTurn() {
        assertTrue(AgentForegroundServicePolicy.shouldRun(1, 1))
        assertFalse(AgentForegroundServicePolicy.shouldRun(0, 1))
        assertTrue(AgentForegroundServicePolicy.shouldRun(1, 1))
    }

    @Test
    fun processDeathDoesNotRequestStickyRestart() {
        assertEquals(Service.START_NOT_STICKY, AgentForegroundServicePolicy.restartMode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCountsAreRejected() {
        AgentForegroundServicePolicy.shouldRun(-1, 0)
    }
}
