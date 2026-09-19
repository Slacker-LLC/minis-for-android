package com.openminis.app.ui.chat

import com.openminis.app.data.StepsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-turn-work] One turn, one work row, one answer outside it.
 *
 * The transcript merges a turn's assistant rows into a single message (narration, tool calls,
 * narration, ..., final answer). Grouping used to split that message at every text block, so a
 * question that used three tools produced three "用时 Ns" rows. Now everything up to the last tool
 * call folds into one row - narration included, which the panel renders between the steps - and the
 * trailing text is the answer, which stays out of the row the way Codex's AgentMessage does.
 */
class TurnWorkRowTest {

    private fun tool(id: String, startTimeMs: Long = 1_000L) = AssistantBlock(
        id = id,
        kind = TOOL_USE_KIND,
        content = "ok",
        toolStatus = ToolBlockStatus.SUCCESS,
        toolName = "shell_execute",
        startTimeMs = startTimeMs,
        durationMs = 500L,
    )

    private fun text(id: String, body: String) = AssistantBlock(id = id, kind = "text", content = body)

    private fun entries(blocks: List<AssistantBlock>) = buildAssistantTurnEntries(
        messageId = "m1",
        blocks = blocks,
        presentation = StepsPresentation.GROUPED,
        thinkingVisible = true,
    )

    @Test
    fun `narration, tools and an answer fold into one row plus the answer`() {
        val entries = entries(
            listOf(
                text("t1", "先跑一条命令"),
                tool("c1", startTimeMs = 1_000L),
                text("t2", "再确认一下"),
                tool("c2", startTimeMs = 2_000L),
                text("t3", "结论：都跑完了"),
            ),
        )
        val processes = entries.filterIsInstance<AssistantTurnEntry.Process>()
        assertEquals("one duration line for the whole turn", 1, processes.size)
        assertEquals(
            "every step of the turn, narration included, is inside that one row",
            listOf("t1", "c1", "t2", "c2"),
            processes.single().process.blocks.map { it.id },
        )
        assertEquals(
            "the turn's duration spans all of its steps",
            1_500L,
            processes.single().process.durationMs,
        )
        assertEquals(
            "only the trailing text is the answer",
            listOf("t3"),
            entries.filterIsInstance<AssistantTurnEntry.Single>().map { it.block.id },
        )
        assertTrue(
            "and it renders after the row, not inside it",
            entries.last() is AssistantTurnEntry.Single,
        )
    }

    @Test
    fun `a text-only turn has no work row`() {
        val entries = entries(listOf(text("t1", "只有一句话")))
        assertEquals(0, entries.filterIsInstance<AssistantTurnEntry.Process>().size)
        assertEquals(1, entries.filterIsInstance<AssistantTurnEntry.Single>().size)
    }

    @Test
    fun `a turn that ends on a tool call keeps everything in the row`() {
        val entries = entries(listOf(text("t1", "开始"), tool("c1")))
        assertEquals(1, entries.filterIsInstance<AssistantTurnEntry.Process>().size)
        assertEquals(
            "nothing is left outside when the model never answered",
            0,
            entries.filterIsInstance<AssistantTurnEntry.Single>().size,
        )
    }
}
