package com.openminis.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderCacheTest {
    @Test
    fun `identity section renders from supplied snapshot without file access`() {
        val file = SoulFile(
            metadata = SoulMetadata(
                name = "CacheName",
                emoji = "",
                style = "brief",
                lang = "en",
            ),
            body = "Prefer short answers.",
        )

        val prompt = SystemPromptBuilder.identitySection(file)

        assertTrue(prompt.contains("You are CacheName"))
        assertTrue(prompt.contains("Prefer short answers."))
        assertTrue(prompt.contains("Response style"))
    }
}
