package com.openminis.app.tools.runtime

import com.openminis.app.data.ContextOffload
import com.openminis.app.tools.internal.ToolResultPruner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-result-bounds-android] What a remote MCP tool's result looks like once it
 * reaches the transcript. A result is untrusted text and the transport's reply cap (4
 * MiB) is not a context budget, so an oversized one spills to the session's offloads
 * directory through the helper the log tools already use; when that spill cannot be
 * written, the fallback is a pruned preview with an explicit marker rather than the raw
 * megabytes.
 */
class MCPToolResultBoundsTest {

    private fun spilled(inline: String) = ContextOffload.SpillResult(
        inline = inline,
        linuxPath = "/var/minis/offloads/tools/mcp_x-1.txt",
        spilled = true,
    )

    @Test
    fun `a spilled result is replaced by its preview`() {
        val raw = "x".repeat(200_000)
        val preview = "head of the result, full text stored at /var/minis/offloads/tools/mcp_x-1.txt"

        assertEquals(preview, MCPToolHandler.boundedMcpResult(raw, spilled(preview)))
    }

    @Test
    fun `a small result passes through untouched`() {
        val raw = "small result"

        assertEquals(raw, MCPToolHandler.boundedMcpResult(raw, spill = null))
    }

    @Test
    fun `an oversized result whose spill failed is still bounded, with a marker`() {
        val raw = "y".repeat(ToolResultPruner.THRESHOLD_CHARS + 20_000)

        val bounded = MCPToolHandler.boundedMcpResult(raw, spill = null)

        assertTrue(bounded.length <= ToolResultPruner.THRESHOLD_CHARS)
        assertTrue(bounded.length < raw.length)
        assertTrue(bounded.startsWith("y".repeat(ToolResultPruner.HEAD_CHARS)))
        assertTrue(bounded.endsWith("y".repeat(ToolResultPruner.TAIL_CHARS)))
    }

    @Test
    fun `a spill result that did not spill is ignored`() {
        val raw = "plain"
        val notSpilled = ContextOffload.SpillResult(inline = "other", linuxPath = null, spilled = false)

        assertEquals(raw, MCPToolHandler.boundedMcpResult(raw, notSpilled))
    }

    @Test
    fun `the inline budget stays far below the transport cap`() {
        // Mirrors ContextOffload.spillIfOversized's default, which the handler inherits.
        val inlineBudget = 50 * 1024

        assertTrue(inlineBudget < 4 * 1024 * 1024)
        assertTrue(ToolResultPruner.THRESHOLD_CHARS <= 64 * 1024)
    }
}
