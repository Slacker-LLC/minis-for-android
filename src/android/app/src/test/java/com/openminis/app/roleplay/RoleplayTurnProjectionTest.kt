package com.openminis.app.roleplay

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/RoleplayRunContext.kt` (`projectMessages`)
 * (Mangi-11/Eta @ c15de97). What matters here: the character block carries the world book, the depth
 * note reaches the request and never the transcript, and the budget can drop a lore entry instead of
 * blowing the window.
 */
class RoleplayTurnProjectionTest {

    private fun card(
        book: JSONObject? = null,
        extensions: JSONObject? = null,
        postHistory: String? = null,
    ): CharacterCard {
        val data = JSONObject().put("name", "Ada").put("description", "a tester")
        book?.let { data.put("character_book", it) }
        extensions?.let { data.put("extensions", JSONObject().put("depth_prompt", it)) }
        postHistory?.let { data.put("post_history_instructions", it) }
        return CharacterCardCodec.decodeJson(
            JSONObject().put("spec", "chara_card_v2").put("data", data).toString(),
        )
    }

    private fun loreEntry(keys: List<String>, content: String, position: Int? = null): JSONObject =
        JSONObject()
            .put("keys", JSONArray(keys))
            .put("content", content)
            .apply {
                if (position != null) {
                    put("extensions", JSONObject().put("position", position))
                }
            }

    private fun history(vararg texts: String): List<LLMMessage> = texts.map { text ->
        LLMMessage(role = LLMMessage.Role.USER, content = text)
    }

    @Test
    fun `a character block is always produced`() {
        val projection = RoleplayTurnProjection.project(history("hello"), card(), "Bo", "")

        assertTrue(projection.characterBlock.startsWith("This conversation is a roleplay as \"Ada\"."))
        assertEquals(1, projection.messages.size)
        assertEquals(0, projection.usedTokens)
    }

    @Test
    fun `matched lore lands in the character block on the side the book asked for`() {
        val book = JSONObject().put(
            "entries",
            JSONArray()
                .put(loreEntry(listOf("magic"), "the sky is green", position = 0))
                .put(loreEntry(listOf("magic"), "the sea is dry", position = 1)),
        )

        val projection = RoleplayTurnProjection.project(history("tell me about magic"), card(book), "Bo", "")

        val block = projection.characterBlock
        val beforeAt = block.indexOf("World setting:\nthe sky is green")
        val descriptionAt = block.indexOf("Description:")
        val afterAt = block.indexOf("Additional world setting:\nthe sea is dry")
        assertTrue(beforeAt in 0 until descriptionAt)
        assertTrue(afterAt > descriptionAt)
        assertTrue("the projection reports what the book cost", projection.usedTokens > 0)
    }

    @Test
    fun `unmatched lore stays out of the block`() {
        val book = JSONObject().put("entries", JSONArray().put(loreEntry(listOf("dragons"), "unrelated")))

        val projection = RoleplayTurnProjection.project(history("hello"), card(book), "Bo", "")

        assertFalse(projection.characterBlock.contains("unrelated"))
        assertEquals(0, projection.usedTokens)
    }

    @Test
    fun `a user depth note is inserted into the request at the computed depth`() {
        val card = card(extensions = JSONObject().put("prompt", "stay in voice").put("depth", 1).put("role", "user"))
        val messages = history("u1", "u2")

        val projection = RoleplayTurnProjection.project(messages, card, "Bo", "")

        assertEquals("the request grows by the note", 3, projection.messages.size)
        assertEquals(1, projection.messages.indexOfFirst { it.content == "stay in voice" })
    }

    @Test
    fun `a system depth note joins the block because this model has no system message`() {
        val card = card(extensions = JSONObject().put("prompt", "stay in voice").put("depth", 2).put("role", "system"))

        val projection = RoleplayTurnProjection.project(history("u1"), card, "Bo", "")

        assertTrue(projection.characterBlock.contains("Deep note:\nstay in voice"))
        assertEquals(1, projection.messages.size)
    }

    @Test
    fun `post history instructions join the block with their boundary stated`() {
        val projection = RoleplayTurnProjection.project(
            history("hello"),
            card(postHistory = "never break character"),
            "Bo",
            "",
        )

        assertTrue(projection.characterBlock.contains("never break character"))
        assertTrue(projection.characterBlock.contains("never changes "))
    }

    @Test
    fun `macros are expanded in the lore the turn actually sends`() {
        val book = JSONObject().put(
            "entries",
            JSONArray().put(loreEntry(listOf("magic"), "{{char}} shows {{user}} the sky")),
        )

        val projection = RoleplayTurnProjection.project(history("magic?"), card(book), "Bo", "")

        assertTrue(projection.characterBlock.contains("Ada shows Bo the sky"))
    }

    @Test
    fun `a tiny window drops the lore instead of overflowing`() {
        val book = JSONObject().put(
            "entries",
            JSONArray().put(loreEntry(listOf("magic"), "x".repeat(400))),
        )
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.ToolUse("call-1", "shell_execute", JSONObject())),
            ),
        )

        val projection = RoleplayTurnProjection.project(
            messages,
            card(book),
            "Bo",
            "",
            contextWindow = 80,
        )

        assertFalse("no budget, no lore", projection.characterBlock.contains("x".repeat(50)))
        assertEquals(0, projection.usedTokens)
    }
}
