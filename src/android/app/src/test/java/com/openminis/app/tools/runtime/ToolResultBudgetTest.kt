package com.openminis.app.tools.runtime

import com.openminis.app.data.ContextOffload
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.internal.ToolResultPruner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-result-budget-android] The single bound every registry tool's result passes
 * through before it becomes a message part: text above the inline budget spills to the
 * session's offloads directory (preview + path to re-read), and when the spill cannot be
 * written the fallback is a pruned preview, never the raw text.
 */
class ToolResultBudgetTest {

    private fun result(text: String) = ToolExecutionResult(
        output = text,
        success = true,
        toolTitle = "some tool",
    )

    private fun spilled(inline: String) = ContextOffload.SpillResult(
        inline = inline,
        linuxPath = "/var/minis/offloads/tools/tool-1.txt",
        spilled = true,
    )

    @Test
    fun `a result under the budget is returned as the same object`() = runBlocking {
        val small = result("ok")

        val bounded = ToolResultBudget.bounded("s1", "tool", small) { _, _, _ -> null }

        assertSame(small, bounded)
    }

    @Test
    fun `a result at the budget is not spilled`() = runBlocking {
        val atBudget = result("a".repeat(ToolResultBudget.MAX_INLINE_BYTES))
        var spilled = false

        val bounded = ToolResultBudget.bounded("s1", "tool", atBudget) { _, _, _ ->
            spilled = true
            null
        }

        assertSame(atBudget, bounded)
        assertTrue(!spilled)
    }

    @Test
    fun `an oversized result is replaced by the spill's preview`() = runBlocking {
        val big = result("b".repeat(ToolResultBudget.MAX_INLINE_BYTES + 1))

        val bounded = ToolResultBudget.bounded("s1", "tool", big) { _, text, baseName ->
            assertEquals("tool", baseName)
            assertEquals(big.output, text)
            spilled("preview of the dump")
        }

        assertEquals("preview of the dump", bounded.output)
        assertTrue(bounded.success)
        assertEquals("some tool", bounded.toolTitle)
    }

    @Test
    fun `a failed spill falls back to a pruned preview, not the raw text`() = runBlocking {
        val raw = "c".repeat(ToolResultBudget.MAX_INLINE_BYTES + 1_000)

        val bounded = ToolResultBudget.bounded("s1", "tool", result(raw)) { _, _, _ -> null }

        assertTrue(bounded.output.length <= ToolResultPruner.THRESHOLD_CHARS)
        assertTrue(bounded.output.startsWith("c".repeat(ToolResultPruner.HEAD_CHARS)))
        assertTrue(bounded.output.endsWith("c".repeat(ToolResultPruner.TAIL_CHARS)))
    }

    @Test
    fun `a spill that reports nothing spilled falls back to the pruned preview`() = runBlocking {
        val raw = "d".repeat(ToolResultBudget.MAX_INLINE_BYTES + 1_000)

        val bounded = ToolResultBudget.bounded("s1", "tool", result(raw)) { _, _, _ ->
            ContextOffload.SpillResult(inline = "ignored", linuxPath = null, spilled = false)
        }

        assertTrue(bounded.output.length < raw.length)
        assertTrue(bounded.output != "ignored")
    }
}
