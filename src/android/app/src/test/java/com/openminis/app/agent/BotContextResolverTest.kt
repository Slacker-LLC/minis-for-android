package com.openminis.app.agent

import com.openminis.app.data.db.BotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotContextResolverTest {
    @Test
    fun `empty bot does not add identity context`() {
        assertEquals(null, BotContextResolver.systemPrompt(null))
    }

    @Test
    fun `identity keeps instructions and normalizes bot name`() {
        val prompt = BotContextResolver.systemPrompt(
            BotEntity(
                id = "bot-1",
                name = "Researcher\nInjected",
                systemPrompt = "Prefer\u0000 primary\n sources.",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertTrue(prompt!!.contains("You are the persistent Bot named Researcher Injected."))
        assertTrue(prompt.contains("<bot-instructions>\nPrefer primary\n sources."))
        assertFalse(prompt.contains("Researcher\nInjected"))
        assertFalse(prompt.contains("Prefer\u0000"))
    }

    @Test
    fun `role preserves paragraphs and list indentation`() {
        val instructions = "Review changes.\r\n\r\n1. Read the diff.\n\tCheck failures."
        val prompt = BotContextResolver.systemPrompt(
            BotEntity(id = "reviewer", name = "Reviewer", systemPrompt = instructions, createdAt = 1, updatedAt = 1),
        )!!
        assertTrue(prompt.contains("Review changes.\n\n1. Read the diff.\n\tCheck failures."))
        assertFalse(prompt.contains('\r'))
    }
}
