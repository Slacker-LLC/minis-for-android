package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult

/**
 * Retired compatibility placeholder for the old `root.shell` tool name.
 *
 * Generic Root execution is intentionally not exposed to local Agents or MCP.
 * Fixed-purpose Android operations that require privilege must use their own
 * narrowly scoped handlers and the internal privileged runner instead.
 */
class RootShellHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = "root.shell",
        description = "Retired compatibility placeholder. Generic Root command execution is not available to Agents or MCP.",
        parameters = emptyMap(),
        required = emptyList(),
        timeoutMs = 1_000L,
    )

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult = ToolExecutionResult(
        output = "Error: permission_denied: root.shell is retired; use a fixed-purpose Android tool",
        success = false,
    )
}
