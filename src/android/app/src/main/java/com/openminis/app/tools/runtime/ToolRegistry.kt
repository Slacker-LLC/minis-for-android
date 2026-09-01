package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

object ToolRegistry {
    private val handlers = linkedMapOf<String, ToolHandler>()
    private val aliases = linkedMapOf<String, String>()

    fun register(handler: ToolHandler, aliasNames: List<String> = emptyList()) {
        handlers[handler.definition.name] = handler
        val apiName = handler.definition.apiName
        if (apiName != handler.definition.name) aliases[apiName] = handler.definition.name
        for (a in aliasNames) aliases[a] = handler.definition.name
    }

    fun unregister(name: String) {
        val canonical = canonicalName(name) ?: return
        handlers.remove(canonical)
        aliases.filterValues { it == canonical }.keys.toList().forEach { aliases.remove(it) }
    }

    fun canonicalName(name: String): String? = if (handlers.containsKey(name)) name else aliases[name]
    fun definition(name: String): AgentToolDefinition? = canonicalName(name)?.let { handlers[it]?.definition }
    fun definitions(): List<AgentToolDefinition> = handlers.values.map { it.definition }
    fun handler(name: String): ToolHandler? = canonicalName(name)?.let { handlers[it] }
    fun contains(name: String): Boolean = canonicalName(name) != null

    fun definitionsForCaller(caller: String): List<AgentToolDefinition> {
        if (caller == ToolPermissionManager.CALLER_LOCAL) return definitions()
        val mcpVisible = ToolPermissionManager.mcpVisibleTools()
        return handlers.values.map { it.definition }.filter { it.name in mcpVisible }
    }
}

object ToolExecutor {
    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String,
        context: Context,
        caller: String = ToolPermissionManager.CALLER_LOCAL,
        toolId: String = "",
        confirmBypassed: Boolean = false,
    ): ToolExecutionResult {
        val canonical = ToolRegistry.canonicalName(name)
            ?: return ToolExecutionResult("Error: unknown_tool: $name", false)
        if (!ToolPermissionManager.isAllowedFor(canonical, caller)) {
            return ToolExecutionResult("Error: permission_denied: $canonical", false)
        }
        if (ToolPermissionManager.needsConfirm(canonical, caller) && !confirmBypassed) {
            return ToolExecutionResult("Error: confirm_required: $canonical", false)
        }
        val handler = ToolRegistry.handler(canonical)
            ?: return ToolExecutionResult("Error: no handler for $canonical", false)
        val provider = ProviderRouter.route(canonical)
            ?: return handler.execute(argsJson, sessionId, context, toolId)
        return provider.execute(canonical, argsJson, sessionId, context, toolId) {
            handler.execute(argsJson, sessionId, context, toolId)
        }
    }
}

interface ToolHandler {
    val definition: AgentToolDefinition
    suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String = ""): ToolExecutionResult
}

class LinuxFileReadHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileReadTool.definition().copy(name = "linux.file.read")
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileReadTool.execute(argsJson, sessionId, context)
}

class LinuxFileWriteHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileWriteTool.definition().copy(name = "linux.file.write")
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileWriteTool.execute(argsJson, sessionId, context)
}

class LinuxFileEditHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileEditTool.definition().copy(name = "linux.file.edit")
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileEditTool.execute(argsJson, sessionId, context)
}

class LinuxShellHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.AgentTools.shellExecuteDefinition(name = "linux.shell")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val command = args.optString("command")
        if (command.isBlank()) return ToolExecutionResult("Error: 'command' is required", false)
        val requestedMs = if (args.has("timeout")) args.optLong("timeout") * 1_000L else null
        val timeoutMs = ToolTimeoutPolicy.resolve("linux.shell", callerOverrideMs = requestedMs).timeoutMs ?: 900_000L
        val result = com.openminis.app.runtime.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = command,
            timeout = timeoutMs,
        )
        val failureKind = result.failureKind.toToolFailureKind()
        return ToolExecutionResult(
            output = result.output,
            success = result.exitCode == 0 && failureKind == null,
            toolTitle = args.optString("tool_title", "linux.shell"),
            timedOut = failureKind == ToolFailureKind.TOOL_TIMEOUT,
            failureKind = failureKind,
        )
    }
}

class LinuxPythonRunHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "linux.python.run",
        description = "Run Python 3 code in the on-device Ubuntu 24.04 environment (uid 10000). " +
            "Pass code as a string; it is written to /workspace and executed with python3. " +
            "Workspace is /workspace; use linux.file.write for files first if the script is long.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "code" to com.openminis.app.data.model.AgentToolParam("string", "Python 3 code to execute."),
            "timeout" to com.openminis.app.data.model.AgentToolParam("integer", "Timeout in seconds (default 300, max 900)."),
        ),
        required = listOf("tool_title", "code"),
        propertyOrdering = listOf("tool_title", "code", "timeout"),
        timeoutMs = null,
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val code = args.optString("code")
        if (code.isBlank()) return ToolExecutionResult("Error: 'code' is required", false)
        val requestedMs = if (args.has("timeout")) args.optLong("timeout") * 1_000L else null
        val timeoutMs = ToolTimeoutPolicy.resolve("linux.python.run", callerOverrideMs = requestedMs).timeoutMs ?: 300_000L
        val scriptPath = "/workspace/python_run_${System.currentTimeMillis()}_${toolId.ifBlank { "tool" }}.py"
        var primary: ToolExecutionResult? = null
        var cancelled: CancellationException? = null
        var cleanupFailure: String? = null
        try {
            val write = com.openminis.app.tools.FileWriteTool.execute(
                """{"tool_title":"write python script","path":${JSONObject.quote(scriptPath)},"content":${JSONObject.quote(code)}}""",
                sessionId,
                context,
            )
            if (!write.success) {
                primary = write
            } else {
                val result = com.openminis.app.runtime.ExecutionCoordinator.execute(
                    sessionId = sessionId,
                    command = "python3 ${shellQuote(scriptPath)}",
                    timeout = timeoutMs,
                )
                val failureKind = result.failureKind.toToolFailureKind()
                primary = ToolExecutionResult(
                    output = result.output,
                    success = result.exitCode == 0 && failureKind == null,
                    toolTitle = args.optString("tool_title", "linux.python.run"),
                    timedOut = failureKind == ToolFailureKind.TOOL_TIMEOUT,
                    failureKind = failureKind,
                )
            }
        } catch (c: CancellationException) {
            cancelled = c
        } finally {
            cleanupFailure = when {
                runCatching {
                    com.openminis.app.runtime.minisd.WorkspaceFileClient.delete(sessionId, scriptPath)
                }.isSuccess -> null
                else -> "CLEANUP_FAILURE: unable to delete temporary script $scriptPath"
            }
        }
        cancelled?.let { c ->
            cleanupFailure?.let { c.addSuppressed(IllegalStateException(it)) }
            throw c
        }
        val result = primary ?: ToolExecutionResult("Error: python execution produced no result", false)
        if (cleanupFailure == null) return result
        android.util.Log.e("LinuxPythonRunHandler", cleanupFailure)
        val kind = result.failureKind ?: ToolFailureKind.CLEANUP_FAILURE
        return result.copy(
            output = result.output + "\n" + cleanupFailure,
            success = false,
            failureKind = kind,
            cleanupFailure = cleanupFailure,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

private fun com.openminis.app.runtime.ExecutionCoordinator.FailureKind?.toToolFailureKind(): ToolFailureKind? = when (this) {
    com.openminis.app.runtime.ExecutionCoordinator.FailureKind.TOOL_TIMEOUT -> ToolFailureKind.TOOL_TIMEOUT
    com.openminis.app.runtime.ExecutionCoordinator.FailureKind.TRANSPORT_TIMEOUT -> ToolFailureKind.TRANSPORT_TIMEOUT
    com.openminis.app.runtime.ExecutionCoordinator.FailureKind.PROCESS_KILLED -> ToolFailureKind.PROCESS_KILLED
    com.openminis.app.runtime.ExecutionCoordinator.FailureKind.CLEANUP_FAILURE -> ToolFailureKind.CLEANUP_FAILURE
    com.openminis.app.runtime.ExecutionCoordinator.FailureKind.RUNTIME_FAILURE, null -> null
}

class AndroidToolHandler(
    private val legacyName: String,
    private val newName: String,
) : ToolHandler {
    override val definition: AgentToolDefinition
        get() {
            val legacy = com.openminis.app.tools.android.AndroidAgentTools.definitions()
                .firstOrNull { it.name == legacyName }
                ?: return AgentToolDefinition(name = newName, description = legacyName, parameters = emptyMap())
            return legacy.copy(name = newName)
        }
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.android.AndroidAgentTools.execute(
            name = legacyName,
            argsJson = argsJson,
            sessionId = sessionId,
            context = context,
            toolId = toolId,
        )
}

class LinuxReadImageHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.ReadImageTool.definition().copy(name = "linux.file.image.read")
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.ReadImageTool.execute(argsJson, sessionId, context)
}

class AgentGoalHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.goal",
        description = "Get, create, or update the session goal. Pass action=get_goal|create_goal|update_goal; create_goal/update_goal also need `goal` text (empty clears).",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of this call, shown to the user."),
            "action" to com.openminis.app.data.model.AgentToolParam("string", "get_goal | create_goal | update_goal."),
            "goal" to com.openminis.app.data.model.AgentToolParam("string", "The goal text for create_goal/update_goal."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "goal"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.GoalTools.execute(JSONObject(argsJson).optString("action"), argsJson, sessionId, context)
}

class AgentTodoHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.todo",
        description = "Replace the session todo list in one atomic call. Pass `todos` as a JSON array of {title, status?, id?} where status is pending|in_progress|completed|skipped.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of this call, shown to the user."),
            "todos" to com.openminis.app.data.model.AgentToolParam(
                "array", "Full replacement list of todos.",
                items = com.openminis.app.data.model.AgentToolParam(
                    "object", "One todo.",
                    properties = mapOf(
                        "title" to com.openminis.app.data.model.AgentToolParam("string", "Todo text."),
                        "status" to com.openminis.app.data.model.AgentToolParam("string", "pending|in_progress|completed|skipped (default pending)."),
                        "id" to com.openminis.app.data.model.AgentToolParam("string", "Optional stable id."),
                    ),
                    requiredProperties = listOf("title"),
                ),
            ),
        ),
        required = listOf("tool_title", "todos"),
        propertyOrdering = listOf("tool_title", "todos"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.TodoTool.execute(argsJson, sessionId, context)
}

class AgentSubagentHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.subagent",
        description = "Delegate a self-contained sub-task to a child agent that runs in its own session with its own context, then return only its final answer. The child CANNOT see this conversation: write `prompt` as a complete, standalone task including all needed paths, names and constraints.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of the delegated task, shown to the user."),
            "prompt" to com.openminis.app.data.model.AgentToolParam("string", "The complete, self-contained task for the child agent."),
        ),
        required = listOf("tool_title", "prompt"),
        propertyOrdering = listOf("tool_title", "prompt"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.SubagentTool.execute(argsJson, sessionId, context)
}

class AgentAskHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.ask",
        description = "Pause and ask the user a concise question when you need confirmation, a choice, or missing information to continue. The user answers through the web UI and the answer comes back as a structured tool result.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of the question, shown to the user."),
            "question" to com.openminis.app.data.model.AgentToolParam("string", "The question to ask the user, in the user's language."),
            "options" to com.openminis.app.data.model.AgentToolParam(
                "array", "Optional answer choices, each {label, value, recommended?}.",
                items = com.openminis.app.data.model.AgentToolParam(
                    "object", "One answer choice.",
                    properties = mapOf(
                        "label" to com.openminis.app.data.model.AgentToolParam("string", "Human-readable label."),
                        "value" to com.openminis.app.data.model.AgentToolParam("string", "Stable machine-readable value."),
                        "recommended" to com.openminis.app.data.model.AgentToolParam("boolean", "Optional hint shown to the user."),
                    ),
                    requiredProperties = listOf("label", "value"),
                ),
            ),
            "multiple" to com.openminis.app.data.model.AgentToolParam("boolean", "Allow multiple selections (default false)."),
            "allowCustom" to com.openminis.app.data.model.AgentToolParam("boolean", "Allow a free-form custom answer (default true)."),
            "timeoutMinutes" to com.openminis.app.data.model.AgentToolParam("integer", "How long to wait for the user (1-30, default 10)."),
        ),
        required = listOf("tool_title", "question"),
        propertyOrdering = listOf("tool_title", "question", "options", "multiple", "allowCustom", "timeoutMinutes"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.AskUserQuestionTool.execute(argsJson, sessionId, context)
}

class SystemJobsHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "system.jobs",
        description = "Manage background jobs. Pass action=job_list (list all), job_kill (cancel by job_id), or job_output (read output by job_id; wait=true blocks until terminal status, up to timeout_ms).",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of this call, shown to the user."),
            "action" to com.openminis.app.data.model.AgentToolParam("string", "job_list | job_kill | job_output."),
            "job_id" to com.openminis.app.data.model.AgentToolParam("string", "The id of the background job (job_kill/job_output)."),
            "wait" to com.openminis.app.data.model.AgentToolParam("boolean", "Block until the job reaches a terminal status (job_output, default false)."),
            "timeout_ms" to com.openminis.app.data.model.AgentToolParam("integer", "Maximum wait in milliseconds when wait=true (default 30000)."),
            "reason" to com.openminis.app.data.model.AgentToolParam("string", "Optional short reason for job_kill."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "job_id", "wait", "timeout_ms", "reason"),
        timeoutMs = 120_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.JobTools.execute(JSONObject(argsJson).optString("action"), argsJson, sessionId, context)
}

class AgentRalphHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.RalphTool.definition().copy(name = "agent.ralph")
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.RalphTool.execute(argsJson, sessionId, context)
}
