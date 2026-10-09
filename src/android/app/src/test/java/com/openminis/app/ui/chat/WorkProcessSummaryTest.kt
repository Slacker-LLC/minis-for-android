package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-work-items] The typed layer behind the collapsed work row: what kind a step is, how
 * long the finished run took, and the one-line summary of what it consisted of.
 *
 * Codex's turn model keeps a typed item per step and builds the finished view from those types;
 * these are the same three questions at the granularity this app has. All pure - the widgets only
 * render what is asserted here.
 */
class WorkProcessSummaryTest {

    private fun tool(
        id: String,
        name: String,
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
        startTimeMs: Long = 0L,
        durationMs: Long = 0L,
    ) = AssistantBlock(
        id = id,
        kind = TOOL_USE_KIND,
        content = "ok",
        toolStatus = status,
        toolName = name,
        startTimeMs = startTimeMs,
        durationMs = durationMs,
    )

    private fun thinking(id: String) = AssistantBlock(id = id, kind = THINKING_KIND, content = "why")

    @Test
    fun `a step is classified by what its tool is, not by its title`() {
        assertEquals(WorkItemKind.REASONING, workItemKindOf(thinking("t1")))
        assertEquals(WorkItemKind.COMMAND, workItemKindOf(tool("a", "shell_execute")))
        assertEquals(WorkItemKind.FILE_READ, workItemKindOf(tool("b", "file_read")))
        assertEquals(WorkItemKind.FILE_EDIT, workItemKindOf(tool("c", "file_edit")))
        assertEquals(WorkItemKind.FILE_EDIT, workItemKindOf(tool("d", "file_write")))
        assertEquals(WorkItemKind.BROWSER, workItemKindOf(tool("e", "browser_use")))
        assertEquals(WorkItemKind.IMAGE, workItemKindOf(tool("f", "read_image")))
        assertEquals(WorkItemKind.DELEGATION, workItemKindOf(tool("g", "subagent")))
        assertEquals(WorkItemKind.SEARCH, workItemKindOf(tool("h", "web_search")))
        assertEquals(
            "a canonical MCP name carries a dot and no tool-specific prefix",
            WorkItemKind.MCP,
            workItemKindOf(tool("i", "mcp.docs.search")),
        )
        assertEquals(WorkItemKind.OTHER, workItemKindOf(tool("j", "something_new")))
    }

    @Test
    fun `step timings never become a time, only the turn clock does`() {
        val steps = listOf(
            tool("a", "shell_execute", startTimeMs = 1_000L, durationMs = 2_000L),
            tool("b", "file_read", startTimeMs = 4_000L, durationMs = 1_000L),
        )
        assertNull("a row without the turn's clock shows no time", WorkProcess(id = "p1", blocks = steps).durationMs)
        assertEquals(
            "the turn's clock: from the user's send to the end of the reply",
            9_000L,
            WorkProcess(id = "p1", blocks = steps, messageCreatedAtMs = 500L, messageUpdatedAtMs = 9_500L).durationMs,
        )
    }

    @Test
    fun `the clock has no final time while the turn runs or when the row has no timestamps`() {
        val steps = listOf(tool("a", "shell_execute"))
        assertNull(
            WorkProcess(id = "p1", blocks = steps, messageCreatedAtMs = 500L, messageUpdatedAtMs = 9_500L, clockLive = true).durationMs,
        )
        assertNull(WorkProcess(id = "p2", blocks = steps, messageCreatedAtMs = 500L).durationMs)
    }

    @Test
    fun `the running line names the step in flight and a thinking run says so`() {
        val running = WorkProcess(
            id = "p1",
            blocks = listOf(
                tool("a", "shell_execute"),
                tool("b", "file_read", status = ToolBlockStatus.RUNNING).copy(toolTitle = "Read ChatScreen.kt"),
            ),
        ).summary()
        assertEquals(2, running.runningStepNumber)
        assertEquals("Read ChatScreen.kt", running.runningToolName)

        val thinkingRun = WorkProcess(id = "p2", blocks = listOf(tool("a", "shell_execute"), thinking("t")), turnLive = true).summary()
        assertEquals(true, thinkingRun.thinking)
        assertEquals(null, thinkingRun.runningToolName)
    }

    @Test
    fun `tallies count every step by kind, in enum order`() {
        val process = WorkProcess(
            id = "p1",
            blocks = listOf(
                thinking("t1"),
                tool("a", "shell_execute"),
                tool("b", "shell_execute"),
                tool("c", "file_edit"),
            ),
        )
        val tallies = process.summary().tallies
        assertEquals(2, tallies[WorkItemKind.COMMAND])
        assertEquals(1, tallies[WorkItemKind.FILE_EDIT])
        assertEquals(1, tallies[WorkItemKind.REASONING])
    }

    @Test
    fun `the summary line drops reasoning and unnamed steps, biggest group first`() {
        val tallies = mapOf(
            WorkItemKind.REASONING to 4,
            WorkItemKind.FILE_EDIT to 1,
            WorkItemKind.COMMAND to 3,
            WorkItemKind.OTHER to 2,
        )
        val groups = tallyEntries(tallies)
        assertEquals(
            listOf(WorkItemKind.COMMAND to 3, WorkItemKind.FILE_EDIT to 1),
            groups,
        )
        assertEquals(
            "3 commands · 1 change",
            groups.joinToString(" · ") { (kind, count) ->
                when (kind) {
                    WorkItemKind.COMMAND -> "$count commands"
                    WorkItemKind.FILE_EDIT -> "$count change"
                    else -> "$count other"
                }
            },
        )
    }

    @Test
    fun `a run of nothing but reasoning has no summary line`() {
        assertEquals(emptyList<Pair<WorkItemKind, Int>>(), tallyEntries(mapOf(WorkItemKind.REASONING to 3)))
        assertEquals(emptyList<Pair<WorkItemKind, Int>>(), tallyEntries(emptyMap()))
    }
}
