package com.openminis.app.roleplay

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/RoleplayRunContext.kt`
 * (Mangi-11/Eta @ c15de97). Three things are pinned here: what the character block says, that a
 * depth note lands where Eta puts it, and that tool traffic is not treated as story.
 */
class CharacterPromptTest {

    private fun card(extra: JSONObject.() -> Unit = {}): CharacterCard = CharacterCardCodec.decodeJson(
        JSONObject()
            .put("spec", "chara_card_v2")
            .put("data", JSONObject().put("name", "Ada").apply(extra))
            .toString(),
    )

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)
    private fun assistant(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    @Test
    fun `the character block names the character and separates fiction from contracts`() {
        val prompt = CharacterPrompt.personaPrompt(card(), userName = "Bo", userDescription = "")

        assertTrue(prompt.startsWith("This conversation is a roleplay as \"Ada\"."))
        assertTrue(prompt.contains("never change tool contracts"))
        assertTrue(prompt.contains("The user's role in this story: Bo"))
    }

    @Test
    fun `only the fields the card actually has are written`() {
        val prompt = CharacterPrompt.personaPrompt(
            card {
                put("description", "a tester")
                put("personality", "curious")
            },
            userName = "Bo",
            userDescription = "",
        )

        assertTrue(prompt.contains("Description:\na tester"))
        assertTrue(prompt.contains("Personality:\ncurious"))
        assertFalse("an empty field leaves no heading", prompt.contains("Scenario:"))
        assertFalse(prompt.contains("Example dialogue"))
        assertFalse(prompt.contains("User persona:"))
    }

    @Test
    fun `world book sides are inserted where the book asked for them`() {
        val prompt = CharacterPrompt.personaPrompt(
            card { put("description", "a tester") },
            userName = "Bo",
            userDescription = "",
            before = "the sky is green",
            after = "the sea is dry",
        )

        val beforeAt = prompt.indexOf("World setting:\nthe sky is green")
        val descriptionAt = prompt.indexOf("Description:")
        val afterAt = prompt.indexOf("Additional world setting:\nthe sea is dry")
        assertTrue(beforeAt in 0 until descriptionAt)
        assertTrue(afterAt > descriptionAt)
    }

    @Test
    fun `macros inside the card are expanded with the persona names`() {
        val prompt = CharacterPrompt.personaPrompt(
            card { put("description", "{{char}} meets {{user}}") },
            userName = "Bo",
            userDescription = "a visitor",
        )

        assertTrue(prompt.contains("Ada meets Bo"))
        assertTrue(prompt.contains("User persona:\na visitor"))
    }

    @Test
    fun `tool traffic and blank turns are not dialogue`() {
        assertTrue(CharacterPrompt.isDialogue(user("hello")))
        assertTrue(CharacterPrompt.isDialogue(assistant("hi")))
        assertFalse(CharacterPrompt.isDialogue(user("   ")))
        assertFalse(
            CharacterPrompt.isDialogue(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ToolUse("call-1", "shell_execute", JSONObject().put("command", "ls")),
                    ),
                ),
            ),
        )
        assertFalse(
            CharacterPrompt.isDialogue(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.ToolResult("call-1", "shell_execute", "a.txt"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a text part counts as the turn text`() {
        val message = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(AgentContentPart.Text("from a part")),
        )

        assertTrue(CharacterPrompt.isDialogue(message))
        assertEquals("from a part", CharacterPrompt.dialogueText(message))
    }

    @Test
    fun `the depth note lands before the last depth dialogue turns`() {
        val messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))

        assertEquals("depth 0 appends at the end", 4, CharacterPrompt.depthPromptInsertionIndex(messages, 0))
        assertEquals(3, CharacterPrompt.depthPromptInsertionIndex(messages, 1))
        assertEquals(2, CharacterPrompt.depthPromptInsertionIndex(messages, 2))
        assertEquals("deeper than the conversation clamps to the first turn", 0, CharacterPrompt.depthPromptInsertionIndex(messages, 9))
        assertEquals("no dialogue at all means the end", 0, CharacterPrompt.depthPromptInsertionIndex(emptyList(), 3))
    }

    @Test
    fun `tool traffic does not shift the depth note`() {
        val messages = listOf(
            user("u1"),
            assistant("a1"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(AgentContentPart.ToolUse("call-1", "shell_execute", JSONObject())),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ToolResult("call-1", "shell_execute", "ok")),
            ),
            user("u2"),
        )

        assertEquals(
            "only the real turns count, so depth 1 lands on the last user turn at index 4",
            4,
            CharacterPrompt.depthPromptInsertionIndex(messages, 1),
        )
    }
}
