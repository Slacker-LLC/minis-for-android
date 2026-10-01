package com.openminis.app.agent.subagents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsSubAgentJobStoreTest {
    private fun job(result: String? = "r", brief: String? = "b") = SubAgentJob(
        id = "j1", parentSessionId = "p", agentName = "a", title = "t", wait = false, maxMinutes = 10,
        state = SubAgentJobState.DONE, createdAtMs = 1, resultText = result, brief = brief,
        modelEntryId = "e1", resumed = true,
    )

    @Test
    fun `a job round-trips with its spec`() {
        val back = PrefsSubAgentJobStore.decode(PrefsSubAgentJobStore.encode(listOf(job())))
        assertEquals(listOf(job()), back)
    }

    @Test
    fun `stored text is bounded`() {
        val big = "x".repeat(PrefsSubAgentJobStore.MAX_STORED_RESULT_CHARS + 500)
        val back = PrefsSubAgentJobStore.decode(PrefsSubAgentJobStore.encode(listOf(job(result = big))))
        assertEquals(PrefsSubAgentJobStore.MAX_STORED_RESULT_CHARS, back.single().resultText!!.length)
    }

    // ── Negative cases: a damaged payload means no history, never a crash ──

    @Test
    fun `missing, empty and damaged payloads load as no jobs`() {
        for (raw in listOf(null, "", "  ", "not json", "{}", """[{"id":1}]""")) {
            assertTrue(raw, PrefsSubAgentJobStore.decode(raw).isEmpty())
        }
    }
}
