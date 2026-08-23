package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * P3 Tool Runtime — registry + executor (06 §1/§4).
 *
 * A [ToolHandler] exposes a [AgentToolDefinition] (name/description/schema,
 * provider-agnostic) and executes it. Registry owns name → handler and the
 * D12 mapping (new `linux.*` names + old flat aliases); executor owns the
 * permission gate and fixed error semantics.
 *
 * Not wired into ChatViewModel yet — that swap happens per-tool in P3
 * after each handler is migrated (06 §5). This object is the destination.
 */
object ToolRegistry {

    private val handlers = linkedMapOf<String, ToolHandler>()

    /** Old flat name -> same handler (alias for migration window). */
    private val aliases = linkedMapOf<String, String>()

    fun register(handler: ToolHandler, aliasNames: List<String> = emptyList()) {
        handlers[handler.definition.name] = handler
        for (a in aliasNames) aliases[a] = handler.definition.name
    }

    fun canonicalName(name: String): String? =
        if (handlers.containsKey(name)) name else aliases[name]

    fun definition(name: String): AgentToolDefinition? =
        canonicalName(name)?.let { handlers[it]?.definition }

    fun definitions(): List<AgentToolDefinition> = handlers.values.map { it.definition }

    fun handler(name: String): ToolHandler? = canonicalName(name)?.let { handlers[it] }

    fun contains(name: String): Boolean = canonicalName(name) != null

    /**
     * Definitions visible to [caller]: local sees everything registered;
     * MCP sees only tools allowed by the permission table (local-only and
     * denied are filtered out) that also have a handler here.
     */
    fun definitionsForCaller(caller: String): List<AgentToolDefinition> {
        if (caller == ToolPermissionManager.CALLER_LOCAL) return definitions()
        val mcpVisible = ToolPermissionManager.mcpVisibleTools()
        return handlers.values
            .map { it.definition }
            .filter { it.name in mcpVisible }
    }
}

/**
 * Tool executor: gates via [ToolPermissionManager], runs the handler,
 * fixes error semantics for linux.* availability.
 */
object ToolExecutor {

    /** Runs [name] for [caller]; returns structured result. */
    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String,
        context: Context,
        caller: String = ToolPermissionManager.CALLER_LOCAL,
        toolId: String = "",
        /** True when an MCP_CONFIRM gate was already consumed by the caller (MCP confirm flow). */
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

        // P4 (06 §1): a provider claiming this name wraps handler execution
        // (e.g. LinuxProvider's ubuntu_runtime_unavailable gate). No provider
        // → unchanged default handler path (backward compatible).
        val provider = ProviderRouter.route(canonical)
            ?: return handler.execute(argsJson, sessionId, context, toolId)

        return provider.execute(canonical, argsJson, sessionId, context, toolId) {
            handler.execute(argsJson, sessionId, context, toolId)
        }
    }
}

/** A registered tool: definition + execution. */
interface ToolHandler {
    val definition: AgentToolDefinition
    suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String = ""): ToolExecutionResult
}

// ── linux.* handlers (P3 first wave) ────────────────────────────────────────
// File handlers reuse the existing FileRead/Write/Edit implementations
// (identical semantics to the old flat tools) and expose the D12 names.

/** `linux.file.read` — same implementation as file_read. */
class LinuxFileReadHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileReadTool.definition().copy(name = "linux.file.read")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileReadTool.execute(argsJson, sessionId, context)
}

/** `linux.file.write` — same implementation as file_write. */
class LinuxFileWriteHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileWriteTool.definition().copy(name = "linux.file.write")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileWriteTool.execute(argsJson, sessionId, context)
}

/** `linux.file.edit` — same implementation as file_edit. */
class LinuxFileEditHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.FileEditTool.definition().copy(name = "linux.file.edit")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.FileEditTool.execute(argsJson, sessionId, context)
}

/** `linux.shell` — same implementation as shell_execute (Ubuntu runtime). */
class LinuxShellHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.AgentTools.shellExecuteDefinition(name = "linux.shell")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = org.json.JSONObject(argsJson)
        val command = args.optString("command")
        if (command.isBlank()) {
            return ToolExecutionResult("Error: 'command' is required", false)
        }
        val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
        val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = command,
            timeout = timeoutSec * 1000L,
        )
        return ToolExecutionResult(
            output = result.output,
            success = result.exitCode == 0 || result.exitCode == 124,
            toolTitle = args.optString("tool_title", "linux.shell"),
            timedOut = result.exitCode == 124,
        )
    }
}

/** `linux.python.run` — run a python script/code snippet in the Ubuntu runtime. */
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
        timeoutMs = 300_000L,
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = org.json.JSONObject(argsJson)
        val code = args.optString("code")
        if (code.isBlank()) {
            return ToolExecutionResult("Error: 'code' is required", false)
        }
        val timeoutSec = args.optInt("timeout", 300).coerceIn(1, 900)
        // Write to a temp script in workspace then run; keeps output bounded.
        val scriptPath = "python_run_${System.currentTimeMillis()}.py"
        val write = com.openminis.app.tools.FileWriteTool.execute(
            """{"tool_title":"write python script","path":"/workspace/$scriptPath","content":${org.json.JSONObject.quote(code)}}""",
            sessionId, context,
        )
        if (!write.success) return write
        val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = "python3 /workspace/$scriptPath",
            timeout = timeoutSec * 1000L,
        )
        return ToolExecutionResult(
            output = result.output,
            success = result.exitCode == 0 || result.exitCode == 124,
            toolTitle = args.optString("tool_title", "linux.python.run"),
            timedOut = result.exitCode == 124,
        )
    }
}

// ── android.* handlers (P3, read-oriented first wave) ────────────────────────
// One generic handler forwards to AndroidAgentTools.execute by old name;
// each registered D12 name maps to the corresponding legacy tool. The
// action gate (info-only vs mutating) stays in AndroidAgentTools itself.

/** Generic android.* handler bound to one legacy AndroidAgentTools name. */
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

// ── image read + agent.*/system.jobs handlers (P3 read-first wave 2) ────────

/** `linux.file.image.read` — same implementation as read_image. */
class LinuxReadImageHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.ReadImageTool.definition().copy(name = "linux.file.image.read")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.ReadImageTool.execute(argsJson, sessionId, context)
}

/** `agent.goal` — get_goal / create_goal / update_goal dispatched by args action. */
class AgentGoalHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.goal",
        description = "Get, create, or update the session goal. Pass action=get_goal|create_goal|update_goal; " +
            "create_goal/update_goal also need `goal` text (empty clears).",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of this call, shown to the user."),
            "action" to com.openminis.app.data.model.AgentToolParam("string", "get_goal | create_goal | update_goal."),
            "goal" to com.openminis.app.data.model.AgentToolParam("string", "The goal text for create_goal/update_goal."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "goal"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val name = org.json.JSONObject(argsJson).optString("action")
        return com.openminis.app.tools.GoalTools.execute(name, argsJson, sessionId, context)
    }
}

/** `agent.todo` — same implementation as todo_write. */
class AgentTodoHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.todo",
        description = "Replace the session todo list in one atomic call. Pass `todos` as a JSON array of " +
            "{title, status?, id?} where status is pending|in_progress|completed|skipped.",
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

/** `agent.subagent` — same implementation as subagent. */
class AgentSubagentHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.subagent",
        description = "Delegate a self-contained sub-task to a child agent that runs in its own session with its own " +
            "context, then return only its final answer. The child CANNOT see this conversation: write `prompt` as a " +
            "complete, standalone task including all needed paths, names and constraints.",
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

/** `agent.ask` — same implementation as ask_user_question. */
class AgentAskHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "agent.ask",
        description = "Pause and ask the user a concise question when you need confirmation, a choice, or missing " +
            "information to continue. The user answers through the web UI and the answer comes back as a structured " +
            "tool result.",
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

/** `system.jobs` — job_list / job_kill / job_output dispatched by args action. */
class SystemJobsHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "system.jobs",
        description = "Manage background jobs. Pass action=job_list (list all), job_kill (cancel by job_id), or " +
            "job_output (read output by job_id; wait=true blocks until terminal status, up to timeout_ms).",
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

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val name = org.json.JSONObject(argsJson).optString("action")
        return com.openminis.app.tools.JobTools.execute(name, argsJson, sessionId, context)
    }
}

/** `agent.ralph` — same implementation as ralph. */
class AgentRalphHandler : ToolHandler {
    override val definition: AgentToolDefinition =
        com.openminis.app.tools.RalphTool.definition().copy(name = "agent.ralph")

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.RalphTool.execute(argsJson, sessionId, context)
}
