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
    fun `what the model wrote between steps stays visible, in order, between the work rows`() {
        val entries = entries(
            listOf(
                text("t1", "先跑一条命令"),
                tool("c1", startTimeMs = 1_000L),
                text("t2", "再确认一下"),
                tool("c2", startTimeMs = 2_000L),
                text("t3", "结论：都跑完了"),
            ),
        )
        assertEquals(
            "narration is shown where it happened, not folded into the work",
            listOf("single:t1", "process:c1", "single:t2", "process:c2", "single:t3"),
            entries.map {
                when (it) {
                    is AssistantTurnEntry.Single -> "single:" + it.block.id
                    is AssistantTurnEntry.Process -> "process:" + it.process.blocks.joinToString(",") { b -> b.id }
                }
            },
        )
    }

    @Test
    fun `only the last work row follows the live turn`() {
        val entries = buildAssistantTurnEntries(
            messageId = "m1",
            blocks = listOf(tool("c1", startTimeMs = 1_000L), text("t2", "再确认一下"), tool("c2", startTimeMs = 2_000L), text("t3", "结论")),
            presentation = StepsPresentation.GROUPED,
            thinkingVisible = true,
            turnLive = true,
        )
        val processes = entries.filterIsInstance<AssistantTurnEntry.Process>().map { it.process }
        assertEquals("an earlier run is finished: the model went on to write after it", listOf(false, true), processes.map { it.turnLive })
    }

    @Test
    fun `a text-only turn has no work row`() {
        val entries = entries(listOf(text("t1", "只有一句话")))
        assertEquals(0, entries.filterIsInstance<AssistantTurnEntry.Process>().size)
        assertEquals(1, entries.filterIsInstance<AssistantTurnEntry.Single>().size)
    }

    @Test
    fun `a turn that ends on a tool call has its opening sentence outside the row`() {
        val entries = entries(listOf(text("t1", "开始"), tool("c1")))
        assertEquals(1, entries.filterIsInstance<AssistantTurnEntry.Process>().size)
        assertEquals(
            "the opening sentence is visible text before the work row",
            listOf("t1"),
            entries.filterIsInstance<AssistantTurnEntry.Single>().map { it.block.id },
        )
    }
}
