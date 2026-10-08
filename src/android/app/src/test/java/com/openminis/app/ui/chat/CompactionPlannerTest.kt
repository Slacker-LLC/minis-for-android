package com.openminis.app.ui.chat

import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The range and anchor decisions of a compaction, tested without a ViewModel. */
class CompactionPlannerTest {
    private fun user(id: String?, text: String) = LLMMessage(LLMMessage.Role.USER, text, dbMessageId = id)
    private fun assistant(id: String?, text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text, dbMessageId = id)

    private fun marker(anchor: String?) = CompactMarkerEntity(
        id = "m", sessionId = "s", summary = "old", firstKeptSortOrder = 0, compactedCount = 0,
        createdAt = 0, lastCompactedMessageId = anchor, version = 2,
    )

    private fun history(turns: Int) = (1..turns).flatMap {
        listOf(user("u$it", "question $it " + "x".repeat(400)), assistant("a$it", "answer $it " + "y".repeat(400)))
    }

    private fun plan(h: List<LLMMessage>, prev: CompactMarkerEntity? = null, override: Int? = null) =
        CompactionPlanner.plan(h, override, prev, existingSummary = null, contextWindow = 100_000)

    @Test
    fun `nothing persisted yet is refused`() {
        val h = listOf(user(null, "hi"), assistant(null, "hello"))
        val out = plan(h)
        assertTrue(out is CompactionPlanner.Outcome.Reject)
        assertEquals("Cannot compact: no persisted messages yet.", (out as CompactionPlanner.Outcome.Reject).message)
    }

    @Test
    fun `a marker already at the anchor leaves nothing to compact`() {
        val h = history(3)
        val out = plan(h, prev = marker("a3"))
        assertEquals(
            "Already compacted up to this point.",
            (out as CompactionPlanner.Outcome.Reject).message,
        )
    }

    @Test
    fun `a long history yields a range that ends before the tail and covers it from the start`() {
        val h = history(60)
        val out = plan(h) as CompactionPlanner.Outcome.Ready
        assertEquals("u1", out.toCompact.first().dbMessageId)
        assertTrue(out.compactEndIdx < h.lastIndex)
        assertEquals(out.compactEndIdx + 1, out.toCompact.size)
        assertTrue(out.chunks.isNotEmpty())
        assertEquals(out.toCompact.size, out.chunks.sumOf { it.size })
    }

    @Test
    fun `a previous marker moves the range start past its anchor`() {
        val h = history(60)
        val out = plan(h, prev = marker("a5")) as CompactionPlanner.Outcome.Ready
        assertEquals("u6", out.toCompact.first().dbMessageId)
    }

    @Test
    fun `an unknown marker anchor restarts from the top`() {
        val out = plan(history(60), prev = marker("gone")) as CompactionPlanner.Outcome.Ready
        assertEquals("u1", out.toCompact.first().dbMessageId)
    }

    @Test
    fun `the anchor walk back skips ids the database does not have`() {
        val h = history(5)
        assertEquals(7, CompactionPlanner.verifiedAnchorIndex(h, 7, setOf("a4", "u4")))
        assertEquals(6, CompactionPlanner.verifiedAnchorIndex(h, 7, setOf("u4")))
        assertEquals(-1, CompactionPlanner.verifiedAnchorIndex(h, 7, setOf("elsewhere")))
        // A failed database read (empty set) trusts the in-memory index.
        assertEquals(7, CompactionPlanner.verifiedAnchorIndex(h, 7, emptySet()))
    }

    private fun row(id: String, role: String = "user", compacted: Boolean = false) =
        ChatMessage(id = id, role = role, content = id, isCompactedHistory = compacted)

    @Test
    fun `rows up to the cutoff are grayed, later rows and notices are not`() {
        val msgs = listOf(row("u1"), row("a1", "assistant"), row("u2"), row("a2", "assistant"))
        val out = CompactionPlanner.markCompacted(msgs, cutoffId = "a1")
        assertEquals(listOf(true, true, false, false), out.messages.map { it.isCompactedHistory })
        assertEquals(2, out.compactedUiCount)
    }

    @Test
    fun `an old divider is dropped and not counted`() {
        val divider = ChatMessage(
            id = "d", role = "system", content = "",
            toolBlocks = listOf(AssistantBlock(id = "t", kind = "info", toolName = "compact")),
        )
        val out = CompactionPlanner.markCompacted(listOf(row("u1"), divider, row("a1", "assistant")), "a1")
        assertFalse(out.messages.any { it.role == "system" })
        assertEquals(2, out.compactedUiCount)
    }

    @Test
    fun `a cutoff that is not in the list grays nothing`() {
        val out = CompactionPlanner.markCompacted(listOf(row("u1"), row("a1", "assistant")), "missing")
        assertEquals(0, out.compactedUiCount)
        assertTrue(out.messages.none { it.isCompactedHistory })
    }

    @Test
    fun `a cutoff stored inside a merged bubble grays up to that bubble only`() {
        val merged = row("a1", "assistant").copy(sourceDbIds = listOf("db-a1", "db-a1-tool", "db-a1-final"))
        val out = CompactionPlanner.markCompacted(listOf(row("u1"), merged, row("u2"), row("a2", "assistant")), "db-a1-tool")
        assertEquals(listOf(true, true, false, false), out.messages.map { it.isCompactedHistory })
        assertEquals(2, out.compactedUiCount)
    }
}
