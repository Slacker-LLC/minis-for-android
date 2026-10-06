package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import com.openminis.app.util.shellQuote
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Every read and write holds the registry's monitor: MCP reloads register and unregister tools on an
 * IO thread while a turn reads definitions and resolves names, and handlers and aliases must change
 * together. Readers get snapshots, never the live maps.
 */
object ToolRegistry {
    private val handlers = linkedMapOf<String, ToolHandler>()
    private val aliases = linkedMapOf<String, String>()

    /**
     * Aliases that name an action of a merged handler (`get_goal` → `agent.goal` with
     * action=get_goal). Their published schemas have no `action` parameter, so the call carries
     * the action in its name; [argsForCall] puts it back.
     */
    private val actionAliases = mutableSetOf<String>()

    internal fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Register [handler] under its name, its wire name and [aliasNames]. Registering the same name again
     * replaces that handler. A name or wire name that already belongs to a DIFFERENT tool is a conflict:
     * the call is refused and false returned, because silently re-pointing a name would send the model's
     * call to the wrong tool (two MCP servers or tools whose names sanitize alike). An alias another tool
     * already holds is left with that tool and skipped.
     *
     * @param aliasesAreActions the aliases are action names of this handler; a call by alias gets
     *   `action=<alias>` when it does not carry an action itself.
     */
    @Synchronized
    fun register(handler: ToolHandler, aliasNames: List<String> = emptyList(), aliasesAreActions: Boolean = false): Boolean {
        val defName = handler.definition.name
        val apiName = handler.definition.apiName
        fun ownerOf(name: String) = aliases[name] ?: name.takeIf { handlers.containsKey(it) }
        // The tool's own names must be free: a collision there would send the model's call to
        // the wrong tool. An alias is a convenience: one already held by another tool stays
        // with it and only that alias is dropped, so the tool itself still registers.
        val conflict = listOf(defName, apiName).distinct().firstOrNull { name ->
            ownerOf(name).let { it != null && it != defName }
        }
        if (conflict != null) {
            android.util.Log.w("ToolRegistry", "refused $defName: '$conflict' already names another tool")
            return false
        }
        val freeAliases = aliasNames.filter { name ->
            ownerOf(name).let { it == null || it == defName }
        }
        (aliasNames - freeAliases.toSet()).forEach {
            android.util.Log.w("ToolRegistry", "alias '$it' of $defName stays with ${ownerOf(it)}")
        }
        handlers[defName] = handler
        if (apiName != defName) aliases[apiName] = defName
        for (a in freeAliases) aliases[a] = defName
        if (aliasesAreActions) actionAliases.addAll(freeAliases)
        return true
    }

    @Synchronized
    fun unregister(name: String) {
        val canonical = canonicalName(name) ?: return
        handlers.remove(canonical)
        aliases.filterValues { it == canonical }.keys.toList().forEach {
            aliases.remove(it)
            actionAliases.remove(it)
        }
    }

    /**
     * The arguments to hand the handler for a call made by [name]. Unchanged unless [name] is an
     * action alias and the arguments carry no action, in which case the alias becomes the action.
     */
    fun argsForCall(name: String, argsJson: String): String {
        val alias = actionAliases.firstOrNull { it == name } ?: actionAliases.firstOrNull {
            it.equals(name, ignoreCase = true) || normalize(it) == normalize(name)
        } ?: return argsJson
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        if (args.optString("action").isNotBlank()) return argsJson
        return args.put("action", alias).toString()
    }

    @Synchronized
    fun canonicalName(name: String): String? {
        if (handlers.containsKey(name)) return name
        aliases[name]?.let { return it }

        // Case-insensitive lookup
        for ((k, _) in handlers) {
            if (k.equals(name, ignoreCase = true)) return k
        }
        for ((k, v) in aliases) {
            if (k.equals(name, ignoreCase = true)) return v
        }

        // Normalized (strip '.', '_', '-') alphanumeric lookup
        val norm = normalize(name)
        if (norm.isEmpty()) return null
        for (k in handlers.keys) {
            if (normalize(k) == norm) return k
        }
        for ((k, v) in aliases) {
            if (normalize(k) == norm) return v
        }
        return null
    }

    @Synchronized
    fun aliasesFor(canonicalName: String): List<String> =
        aliases.filterValues { it == canonicalName }.keys.toList()

    @Synchronized
    fun allKnownNames(canonicalName: String): Set<String> {
        val canonical = canonicalName(canonicalName) ?: canonicalName
        val result = mutableSetOf(canonical)
        definition(canonical)?.let {
            result.add(it.apiName)
        }
        result.addAll(aliasesFor(canonical))
        return result
    }

    @Synchronized
    fun definition(name: String): AgentToolDefinition? = canonicalName(name)?.let { handlers[it]?.definition }
    @Synchronized
    fun definitions(): List<AgentToolDefinition> = handlers.values.map { it.definition }
    @Synchronized
    fun handler(name: String): ToolHandler? = canonicalName(name)?.let { handlers[it] }
    @Synchronized
    fun contains(name: String): Boolean = canonicalName(name) != null

    fun definitionsForCaller(caller: String): List<AgentToolDefinition> {
        if (caller == ToolPermissionManager.CALLER_LOCAL) return definitions()
        val mcpVisible = ToolPermissionManager.mcpVisibleTools()
        return definitions().filter { it.name in mcpVisible }
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
        val handler = ToolRegistry.handler(canonical)
            ?: return ToolExecutionResult("Error: no handler for $canonical", false)
        @Suppress("NAME_SHADOWING")
        val argsJson = ToolRegistry.argsForCall(name, argsJson)
        if (com.openminis.app.offload.OffloadPermissionManager.tierFor(sessionId) ==
            com.openminis.app.scheduled.ScheduledTaskPermissionTier.READ_ONLY
        ) {
            val policy = com.openminis.app.scheduled.ScheduledReadOnlyPolicy
            val mcpDenial = policy.mcpDenial(canonical, handler.isMcpTool)
            if (mcpDenial != null) {
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, canonical, "remote MCP integration",
                )
                return ToolExecutionResult("Error: $mcpDenial", false)
            }
            if (canonical == "linux.shell") {
                val command = runCatching { JSONObject(argsJson).optString("command", "") }.getOrDefault("")
                val denial = policy.shellDenial(command)
                if (denial != null) {
                    com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                        sessionId, canonical, denial.substringAfter(": ").take(200),
                    )
                    return ToolExecutionResult("Error: $denial", false)
                }
            } else if (policy.codeExecutionDenial(canonical) != null) {
                val denial = policy.codeExecutionDenial(canonical)!!
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, canonical, "code execution or package installation",
                )
                return ToolExecutionResult("Error: $denial", false)
            } else {
                val args = runCatching { JSONObject(argsJson) }.getOrNull()
                val denial = policy.fileWriteDenial(canonical, args)
                if (denial != null) {
                    com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                        sessionId, canonical, policy.fileWriteSummary(canonical, args),
                    )
                    return ToolExecutionResult("Error: $denial", false)
                }
            }
        }
        val requestedDisplayId = runCatching {
            val value = JSONObject(argsJson).opt("displayId")
            when (value) {
                is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
                is String -> value.toIntOrNull()?.takeIf { it >= 0 }
                else -> null
            }
        }.getOrNull()
        if (ToolPermissionManager.isRemoteVirtualDisplayDenied(canonical, caller, requestedDisplayId)) {
            return ToolExecutionResult("Error: permission_denied: virtual-display android.ui is local-only", false)
        }
        if (!ToolPermissionManager.isAllowedFor(canonical, caller, sessionId, requestedDisplayId)) {
            return ToolExecutionResult("Error: permission_denied: $canonical", false)
        }
        if (ToolPermissionManager.needsConfirm(canonical, caller) && !confirmBypassed) {
            // MCP callers must arrive here only after MCPServer consumed its
            // caller+method+arguments-bound ticket. Local Agent calls use the
            // existing in-app approval seam instead of retrying forever with a
            // confirm_required result that has no ticket to approve.
            if (caller != ToolPermissionManager.CALLER_LOCAL) {
                return ToolExecutionResult("Error: confirm_required: $canonical", false)
            }
            val decision = com.openminis.app.tools.ApprovalSeam.request(
                context = context,
                sessionId = sessionId,
                toolName = canonical,
                summary = "参数=${argsJson.take(800)}",
            )
            if (decision.decision != "allowed-once") {
                return ToolExecutionResult(
                    "Error: approval_${decision.decision}: $canonical",
                    false,
                )
            }
        }
        val raw = ProviderRouter.route(canonical)
            ?.execute(canonical, argsJson, sessionId, context, toolId) {
                handler.execute(argsJson, sessionId, context, toolId)
            }
            ?: handler.execute(argsJson, sessionId, context, toolId)
        // [T-tool-result-budget-android] The dispatch is where a result stops being a
        // tool's private business and becomes a message part, so it is where the bound
        // lives; tools that already bound themselves stay well under it.
        return ToolResultBudget.bounded(sessionId = sessionId, toolName = canonical, result = raw)
    }
}

interface ToolHandler {
    val definition: AgentToolDefinition
    val isMcpTool: Boolean get() = false
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
        val readOnly = com.openminis.app.offload.OffloadPermissionManager.tierFor(sessionId) ==
            com.openminis.app.scheduled.ScheduledTaskPermissionTier.READ_ONLY
        if (readOnly) {
            val policy = com.openminis.app.scheduled.ScheduledReadOnlyPolicy
            val decision = policy.evaluateShell(command)
            val staticDenial = policy.shellDenial(command)
            if (staticDenial != null) {
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, "linux.shell", staticDenial.substringAfter(": ").take(200),
                )
                return ToolExecutionResult("Error: $staticDenial", false)
            }
            val unsafeRedirect = decision.redirectPaths.firstOrNull { target ->
                !isSafeTemporaryRedirect(sessionId, target)
            }
            if (unsafeRedirect != null) {
                val summary = "temporary redirect path failed session validation: $unsafeRedirect"
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, "linux.shell", policy.sanitizeSummary(summary),
                )
                return ToolExecutionResult(
                    "Error: shell_denied_readonly_tier: ${policy.sanitizeSummary(summary)}",
                    false,
                )
            }
        }
        if (command.isBlank()) {
            val denial = "shell_denied_readonly_tier: <empty>"
            if (readOnly) {
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, "linux.shell", "<empty>",
                )
                return ToolExecutionResult("Error: $denial", false)
            }
            return ToolExecutionResult("Error: 'command' is required", false)
        }
        val requestedMs = if (args.has("timeout")) args.optLong("timeout") * 1_000L else null
        val timeoutMs = ToolTimeoutPolicy.resolve("linux.shell", callerOverrideMs = requestedMs).timeoutMs ?: 900_000L
        val commandToExecute = if (readOnly) {
            com.openminis.app.scheduled.ScheduledReadOnlyPolicy.hardenGitInvocation(command)
        } else command
        val result = com.openminis.app.runtime.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = commandToExecute,
            timeout = timeoutMs,
        )
        val failureKind = result.toToolFailureKind()
        return ToolExecutionResult(
            output = result.output,
            success = result.exitCode == 0 && failureKind == null,
            toolTitle = args.optString("tool_title", "linux.shell"),
            timedOut = failureKind == ToolFailureKind.TOOL_TIMEOUT,
            failureKind = failureKind,
        )
    }

    private suspend fun isSafeTemporaryRedirect(sessionId: String, rawPath: String): Boolean {
        val policy = com.openminis.app.scheduled.ScheduledReadOnlyPolicy
        val guestPath = policy.canonicalTemporaryPath(rawPath) ?: return false
        val root = com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveForFileAccess(
            sessionId, policy.TEMP_DIRECTORY,
        ) ?: return false
        val target = com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveForFileAccess(
            sessionId, guestPath,
        ) ?: return false
        val rootPath = runCatching { root.canonicalPath.trimEnd(java.io.File.separatorChar) }.getOrNull()
            ?: return false
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return false
        return targetPath.startsWith("$rootPath${java.io.File.separator}")
    }
}

class LinuxPythonRunHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "linux.python.run",
        description = "Run Python 3 code in the on-device Ubuntu 24.04 environment as the Android app UID (not Root). " +
            "Pass code as a string; it is written to the session-scoped /workspace and executed with python3. " +
            "Linux capabilities are cleared; use linux.file.write for files first if the script is long.",
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
        val readinessFailure = com.openminis.app.runtime.ExecutionCoordinator.ensureRuntimeReady()
        if (readinessFailure != null) {
            val failureKind = readinessFailure.toToolFailureKind()
            return ToolExecutionResult(
                output = readinessFailure.output,
                success = false,
                toolTitle = args.optString("tool_title", "linux.python.run"),
                timedOut = failureKind == ToolFailureKind.TOOL_TIMEOUT,
                failureKind = failureKind,
            )
        }

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
                val failureKind = result.toToolFailureKind()
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
                    com.openminis.app.runtime.files.WorkspaceFileClient.delete(sessionId, scriptPath)
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
}

private fun com.openminis.app.runtime.ExecutionCoordinator.CommandResult.toToolFailureKind(): ToolFailureKind? =
    if (exitCode == 124) ToolFailureKind.TOOL_TIMEOUT else null

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
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "") }.getOrDefault("")
        val app = context.applicationContext as? com.openminis.app.MinisApp
        // Delegation is one level deep from a sub agent: its own session never delegates, whichever
        // way the tool is configured.
        val isSubAgentChild = app?.chatRepository?.getSession(sessionId)?.source ==
            com.openminis.app.data.db.ChatSessionEntity.SOURCE_SUB_AGENT
        if (isSubAgentChild) {
            return ToolExecutionResult(
                com.openminis.app.agent.subagents.SubAgentTask.error(
                    "depth_limit", "A sub agent cannot delegate further. Do this work yourself.",
                ),
                false, toolTitle = title,
            )
        }
        // While sub agents are allowed (Settings) the model was given the roster schema, so the call
        // goes to the roster runtime; with the switch off the older single-shot behaviour is unchanged.
        if (!com.openminis.app.agent.subagents.SubAgents.isEnabled()) {
            return com.openminis.app.tools.SubagentTool.execute(argsJson, sessionId, context)
        }
        val reply = com.openminis.app.agent.subagents.SubAgents.runtime(context).execute(argsJson, sessionId)
        return ToolExecutionResult(reply.text, reply.ok, toolTitle = title)
    }
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
