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
    fun `ordinary sessions do not change their prompt`() {
        assertEquals("system", ScheduledRunPromptPolicy.appendSafetyNote("system", null))
        assertEquals(null, ScheduledRunPromptPolicy.appendSafetyNote(null, "manual"))
    }
}
