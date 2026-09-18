package com.openminis.app.roleplay

import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/RoleplayRunContext.kt` (the memory half of
 * the persona block) (Mangi-11/Eta @ c15de97). The memory reaches the model with the revision it was
 * read at, and a character without memory gets no empty tags.
 */
class CharacterMemoryPromptTest {

    private fun card(): CharacterCard = CharacterCardCodec.decodeJson(
        JSONObject()
            .put("spec", "chara_card_v2")
            .put("data", JSONObject().put("name", "Ada").put("description", "a tester"))
            .toString(),
    )

    private val memory = CharacterMemorySnapshot(
        content = "# 核心记忆\n\nAda met Bo at the market.",
        revision = "abc123",
        byteSize = 40,
        lineCount = 3,
    )

    @Test
    fun `a character without memory gets no memory block`() {
        val prompt = CharacterPrompt.personaPrompt(card(), "Bo", "")

        assertFalse(prompt.contains("character_memory_core"))
        assertFalse(prompt.contains("story memory is enabled"))
    }

    @Test
    fun `the memory block carries the text and the revision it was read at`() {
        val prompt = CharacterPrompt.personaPrompt(card(), "Bo", "", memory = memory, contextWindow = 128_000)

        assertTrue(prompt.contains("story memory is enabled"))
        assertTrue(prompt.contains("character_memory_revision=abc123"))
        assertTrue(prompt.contains("<character_memory_core>"))
        assertTrue(prompt.contains("Ada met Bo at the market."))
        assertTrue(prompt.contains("</character_memory_core>"))
    }

    @Test
    fun `an empty memory reads as no memory`() {
        val empty = CharacterMemorySnapshot(content = "   ", revision = "x")

        val prompt = CharacterPrompt.personaPrompt(card(), "Bo", "", memory = empty, contextWindow = 128_000)

        assertFalse(prompt.contains("character_memory_core"))
    }

    @Test
    fun `a memory longer than the budget is trimmed with a pointer to the rest`() {
        val long = CharacterMemorySnapshot(
            content = "# 核心记忆\n" + "x".repeat(80_000),
            revision = "long",
            byteSize = 80_000,
            lineCount = 2,
        )

        val prompt = CharacterPrompt.personaPrompt(card(), "Bo", "", memory = long, contextWindow = 128_000)

        assertTrue("the block is bounded", prompt.length < 40_000)
        assertTrue("the model is told where the rest lives", prompt.contains("memory_get"))
    }

    @Test
    fun `the projection hands the memory to the block`() {
        val projection = RoleplayTurnProjection.project(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hello")),
            card = card(),
            userName = "Bo",
            userDescription = "",
            contextWindow = 128_000,
            memory = memory,
        )

        assertTrue(projection.characterBlock.contains("character_memory_revision=abc123"))
        assertTrue(projection.characterBlock.contains("Ada met Bo at the market."))
    }
}
