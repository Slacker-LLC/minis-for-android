package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledRunPromptPolicyTest {
    @Test
    fun `scheduled session appends fixed soft safety note last`() {
        val prompt = ScheduledRunPromptPolicy.appendSafetyNote("Bot role\n\nSession override", "scheduled")
        assertTrue(prompt!!.startsWith("Bot role"))
        assertTrue(prompt.contains("Session override"))
        assertTrue(prompt.endsWith(ScheduledRunPromptPolicy.SAFETY_NOTE))
    }

    @Test
    fun `unattended delegation and wake turns append generic soft safety note`() {
        for (source in listOf("bot_delegation", "manual")) {
            val prompt = ScheduledRunPromptPolicy.appendSafetyNote("system", source, unattended = true)
            assertTrue(prompt!!.endsWith(ScheduledRunPromptPolicy.UNATTENDED_SAFETY_NOTE))
        }
    }

    @Test
    fun `ordinary sessions do not change their prompt`() {
        assertEquals("system", ScheduledRunPromptPolicy.appendSafetyNote("system", null))
        assertEquals("system", ScheduledRunPromptPolicy.appendSafetyNote("system", "bot_delegation"))
        assertEquals(null, ScheduledRunPromptPolicy.appendSafetyNote(null, "manual"))
    }
}
