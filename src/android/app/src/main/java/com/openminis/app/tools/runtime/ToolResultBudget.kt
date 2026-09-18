package com.openminis.app.tools.runtime

import com.openminis.app.data.ContextOffload
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.internal.ToolResultPruner

/**
 * [T-tool-result-budget-android] The one place a tool result is bounded before it becomes
 * a message part.
 *
 * Every tool used to be trusted to bound its own output: the browser has its payload
 * limiter, the MCP handler spills, the shell writes through its own retainer — but a tool
 * that simply printed a lot (a database dump, a log read, a guest command) went into the
 * transcript and into the next provider request as-is. Eta has the same bound in one
 * place instead: its runtime wire caps a result at 64 000 characters. Ours is the
 * registry dispatch, which every registry tool passes through.
 *
 * Oversized text spills through [ContextOffload] — the helper the log tools and the MCP
 * handler already use — so the model gets a preview plus a path it can re-read with
 * `file_read`; if the spill cannot be written, the result is pruned with an explicit
 * omission marker rather than handed over whole.
 */
internal object ToolResultBudget {
    /** Text above this many UTF-8 bytes is spilled instead of inlined. */
    const val MAX_INLINE_BYTES = 50 * 1024

    suspend fun bounded(
        sessionId: String,
        toolName: String,
        result: ToolExecutionResult,
        spill: suspend (sessionId: String, text: String, baseName: String) -> ContextOffload.SpillResult? =
            ::spillToSession,
    ): ToolExecutionResult {
        if (result.output.toByteArray(Charsets.UTF_8).size <= MAX_INLINE_BYTES) return result
        val spilled = spill(sessionId, result.output, toolName)
        val inline = spilled?.takeIf { it.spilled }?.inline
            ?: ToolResultPruner.prune(result.output)
            ?: result.output
        return result.copy(output = inline)
    }

    private suspend fun spillToSession(
        sessionId: String,
        text: String,
        baseName: String,
    ): ContextOffload.SpillResult? = runCatching {
        ContextOffload.spillIfOversized(sessionId = sessionId, text = text, baseName = baseName)
    }.getOrNull()
}
