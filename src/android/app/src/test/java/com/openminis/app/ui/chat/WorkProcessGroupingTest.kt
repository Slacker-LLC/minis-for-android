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
    fun `text between tool calls ends a run and is shown as itself`() {
        val entries = grouped(
            listOf(thinking("t1"), tool("a"), text("x"), tool("b"), thinking("t2")),
        )
        assertEquals(3, entries.size)
        assertEquals(listOf("t1", "a"), (entries[0] as AssistantTurnEntry.Process).process.blocks.map { it.id })
        assertEquals("x", (entries[1] as AssistantTurnEntry.Single).block.id)
        assertEquals(listOf("b", "t2"), (entries[2] as AssistantTurnEntry.Process).process.blocks.map { it.id })
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
    }

    @Test
    fun `a failed step adds nothing to the header, which names the step in flight`() {
        val summary = WorkProcess(
            "p",
            listOf(
                tool("a", status = ToolBlockStatus.FAILED, content = "boom"),
                tool("b", status = ToolBlockStatus.RUNNING),
            ),
        ).summary()
        assertTrue(summary.isRunning)
        assertEquals(2, summary.runningStepNumber)
        assertEquals(2, summary.toolCount)
    }

    @Test
    fun `a live turn is running for its whole length, not only while a tool call is in flight`() {
        val blocks = listOf(tool("a"), tool("b"))
        // Both calls are done but the model is still writing: the row must not fold and re-open.
        val live = WorkProcess("p", blocks, turnLive = true)
        assertTrue(live.isRunning)
        assertTrue(live.summary().isRunning)
        assertNull(live.summary().runningStepNumber)
        assertFalse(WorkProcess("p", blocks, turnLive = false).isRunning)
    }

    @Test
    fun `the turn-live flag reaches the process the builder makes`() {
        val entries = buildAssistantTurnEntries(
            messageId = "m1",
            blocks = listOf(tool("a")),
            presentation = StepsPresentation.GROUPED,
            thinkingVisible = true,
            turnLive = true,
        )
        assertTrue(processes(entries).single().turnLive)
    }

    @Test
    fun `a collapsed note shows one plain line, the latest while it streams`() {
        val md = "## Plan\n\n- **first** step with `code`\n- second step"
        assertEquals("Plan", notePreview(md, latest = false))
        assertEquals("second step", notePreview(md, latest = true))
        assertEquals("first step with code", notePreview("- **first** step with `code`", latest = false))
        assertEquals("", notePreview("  \n\n", latest = false))
        assertTrue(notePreview("x".repeat(500), latest = false).length <= 201)
    }
}
