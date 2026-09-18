package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.BotDelegationCoordinator
import com.openminis.app.tools.ToolExecutionResult

class BotDelegationHandler : ToolHandler {
    override val definition: AgentToolDefinition = BotDelegationCoordinator.definition()

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val coordinator = BotDelegationCoordinator.current()
            ?: return ToolExecutionResult("Error: bot_delegation_unavailable", false)
        return coordinator.enqueueFromTool(argsJson, sessionId, toolId)
    }
}


class BotRosterHandler : ToolHandler {
    override val definition: AgentToolDefinition = BotDelegationCoordinator.listBotsDefinition()

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult = BotDelegationCoordinator.current()?.listBotsForSource(sessionId)
        ?: ToolExecutionResult("Error: bot_delegation_unavailable", false)
}

class BotDelegationStatusHandler : ToolHandler {
    override val definition: AgentToolDefinition = BotDelegationCoordinator.checkDelegationDefinition()

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult = BotDelegationCoordinator.current()?.checkDelegationFromTool(argsJson, sessionId)
        ?: ToolExecutionResult("Error: bot_delegation_unavailable", false)
}
