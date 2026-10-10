package com.openminis.app.tools.runtime

import com.openminis.app.ui.terminal.emulator.BlockedKind
import com.openminis.app.ui.terminal.emulator.ProgramState
import com.openminis.app.ui.terminal.emulator.StatusRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTerminalHandlerTest {
    private fun rec(state: ProgramState, kind: BlockedKind? = null, app: String? = "pi", msg: String? = null) =
        StatusRecord("", state, kind, null, app, null, msg)

    @Test
    fun `the status line names the most urgent record and carries what the program said`() {
        assertNull(SystemTerminalHandler.headline(emptyList()))
        assertEquals(
            "blocked (permission) · pi · Allow bash?",
            SystemTerminalHandler.headline(
                listOf(rec(ProgramState.WORKING), rec(ProgramState.BLOCKED, BlockedKind.PERMISSION, msg = "Allow bash?")),
            ),
        )
        assertEquals("done · pi", SystemTerminalHandler.headline(listOf(rec(ProgramState.IDLE), rec(ProgramState.DONE))))
    }

    @Test
    fun `text from the program cannot smuggle direction overrides or a long message into the status line`() {
        val line = SystemTerminalHandler.headline(listOf(rec(ProgramState.ERROR, msg = "x‮y".repeat(300))))!!
        assertTrue(!line.contains('‮'))
        assertTrue(line.length < 260)
    }

    @Test
    fun `the tool is local-only for scheduled read-only runs and confirm-gated for remote callers`() {
        val policy = ToolPermissionManager.policyFor("terminal")!!
        assertEquals(ToolPermissionManager.Level.MCP_ALLOWED, policy.local)
        assertEquals(ToolPermissionManager.Level.MCP_CONFIRM, policy.mcp)
        assertTrue(com.openminis.app.scheduled.ScheduledReadOnlyPolicy.codeExecutionDenial("terminal") != null)
    }
}
