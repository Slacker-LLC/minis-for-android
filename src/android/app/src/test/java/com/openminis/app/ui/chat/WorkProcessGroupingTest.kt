package com.openminis.app.ui.chat

import com.openminis.app.data.StepsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-work-process] Boundaries of the collapsed work-process row.
 *
 * The grouping runs on every streaming tick, so the rules that matter are the
 * boundaries (what ends a run), the hidden-thinking rule (a block that renders
 * nothing must not create a row) and the failure text that lands on the
 * COLLAPSED header. Every case here is pure — no Android, no Compose.
 */
class WorkProcessGroupingTest {

    private fun thinking(id: String, text: String = "reasoning") =
        AssistantBlock(id = id, kind = THINKING_KIND, content = text)

    private fun tool(
        id: String,
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
        content: String = "ok",
        title: String = "",
        name: String = "shell_execute",
    ) = AssistantBlock(
        id = id,
        kind = TOOL_USE_KIND,
        content = content,
        toolStatus = status,
        toolTitle = title,
        toolName = name,
    )

    private fun text(id: String) = AssistantBlock(id = id, kind = "text", content = "hello")

    private fun info(id: String) = AssistantBlock(id = id, kind = "info", content = "note")

    private fun grouped(
        blocks: List<AssistantBlock>,
        thinkingVisible: Boolean = true,
    ) = buildAssistantTurnEntries(
        messageId = "m1",
        blocks = blocks,
        presentation = StepsPresentation.GROUPED,
        thinkingVisible = thinkingVisible,
    )

    private fun processes(entries: List<AssistantTurnEntry>): List<WorkProcess> =
        entries.filterIsInstance<AssistantTurnEntry.Process>().map { it.process }

    @Test
    fun `consecutive thinking and tool blocks fold into one process`() {
        val entries = grouped(listOf(thinking("t1"), tool("a"), tool("b")))
        assertEquals(1, entries.size)
        val process = processes(entries).single()
        assertEquals(listOf("t1", "a", "b"), process.blocks.map { it.id })
    }

    @Test
    fun `text between tool calls stays inside the one turn row`() {
        val entries = grouped(
            listOf(thinking("t1"), tool("a"), text("x"), tool("b"), thinking("t2")),
        )
        // [T-android-turn-work] One row for the whole turn: a question answered after two tool
        // calls used to produce two rows and two durations. Text the model wrote between calls is
        // narration and belongs inside the row (the panel renders it between the steps); only the
        // trailing text after the last call is the answer, and here there is none.
        assertEquals(1, entries.size)
        assertEquals(listOf("t1", "a", "x", "b", "t2"), processes(entries).single().blocks.map { it.id })
    }

    @Test
    fun `a trailing notice stays outside the row`() {
        val entries = grouped(listOf(tool("a"), info("i1")))
        // info is not a step, so it is not folded in - and it sits after the last step, which makes
        // it the tail of the message rather than part of the row.
        assertEquals(1, processes(entries).single().blocks.map { it.id }.size)
        assertEquals(listOf("a"), processes(entries).single().blocks.map { it.id })
        assertTrue(entries.last() is AssistantTurnEntry.Single)
        assertEquals("i1", (entries.last() as AssistantTurnEntry.Single).block.id)
    }

    @Test
    fun `hidden thinking neither creates nor pads a process`() {
        val entries = grouped(
            listOf(thinking("t1"), tool("a")),
            thinkingVisible = false,
        )
        val process = processes(entries).single()
        assertEquals(listOf("a"), process.blocks.map { it.id })
    }

    @Test
    fun `perTool keeps one entry per block including hidden thinking`() {
        val blocks = listOf(thinking("t1"), tool("a"), text("x"))
        val entries = buildAssistantTurnEntries(
            messageId = "m1",
            blocks = blocks,
            presentation = StepsPresentation.PER_TOOL,
            thinkingVisible = false,
        )
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { (it as AssistantTurnEntry.Single).blockIndex })
        assertEquals(blocks.map { it.id }, entries.map { (it as AssistantTurnEntry.Single).block.id })
    }

    @Test
    fun `process id is anchored on the first block so the row key survives growth`() {
        val short = processes(grouped(listOf(tool("a")))).single()
        val grown = processes(grouped(listOf(tool("a"), tool("b"), thinking("t1")))).single()
        assertEquals(short.id, grown.id)
        assertEquals("m1:a", grown.id)
    }

    @Test
    fun `process rejects an empty block list`() {
        try {
            WorkProcess(id = "p", blocks = emptyList())
            fail("expected WorkProcess to reject an empty run")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one block"))
        }
    }

    @Test
    fun `running tool is detected while any status is in flight`() {
        assertTrue(WorkProcess("p", listOf(tool("a"))).isRunning.not())
        for (status in WORK_PROCESS_RUNNING_STATUSES) {
            val process = WorkProcess("p", listOf(thinking("t"), tool("a", status = status)))
            assertTrue("$status must read as running", process.isRunning)
            assertEquals("a", process.runningTool?.id)
        }
    }

    @Test
    fun `completed summary counts tool steps only and ignores thinking`() {
        val summary = WorkProcess("p", listOf(thinking("t1"), tool("a"), thinking("t2"))).summary()
        assertEquals(1, summary.toolCount)
        assertEquals(2, summary.thinkingCount)
        assertFalse(summary.isRunning)
        assertFalse(summary.hasFailure)
        assertNull(summary.failureReason)
    }

    @Test
    fun `failure reason comes from the last failing tool and is numbered among tools`() {
        val process = WorkProcess(
            "p",
            listOf(
                thinking("t1"),
                tool("a"),
                tool("b", status = ToolBlockStatus.FAILED, content = "boom: permission denied\nmore detail"),
            ),
        )
        val summary = process.summary()
        assertEquals(2, summary.failedStepNumber)
        assertEquals("boom: permission denied", summary.failureReason)
        assertTrue(summary.hasFailure)
    }

    @Test
    fun `a running step outranks an earlier failure on the collapsed row`() {
        val summary = WorkProcess(
            "p",
            listOf(
                tool("a", status = ToolBlockStatus.FAILED, content = "boom"),
                tool("b", status = ToolBlockStatus.RUNNING),
            ),
        ).summary()
        assertTrue(summary.isRunning)
        assertEquals(2, summary.runningStepNumber)
        assertNull(summary.failureReason)
    }

    @Test
    fun `long failure text is truncated and empty content falls back to the title`() {
        val verbose = WorkProcess(
            "p",
            listOf(tool("a", status = ToolBlockStatus.FAILED, content = "x".repeat(400))),
        ).summary(maxFailureChars = 20)
        assertEquals(20, verbose.failureReason!!.removeSuffix("…").length)

        val blank = WorkProcess(
            "p",
            listOf(tool("a", status = ToolBlockStatus.TIMEOUT, content = "   \n", title = "shell_execute")),
        ).summary()
        assertEquals("shell_execute", blank.failureReason)

        val noTitle = WorkProcess(
            "p",
            listOf(tool("a", status = ToolBlockStatus.FAILED, content = "", title = "")),
        ).summary()
        assertEquals("terminal", noTitle.failureReason)
    }
}
