package com.openminis.app.agent.subagents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentJobRegistryTest {
    private var ids = 0
    private fun registry(maxConcurrent: Int = 2, maxQueued: Int = 2) =
        SubAgentJobRegistry(maxConcurrent, maxQueued, clock = { 1_000L }, idFactory = { "job${++ids}-" + "x".repeat(6) })

    private fun SubAgentJobRegistry.submit(parent: String = "p1", title: String = "t") =
        submit(parent, "General Sub Agent", title, wait = false, maxMinutes = 10)

    @Test
    fun `jobs start until the concurrency cap and then queue in order`() {
        val r = registry()
        val a = r.submit() as SubAgentAdmission.Started
        val b = r.submit() as SubAgentAdmission.Started
        val c = r.submit() as SubAgentAdmission.Queued
        val d = r.submit() as SubAgentAdmission.Queued
        assertEquals(SubAgentJobState.RUNNING, a.job.state)
        assertEquals(SubAgentJobState.RUNNING, b.job.state)
        assertEquals(1, c.position)
        assertEquals(2, d.position)
        assertEquals(1, r.queuePosition(c.job.id))
    }

    @Test
    fun `a freed slot promotes the oldest queued job`() {
        val r = registry(maxConcurrent = 1)
        val a = r.submit() as SubAgentAdmission.Started
        val b = r.submit() as SubAgentAdmission.Queued
        assertNull("no slot free yet", r.promoteNext())
        r.finish(a.job.id, SubAgentJobState.DONE, "ok")
        val promoted = r.promoteNext()!!
        assertEquals(b.job.id, promoted.id)
        assertEquals(SubAgentJobState.RUNNING, promoted.state)
        assertNull(r.queuePosition(b.job.id))
    }

    @Test
    fun `the first terminal state wins`() {
        val r = registry()
        val a = r.submit() as SubAgentAdmission.Started
        assertNotNull(r.finish(a.job.id, SubAgentJobState.CANCELLED, "partial"))
        assertNull("a later 'done' must not overwrite a cancel", r.finish(a.job.id, SubAgentJobState.DONE, "late"))
        assertEquals(SubAgentJobState.CANCELLED, r.get(a.job.id)!!.state)
        assertEquals("partial", r.get(a.job.id)!!.resultText)
    }

    @Test
    fun `finishing a queued job removes it from the queue`() {
        val r = registry(maxConcurrent = 1)
        r.submit()
        val b = r.submit() as SubAgentAdmission.Queued
        r.finish(b.job.id, SubAgentJobState.CANCELLED, null)
        assertNull(r.queuePosition(b.job.id))
    }

    @Test
    fun `the wire word for a finished run is completed`() {
        assertEquals("completed", SubAgentJobState.DONE.wire)
        assertEquals("running", SubAgentJobState.RUNNING.wire)
        assertEquals("timeout", SubAgentJobState.TIMEOUT.wire)
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `a full queue rejects instead of growing without bound`() {
        val r = registry(maxConcurrent = 1, maxQueued = 1)
        r.submit(); r.submit()
        val rejected = r.submit()
        assertTrue(rejected is SubAgentAdmission.Rejected)
        assertEquals(SubAgentRejection.QUEUE_FULL, (rejected as SubAgentAdmission.Rejected).reason)
    }

    @Test
    fun `lookup never crosses conversations`() {
        val r = registry(maxConcurrent = 5)
        val mine = (r.submit(parent = "chat-A") as SubAgentAdmission.Started).job
        assertTrue(r.lookup("chat-A", mine.id) is SubAgentLookup.Found)
        assertEquals(SubAgentLookup.NotFound, r.lookup("chat-B", mine.id))
        assertEquals(SubAgentLookup.NotFound, r.lookup("chat-B", mine.id.take(5)))
    }

    @Test
    fun `an ambiguous or empty id is not resolved`() {
        val r = registry(maxConcurrent = 5)
        r.submit(); r.submit()
        // Both generated ids start with "job".
        assertTrue(r.lookup("p1", "job") is SubAgentLookup.Ambiguous)
        assertEquals(SubAgentLookup.NotFound, r.lookup("p1", "  "))
        assertEquals(SubAgentLookup.NotFound, r.lookup("p1", "nope"))
    }

    @Test
    fun `finishing needs a terminal state and an unknown job is ignored`() {
        val r = registry()
        val a = r.submit() as SubAgentAdmission.Started
        try {
            r.finish(a.job.id, SubAgentJobState.RUNNING, null)
            org.junit.Assert.fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(r.finish("missing", SubAgentJobState.DONE, null))
    }
}
