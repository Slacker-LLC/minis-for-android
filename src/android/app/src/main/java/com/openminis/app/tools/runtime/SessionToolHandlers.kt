package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.agent.executeBrowserUseTool
import com.openminis.app.agent.executeMemoryGetTool
import com.openminis.app.agent.executeMemoryWriteTool
import com.openminis.app.tools.*

/** Reuse a live session's state without constructing a UI projection or another executor. */
class BrowserUseHandler : ToolHandler {
    override val definition get() = AgentTools.browserUseDefinition().copy(timeoutMs = 90_000L)
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        AgentSessionStore.existing(sessionId)?.let { return it.executeBrowserUseTool(argsJson) }
        val app = context.applicationContext as? MinisApp ?: return ToolExecutionResult("Error: app unavailable", false)
        return BrowserTools.execute(argsJson, sessionId, app.sharedBrowserTabPool)
    }
}

class MemoryToolHandler(private val write: Boolean) : ToolHandler {
    override val definition get() = if (write) AgentTools.memoryWriteDefinition() else AgentTools.memoryGetDefinition()
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        AgentSessionStore.existing(sessionId)?.let {
            return if (write) it.executeMemoryWriteTool(argsJson) else it.executeMemoryGetTool(argsJson)
        }
        val app = context.applicationContext as? MinisApp ?: return ToolExecutionResult("Error: Memory not available", false)
        if (write && app.chatRepository.getSession(sessionId)?.memoryEnabled != 1) {
            return ToolExecutionResult("Memory writes are disabled for this session. Reads remain available.", false)
        }
        val result = if (write) MemoryTools.executeMemoryWrite(argsJson, app.memoryRepository, sessionId)
            else MemoryTools.executeMemoryGet(argsJson, app.memoryRepository)
        return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
    }
}
