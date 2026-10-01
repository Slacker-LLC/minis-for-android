package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentCardModelTest {
    private val started = """{"status":"running","job_id":"abc-123","agent":"Researcher","title":"Survey docs","model":"GPT-6 Sol","note":"x"}"""

    @Test
    fun `the call that started a run owns the card`() {
        val ref = parseSubAgentCardRef("""{"task":"read the docs","agent":"Researcher"}""", started)!!
        assertEquals("abc-123", ref.jobId)
        assertEquals("Researcher", ref.agent)
        assertEquals("GPT-6 Sol", ref.model)
        assertEquals("read the docs", ref.task)
        assertEquals("running", ref.statusAtCall)
    }

    @Test
    fun `a blocking call carries its result`() {
        val done = """{"status":"completed","job_id":"abc","agent":"A","result":"the answer"}"""
        assertEquals("the answer", parseSubAgentCardRef("""{"task":"t","wait":true}""", done)!!.resultAtCall)
    }

    // ── Negative cases: anything else is an ordinary tool row ──────────────

    @Test
    fun `later steer, cancel, status and resume calls get no card`() {
        for (action in listOf("steer", "cancel", "status", "resume")) {
            assertNull(action, parseSubAgentCardRef("""{"action":"$action","job_id":"abc"}""", started))
        }
    }

    @Test
    fun `an error reply, a plain-text reply and the older tool's answer get no card`() {
        assertNull(parseSubAgentCardRef("""{"task":"t"}""", """{"status":"error","error":"unknown_agent","job_id":"x"}"""))
        assertNull(parseSubAgentCardRef("""{"prompt":"t"}""", "The auth logic lives in src/auth.kt"))
        assertNull(parseSubAgentCardRef("not json", started.replace("job_id", "other")))
        assertNull(parseSubAgentCardRef("""{"task":"t"}""", ""))
    }

    @Test
    fun `finished statuses are recognised`() {
        assertTrue(isFinishedSubAgentStatus("completed"))
        assertTrue(isFinishedSubAgentStatus("interrupted"))
        assertFalse(isFinishedSubAgentStatus("running"))
        assertFalse(isFinishedSubAgentStatus("queued"))
        assertFalse(isFinishedSubAgentStatus(null))
    }
}
