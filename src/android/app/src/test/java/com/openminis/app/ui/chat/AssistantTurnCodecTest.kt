package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.MediaRef
import com.openminis.app.data.storage.MediaStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssistantTurnCodecTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `generated JPEG is persisted restored and rendered as media in stream order`() {
        val base = temp.newFolder("media")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val ref = MediaStore(base).saveMedia(bytes, "image/jpeg", "session")
        assertTrue(ref.relativePath.endsWith(".jpg"))
        val media = AssistantTurnCodec.mediaBlock(ref, base)
        val blocks = listOf(
            AssistantBlock("text1", "text", content = "first"),
            media,
            AssistantBlock("tool1", "tool_use", toolName = "shell_execute", toolTitle = "run", thoughtSignature = "sig"),
            AssistantBlock("text2", "text", content = "last"),
        )

        val turn = AssistantTurnCodec.build(blocks, 0, mapOf("tool1" to """{"command":"pwd"}"""), base)
        val json = JSONArray(turn.partsJson)
        assertEquals(listOf("text", "mediaRef", "toolUse", "text"), (0 until json.length()).map { json.getJSONObject(it).getString("type") })
        assertArrayEquals(bytes, (turn.parts[1] as AgentContentPart.ImageData).data)
        assertEquals("image/jpeg", (turn.parts[1] as AgentContentPart.ImageData).mimeType)
        assertEquals("sig", json.getJSONObject(2).getJSONObject("value").getString("thoughtSignature"))
        assertFalse(turn.partsJson.contains("base64"))

        val restored = AssistantTurnCodec.restoreMediaBlock(json.getJSONObject(1).getJSONObject("value"), base)
        assertEquals(media, restored)
        val items = buildFlatChatItems(listOf(ChatMessage(id = "a", role = "assistant", content = "", toolBlocks = listOf(restored))))
        assertEquals(1, items.filterIsInstance<FlatChatItem.AssistantMedia>().size)
        assertTrue(items.none { it is FlatChatItem.AssistantToolUse || it is FlatChatItem.AssistantTyping })
    }

    @Test
    fun `a later turn never repeats media from a previous turn`() {
        val base = temp.newFolder("media")
        val ref = MediaStore(base).saveMedia(byteArrayOf(1), "image/png", "session")
        val blocks = listOf(AssistantTurnCodec.mediaBlock(ref, base), AssistantBlock("text", "text", "new"))
        val turn = AssistantTurnCodec.build(blocks, 1, emptyMap(), base)
        assertEquals(1, JSONArray(turn.partsJson).length())
        assertEquals(listOf(AgentContentPart.Text("new")), turn.parts)
    }

    @Test
    fun `skipped content cannot produce stray commas or empty tool names`() {
        val parts = listOf(
            AgentContentPart.ToolUse("blank", "", JSONObject()),
            AgentContentPart.Text("a \"quote\"\nnewline"),
            AgentContentPart.ToolResult("result", "shell_execute", "ok"),
            AgentContentPart.ImageData(byteArrayOf(1), "image/png"),
            AgentContentPart.Text("end"),
        )
        val json = JSONArray(AssistantTurnCodec.encodeParts(parts))
        assertEquals(2, json.length())
        assertEquals("a \"quote\"\nnewline", json.getJSONObject(0).getString("value"))
        assertEquals("end", json.getJSONObject(1).getString("value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `restored media cannot read outside media storage`() {
        AssistantTurnCodec.mediaBlock(MediaRef("bad", "../secret", "image/png"), temp.newFolder("media"))
    }

    @Test(expected = java.io.IOException::class)
    fun `missing output fails persistence instead of silently dropping its reference`() {
        val base = temp.newFolder("media")
        val media = AssistantTurnCodec.mediaBlock(MediaRef("missing", "session/missing.png", "image/png"), base)
        AssistantTurnCodec.build(listOf(media), 0, emptyMap(), base)
    }
}
