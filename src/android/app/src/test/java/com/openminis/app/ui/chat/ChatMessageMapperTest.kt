package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored-row → UI/model mapping, tested without a ViewModel now that it stands on its own. */
class ChatMessageMapperTest {
    private val mapper = ChatMessageMapper(File("/tmp/none")) { null }

    private fun row(id: String, role: String, parts: JSONArray, order: Int, reasoning: String? = null, error: String? = null) =
        MessageEntity(id = id, sessionId = "s", role = role, partsJson = parts.toString(), createdAt = 1_000L + order, sortOrder = order, reasoningContent = reasoning, errorInfo = error)

    private fun text(v: String) = JSONObject().put("type", "text").put("value", v)

    private fun toolUse(id: String, name: String) = JSONObject().put("type", "toolUse")
        .put("value", JSONObject().put("toolUseId", id).put("name", name).put("input", "{}").put("description", "d"))

    private fun toolResult(id: String, output: String, success: Boolean = true) = JSONObject().put("type", "toolResult")
        .put("value", JSONObject().put("toolUseId", id).put("output", output).put("success", success))

    private fun parsed(rows: List<MessageEntity>) = rows.associate { it.id to runCatching { JSONArray(it.partsJson) } }

    private fun ui(vararg rows: MessageEntity) = mapper.toChatMessages(rows.toList(), parsed(rows.toList()))

    @Test
    fun `plain user and assistant text map one to one`() {
        val out = ui(
            row("u", "user", JSONArray().put(text("hi")), 0),
            row("a", "assistant", JSONArray().put(text("hello")), 1),
        )
        assertEquals(listOf("u", "a"), out.map { it.id })
        assertEquals("hi", out[0].content)
        assertEquals("hello", out[1].content)
    }

    @Test
    fun `a system reminder and the attached-files block are hidden from the bubble`() {
        val raw = "Look at this<system-reminder>internal</system-reminder><user-attached-files>x</user-attached-files>"
        val out = ui(row("u", "user", JSONArray().put(text(raw)), 0))
        assertEquals("Look at this", out.single().content)
        assertEquals("ab", mapper.stripAttachedFilesXml("a<user-attached-files>x</user-attached-files>b"))
    }

    @Test
    fun `a user row that is only a tool result is not a visible message`() {
        val out = ui(
            row("a", "assistant", JSONArray().put(toolUse("t1", "shell")), 0),
            row("r", "user", JSONArray().put(toolResult("t1", "done")), 1),
        )
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `a tool result is merged into its call, and a failure shows as failed`() {
        val ok = ui(
            row("a", "assistant", JSONArray().put(toolUse("t1", "shell")), 0),
            row("r", "user", JSONArray().put(toolResult("t1", "line one\nline two")), 1),
        ).single().toolBlocks.single()
        assertEquals(ToolBlockStatus.SUCCESS, ok.toolStatus)
        assertEquals("line one\nline two", ok.content)

        val failed = ui(
            row("a", "assistant", JSONArray().put(toolUse("t1", "shell")), 0),
            row("r", "user", JSONArray().put(toolResult("t1", "boom", success = false)), 1),
        ).single().toolBlocks.single()
        assertEquals(ToolBlockStatus.FAILED, failed.toolStatus)
    }

    @Test
    fun `a cancelled tool result is shown as cancelled`() {
        val cancelled = ui(
            row("a", "assistant", JSONArray().put(toolUse("t1", "shell")), 0),
            row("r", "user", JSONArray().put(toolResult("t1", ChatViewModel.CANCELLED_MARKER, success = false)), 1),
        ).single().toolBlocks.single()
        assertEquals(ToolBlockStatus.CANCELLED, cancelled.toolStatus)
    }

    @Test
    fun `consecutive assistant rows become one message that keeps every source id`() {
        val out = ui(
            row("u", "user", JSONArray().put(text("go")), 0),
            row("a1", "assistant", JSONArray().put(text("first")).put(toolUse("t1", "shell")), 1),
            row("r1", "user", JSONArray().put(toolResult("t1", "ok")), 2),
            row("a2", "assistant", JSONArray().put(text("second")), 3),
        )
        assertEquals(listOf("u", "a2"), out.map { it.id })
        val merged = out[1]
        assertEquals("first\n\nsecond", merged.content)
        assertEquals(listOf("a1", "a2"), merged.sourceDbIds)
        assertEquals(1, merged.toolBlocks.count { it.kind == "tool_use" })
    }

    @Test
    fun `reasoning is restored as a thinking block and an empty assistant row is dropped`() {
        val out = ui(
            row("a", "assistant", JSONArray().put(text("answer")), 0, reasoning = "let me think"),
            row("e", "user", JSONArray().put(text("<system-reminder>only</system-reminder>")), 1),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].toolBlocks.any { it.kind == "thinking" && it.content == "let me think" })
    }

    @Test
    fun `an unparseable row becomes a short placeholder instead of its raw json`() {
        val bad = MessageEntity("b", "s", "assistant", "{ not json", 1L, sortOrder = 0)
        val out = mapper.toChatMessages(listOf(bad), mapOf("b" to runCatching { JSONArray("{ not json") }))
        assertTrue(out.single().content.startsWith("(message could not be parsed"))
        assertFalse(out.single().content.contains("not json"))
    }

    @Test
    fun `a stored error survives and a blank one does not`() {
        val withError = ui(row("a", "assistant", JSONArray().put(text("x")), 0, error = "rate limited")).single()
        assertEquals("rate limited", withError.error)
        val blank = ui(row("a", "assistant", JSONArray().put(text("x")), 0, error = "  ")).single()
        assertEquals(null, blank.error)
    }

    @Test
    fun `the model history keeps the attached-files block as its own part but not in content`() {
        val xml = "<user-attached-files>/var/minis/a.txt</user-attached-files>"
        val entity = row("u", "user", JSONArray().put(text("caption")).put(text(xml)), 0)
        val llm = mapper.toLLMMessage(entity)
        assertEquals("caption", llm.content)
        assertTrue(llm.contentParts.any { it is AgentContentPart.Text && it.text == xml })
        assertNotNull(llm.dbMessageId)
    }
}
