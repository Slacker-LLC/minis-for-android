package com.openminis.app.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentTurnHandleTest {
    @Test
    fun stoppingOneRequestCannotCompleteOrCancelTheNextRequest() = runBlocking {
        val first = AgentTurnHandle()
        val next = AgentTurnHandle()
        val firstJob = Job()
        val nextJob = Job()
        first.bind(firstJob)
        next.bind(nextJob)
        first.record(AgentTurnOutcome.Completed)
        first.cancel()
        assertEquals(AgentTurnOutcome.Cancelled, first.result.await())
        assertFalse(next.result.isCompleted)
        assertFalse(nextJob.isCancelled)
        next.record(AgentTurnOutcome.Completed)
        nextJob.complete()
        assertEquals(AgentTurnOutcome.Completed, next.result.await())
    }

    @Test
    fun resultWaitsForWorkerCleanupAndKeepsItsFailure() = runBlocking {
        val request = AgentTurnHandle()
        val job = Job()
        val cleanup = Job(job)
        request.bind(job)
        request.record(AgentTurnOutcome.Failed("provider_disconnected"))
        job.complete()
        assertFalse(request.result.isCompleted)
        cleanup.complete()
        assertEquals(AgentTurnOutcome.Failed("provider_disconnected"), request.result.await())
    }

    @Test
    fun unstartedOrInterruptedRequestsNeverBecomeSuccess() = runBlocking {
        val rejected = AgentTurnHandle()
        rejected.reject("session_busy")
        assertEquals(AgentTurnOutcome.Rejected("session_busy"), rejected.result.await())
        val interrupted = AgentTurnHandle()
        val job = Job()
        interrupted.bind(job)
        interrupted.record(AgentTurnOutcome.NeedsAttention("context_exhausted"))
        job.complete()
        assertEquals(AgentTurnOutcome.NeedsAttention("context_exhausted"), interrupted.result.await())
    }
}
