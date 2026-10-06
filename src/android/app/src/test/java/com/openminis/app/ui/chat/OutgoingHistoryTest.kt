package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The history the model is sent, with and without a compaction summary, tested without a ViewModel. */
class OutgoingHistoryTest {
    private fun user(id: String, text: String) = LLMMessage(LLMMessage.Role.USER, text, dbMessageId = id)
    private fun assistant(id: String, text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text, dbMessageId = id)

    private fun marker(anchor: String?, version: Int = 2) = CompactMarkerEntity(
        id = "marker-1", sessionId = "s", summary = "stored", firstKeptSortOrder = 0, compactedCount = 0,
        createdAt = 0, lastCompactedMessageId = anchor, version = version,
    )

    private fun history(turns: Int) = (1..turns).flatMap {
        listOf(user("u$it", "question $it"), assistant("a$it", "answer $it"))
    }

    private fun joined(list: List<LLMMessage>) = list.joinToString("\n") { it.content }

    @Test
    fun `without a summary or a marker the history is sent untouched`() {
        val h = history(3)
        assertEquals(h, OutgoingHistory.effective(h, summary = null, marker = null))
        assertEquals(h, OutgoingHistory.effective(h, summary = "  ", marker = marker("a2")))
        assertEquals(h, OutgoingHistory.effective(h, summary = "SUM", marker = null))
    }

    @Test
    fun `the summary is spliced into the first user message after the anchor`() {
        val h = history(4)
        val out = OutgoingHistory.effective(h, "SUMMARY TEXT", marker("a2"))
        // The warm-up window keeps the earlier turns verbatim, then the first message after the anchor
        // carries the summary in front of its own text.
        assertTrue(joined(out).startsWith("question 1"))
        val carrier = out.first { it.content.startsWith("<context-summary>") }
        assertTrue(carrier.content.contains("SUMMARY TEXT"))
        assertTrue(carrier.content.endsWith("question 3"))
        assertEquals("the messages after the anchor follow", listOf("answer 3", "question 4", "answer 4"), out.takeLast(3).map { it.content })
        assertEquals("roles still alternate", out.map { it.role }, out.indices.map { if (it % 2 == 0) LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT })
    }

    @Test
    fun `an anchor the history does not contain degrades to the full history, never a lone summary`() {
        val h = history(3)
        assertEquals(h, OutgoingHistory.effective(h, "SUMMARY", marker("no-such-id")))
        assertEquals(h, OutgoingHistory.effective(h, "SUMMARY", marker(null)))
    }

    @Test
    fun `with nothing after the anchor the summary becomes its own final user turn`() {
        val h = history(2)
        val out = OutgoingHistory.effective(h, "SUMMARY", marker("a2"))
        val last = out.last()
        assertEquals(LLMMessage.Role.USER, last.role)
        assertTrue(last.content.startsWith("<context-summary>") && last.content.contains("SUMMARY"))
        assertEquals("nothing else was dropped", h.size + 1, out.size)
    }

    @Test
    fun `a legacy marker keeps its summary head and the messages from its first kept one`() {
        val h = history(3)
        val legacy = marker(anchor = "a1", version = 1).copy(firstKeptMessageId = "u3")
        val out = OutgoingHistory.effective(h, "OLD SUMMARY", legacy)
        assertTrue(out.first().content.contains("OLD SUMMARY"))
        assertEquals(listOf("question 3", "answer 3"), out.drop(1).map { it.content })
    }

    @Test
    fun `an orphaned tool result is dropped and an orphaned call gets an error result`() {
        val orphanResult = LLMMessage(
            LLMMessage.Role.USER, "",
            contentParts = listOf(AgentContentPart.ToolResult("gone", "shell", "output", false)),
        )
        val orphanCall = LLMMessage(
            LLMMessage.Role.ASSISTANT, "",
            contentParts = listOf(AgentContentPart.ToolUse("call-1", "shell", JSONObject())),
        )
        val out = OutgoingHistory.dropOrphanedToolParts(listOf(user("u1", "hi"), orphanResult, orphanCall, user("u2", "and then?")))
        assertFalse("the result whose call is gone is removed", out.any { m -> m.contentParts.any { it is AgentContentPart.ToolResult && it.id == "gone" } })
        val repaired = out.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "call-1" }
        assertTrue("the unmatched call is closed with an error result", repaired.isError)
    }

    @Test
    fun `a call at the very end is in flight, not orphaned, and gets no placeholder`() {
        val inFlight = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("c", "shell", JSONObject())))
        val h = listOf(user("u", "go"), inFlight)
        assertEquals(h, OutgoingHistory.dropOrphanedToolParts(h))
    }

    @Test
    fun `a matched call and result are left alone`() {
        val call = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("c", "shell", JSONObject())))
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("c", "shell", "ok", false)))
        val h = listOf(user("u", "go"), call, result)
        assertEquals(h, OutgoingHistory.dropOrphanedToolParts(h))
    }

    @Test
    fun `the token walk-back keeps at least the newest round and respects the message cap`() {
        val h = history(5)
        val invalid = OutgoingHistory.walkBackRecentTokenBudget(h, anchorIdx = 99, tokenBudget = 1000, maxMessages = 100)
        assertEquals("invalidAnchor", invalid.stopReason)
        assertNull(invalid.priorIdx)

        val tiny = OutgoingHistory.walkBackRecentTokenBudget(h, anchorIdx = 9, tokenBudget = 1, maxMessages = 100)
        assertEquals("even over budget, the newest round is kept", 8, tiny.priorIdx)

        val all = OutgoingHistory.walkBackRecentTokenBudget(h, anchorIdx = 9, tokenBudget = 100_000, maxMessages = 100)
        assertEquals(0, all.priorIdx)
        assertEquals("reachedStart", all.stopReason)

        val capped = OutgoingHistory.walkBackRecentTokenBudget(h, anchorIdx = 9, tokenBudget = 100_000, maxMessages = 4)
        assertEquals("messageCapWouldExceed", capped.stopReason)
        assertEquals(6, capped.priorIdx)
    }
}
