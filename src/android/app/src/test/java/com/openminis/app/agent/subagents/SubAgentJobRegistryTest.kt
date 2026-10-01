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

    // ── Persistence and interrupted runs ───────────────────────────────────

    private class MemStore(var saved: List<SubAgentJob> = emptyList()) : SubAgentJobStore {
        override fun load() = saved
        override fun save(jobs: List<SubAgentJob>) { saved = jobs }
    }

    @Test
    fun `queued and running jobs come back as interrupted, finished ones as they were`() {
        val store = MemStore()
        val r = SubAgentJobRegistry(1, 5, clock = { 1_000L }, idFactory = { "job${++ids}-xxxxxx" }, store = store)
        val done = (r.submit() as SubAgentAdmission.Started).job
        r.finish(done.id, SubAgentJobState.DONE, "ok")
        val running = (r.submit() as SubAgentAdmission.Started).job
        val queued = (r.submit() as SubAgentAdmission.Queued).job

        val reloaded = SubAgentJobRegistry(1, 5, clock = { 9_000L }, store = store)
        assertEquals(SubAgentJobState.DONE, reloaded.get(done.id)!!.state)
        assertEquals(SubAgentJobState.INTERRUPTED, reloaded.get(running.id)!!.state)
        assertEquals(SubAgentJobState.INTERRUPTED, reloaded.get(queued.id)!!.state)
        assertNull("nothing is waiting in a queue after a restart", reloaded.queuePosition(queued.id))
    }

    @Test
    fun `resume restarts an interrupted job and marks it resumed`() {
        val store = MemStore()
        val r = SubAgentJobRegistry(2, 5, store = store)
        val job = (r.submit() as SubAgentAdmission.Started).job
        val reloaded = SubAgentJobRegistry(2, 5, store = store)
        val admission = reloaded.resume(job.id) as SubAgentAdmission.Started
        assertEquals(SubAgentJobState.RUNNING, admission.job.state)
        assertTrue(admission.job.resumed)
    }

    @Test
    fun `resume queues when no slot is free`() {
        val store = MemStore()
        val r = SubAgentJobRegistry(1, 5, store = store)
        val lost = (r.submit() as SubAgentAdmission.Started).job
        val reloaded = SubAgentJobRegistry(1, 5, store = store)
        reloaded.submit() // takes the only slot
        val admission = reloaded.resume(lost.id)
        assertTrue(admission is SubAgentAdmission.Queued)
    }

    @Test
    fun `only an interrupted job can be resumed`() {
        val r = registry(maxConcurrent = 5)
        val running = (r.submit() as SubAgentAdmission.Started).job
        assertNull(r.resume(running.id))
        assertNull(r.resume("missing"))
        r.finish(running.id, SubAgentJobState.DONE, "x")
        assertNull(r.resume(running.id))
    }

    @Test
    fun `history is bounded and an active job is never dropped`() {
        val r = SubAgentJobRegistry(maxConcurrent = 100, maxQueued = 100, clock = { 1L })
        val active = (r.submit() as SubAgentAdmission.Started).job
        repeat(SubAgentJobRegistry.MAX_KEPT + 10) {
            val j = (r.submit() as SubAgentAdmission.Started).job
            r.finish(j.id, SubAgentJobState.DONE, "x")
        }
        assertTrue(r.jobs.value.size <= SubAgentJobRegistry.MAX_KEPT)
        assertNotNull("the still-running job survives the trimming", r.get(active.id))
    }

    @Test
    fun `the brief is dropped once a job finishes`() {
        val r = registry()
        val job = (r.submit(brief = "the whole brief") as SubAgentAdmission.Started).job
        assertEquals("the whole brief", r.get(job.id)!!.brief)
        r.finish(job.id, SubAgentJobState.DONE, "x")
        assertNull(r.get(job.id)!!.brief)
    }

    private fun SubAgentJobRegistry.submit(brief: String?): SubAgentAdmission =
        submit("p1", "General Sub Agent", "t", wait = false, maxMinutes = 10, brief = brief)
}

