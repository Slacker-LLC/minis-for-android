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
    fun `a text block ends the run and starts a new one`() {
        val entries = grouped(
            listOf(thinking("t1"), tool("a"), text("x"), tool("b"), thinking("t2")),
        )
        // [process(t1,a), text(x), process(b,t2)]
        assertEquals(3, entries.size)
        assertEquals(listOf("t1", "a"), processes(entries)[0].blocks.map { it.id })
        assertEquals(listOf("b", "t2"), processes(entries)[1].blocks.map { it.id })
        val middle = entries[1]
        assertTrue(middle is AssistantTurnEntry.Single)
        assertEquals("x", (middle as AssistantTurnEntry.Single).block.id)
        assertEquals(2, middle.blockIndex)
    }

    @Test
    fun `info and media blocks never join a process`() {
        val entries = grouped(listOf(info("i1"), tool("a"), info("i2")))
        assertEquals(3, entries.size)
        // info is not a step: it ends whatever run precedes it and the lone
        // tool call between the two notices stays a one-step process (grouped
        // mode collapses every thinking/tool run, matching Eta's projector).
        assertTrue(entries[0] is AssistantTurnEntry.Single)
        assertEquals("i1", (entries[0] as AssistantTurnEntry.Single).block.id)
        assertEquals(listOf("a"), processes(entries).single().blocks.map { it.id })
        assertEquals("i2", (entries[2] as AssistantTurnEntry.Single).block.id)
    }

    @Test
    fun `hidden thinking neither creates nor pads a process`() {
        val entries = grouped(
            listOf(thinking("t1"), tool("a")),
            thinkingVisible = false,
        )
        val process = processes(entries).single()
        assertEquals(listOf("a"), process.blocks.map { it.id })

        // A run made of nothing but hidden thinking disappears entirely.
        assertTrue(grouped(listOf(thinking("t1")), thinkingVisible = false).isEmpty())
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
