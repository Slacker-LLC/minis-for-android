package com.openminis.app.tools

import com.openminis.app.agent.AgentRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RalphReportParsingTest {

    @Test
    fun `a brace inside a report string does not cut the report`() {
        val reply = """
            Done with this round.
            {"status": "continue", "summary": "parsed the config", "evidence": "the file starts with {", "nextSteps": "fix it}"}
        """.trimIndent()
        val report = RalphTool.lastJsonObject(reply)
        assertEquals("continue", report?.getString("status"))
        assertEquals("the file starts with {", report?.getString("evidence"))
    }

    @Test
    fun `the last object wins and prose without one gives null`() {
        val reply = """Earlier I saw {"status":"blocked","summary":"old"} but now: {"status":"complete","summary":"new"}"""
        assertEquals("new", RalphTool.lastJsonObject(reply)?.getString("summary"))
        assertNull(RalphTool.lastJsonObject("no report here"))
        assertNull(RalphTool.lastJsonObject("broken { report"))
    }

    @Test
    fun `only a completed run counts as completed`() {
        fun result(status: String, timedOut: Boolean = false) = AgentRunner.PromptResult(status, "text", timedOut)
        assertTrue(result("Completed").completed)
        for (status in listOf("completed", "Busy", "Dropped", "Cancelled", "NeedsAttention", "Error", "Timeout", "Running", "Retrying")) {
            assertFalse(status, result(status).completed)
        }
        assertFalse("a timed-out wait is not a completion", result("Completed", timedOut = true).completed)
    }
}
