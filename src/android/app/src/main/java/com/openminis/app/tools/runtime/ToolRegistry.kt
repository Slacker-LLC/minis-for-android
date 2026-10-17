package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import com.openminis.app.util.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.openminis.app.tools.ToolCheckpointStore
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
    @Synchronized
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
        val offered = definitions().filter {
            it.name != com.openminis.app.agent.subagents.SubAgentToolSchema.HANDLER_NAME ||
                com.openminis.app.agent.subagents.SubAgents.isEnabled()
        }
        if (caller == ToolPermissionManager.CALLER_LOCAL) return offered
        val mcpVisible = ToolPermissionManager.mcpVisibleTools()
        return offered.filter { it.name in mcpVisible }
    }
}

object ToolExecutor {
    private val executingIntents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun isExecutionInFlight(executionId: String?): Boolean = executionId != null &&
        (executingIntents.contains(executionId) || com.openminis.app.runtime.guest.NativeOffloadServer.nativeInFlight(executionId) ||
            com.openminis.app.agent.subagents.SubAgents.executionInFlight(executionId) ||
            com.openminis.app.tools.JobRegistry.list().any { it.executionIntentId == executionId && it.status == com.openminis.app.tools.JobRegistry.JobStatus.RUNNING })
    /** Immutable model identity; a guest/MCP response is not a persisted model receipt. */
    data class ModelExecution(val callId: String, val runId: String?, val taskId: String?,
        val revision: Int? = null, val stopGeneration: Int? = null)

    /** Execution identity is supplied by the runtime, never by model arguments/call IDs. */
    internal class ExecutionIdentity(val model: ModelExecution, val intentId: String) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<ExecutionIdentity>
    }

    /** Validate the same published contract before a caller issues or consumes approval. */
    fun validateArguments(name: String, arguments: JSONObject): String? {
        val canonical = ToolRegistry.canonicalName(name) ?: return "Error: unknown_tool: $name"
        val definition = ToolRegistry.definition(canonical) ?: return "Error: no handler for $canonical"
        val aliasAction = JSONObject(ToolRegistry.argsForCall(name, "{}")).optString("action")
        if (aliasAction.isNotEmpty() && arguments.has("action") && arguments.optString("action") != aliasAction) {
            return "Error: action_alias_mismatch: $name requires action=$aliasAction"
        }
        val normalized = JSONObject(ToolRegistry.argsForCall(name, arguments.toString()))
        return ToolCallValidator.preflight(canonical, normalized, listOf(definition))?.let { "Error: invalid_arguments: $it" }
    }

    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String,
        context: Context,
        caller: String = ToolPermissionManager.CALLER_LOCAL,
        toolId: String = "",
        confirmBypassed: Boolean = false,
        // A trusted local transport may bind its existing engine and output representation.
        // This callback is never selected by tool JSON or exposed through MCP.
        localExecution: (suspend () -> ToolExecutionResult)? = null,
        boundOutput: Boolean = true,
        modelExecution: ModelExecution? = null,
    ): ToolExecutionResult {
        fun rejected(output: String, kind: ToolFailureKind) =
            ToolExecutionResult(output, false, failureKind = kind, executionStarted = false)
        if ((localExecution != null || modelExecution != null) && caller != ToolPermissionManager.CALLER_LOCAL) {
            return rejected("Error: permission_denied: local execution binding", ToolFailureKind.PERMISSION_DENIED)
        }
        val canonical = ToolRegistry.canonicalName(name)
            ?: return rejected("Error: unknown_tool: $name", ToolFailureKind.UNAVAILABLE)
        val handler = ToolRegistry.handler(canonical)
            ?: return rejected("Error: no handler for $canonical", ToolFailureKind.UNAVAILABLE)
        val originalArgs = try { JSONObject(argsJson) } catch (_: org.json.JSONException) {
            return rejected("Error: invalid_arguments: expected a JSON object", ToolFailureKind.INVALID_ARGUMENTS)
        }
        validateArguments(name, originalArgs)?.let { return rejected(it, ToolFailureKind.INVALID_ARGUMENTS) }
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
                return rejected("Error: $mcpDenial", ToolFailureKind.PERMISSION_DENIED)
            }
            if (canonical == "linux.shell") {
                val command = runCatching { JSONObject(argsJson).optString("command", "") }.getOrDefault("")
                val denial = policy.shellDenial(command)
                if (denial != null) {
                    com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                        sessionId, canonical, denial.substringAfter(": ").take(200),
                    )
                    return rejected("Error: $denial", ToolFailureKind.PERMISSION_DENIED)
                }
            } else if (policy.codeExecutionDenial(canonical) != null) {
                val denial = policy.codeExecutionDenial(canonical)!!
                com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                    sessionId, canonical, "code execution or package installation",
                )
                return rejected("Error: $denial", ToolFailureKind.PERMISSION_DENIED)
            } else {
                val args = runCatching { JSONObject(argsJson) }.getOrNull()
                val denial = policy.fileWriteDenial(canonical, args)
                if (denial != null) {
                    com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                        sessionId, canonical, policy.fileWriteSummary(canonical, args),
                    )
                    return rejected("Error: $denial", ToolFailureKind.PERMISSION_DENIED)
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
            return rejected("Error: permission_denied: virtual-display android.ui is local-only", ToolFailureKind.PERMISSION_DENIED)
        }
        if (!ToolPermissionManager.isAllowedFor(canonical, caller, sessionId, requestedDisplayId)) {
            return rejected("Error: permission_denied: $canonical", ToolFailureKind.PERMISSION_DENIED)
        }
        if (ToolPermissionManager.needsConfirm(canonical, caller) && !confirmBypassed) {
            // MCP callers must arrive here only after MCPServer consumed its
            // caller+method+arguments-bound ticket. Local Agent calls use the
            // existing in-app approval seam instead of retrying forever with a
            // confirm_required result that has no ticket to approve.
            if (caller != ToolPermissionManager.CALLER_LOCAL) {
                return rejected("Error: confirm_required: $canonical", ToolFailureKind.APPROVAL_REQUIRED)
            }
            val decision = com.openminis.app.tools.ApprovalSeam.request(
                context = context,
                sessionId = sessionId,
                toolName = canonical,
                summary = "参数=${argsJson.take(800)}",
            )
            if (decision.decision != "allowed-once") {
                return rejected(
                    "Error: approval_${decision.decision}: $canonical",
                    ToolFailureKind.APPROVAL_DENIED,
                )
            }
        }
        currentCoroutineContext().ensureActive()
        // Gates and approval have finished. Persist immediately before entering the execution body.
        val intentId = if (modelExecution != null) {
            ToolCheckpointStore.recordIntent(context, sessionId, modelExecution.callId, name,
                originalArgs.toString(), modelExecution.runId, modelExecution.taskId,
                modelExecution.revision, modelExecution.stopGeneration)
                ?: return rejected("Tool execution checkpoint could not be persisted; no action was executed.", ToolFailureKind.CHECKPOINT_FAILURE)
        } else null
        currentCoroutineContext().ensureActive()
        suspend fun invokeBody(): ToolExecutionResult = localExecution?.invoke() ?: ProviderRouter.route(canonical)
            ?.execute(canonical, argsJson, sessionId, context, toolId) {
                handler.executeForCaller(argsJson, sessionId, context, toolId, caller)
            } ?: handler.executeForCaller(argsJson, sessionId, context, toolId, caller)
        suspend fun invokeHandler(): ToolExecutionResult = if (modelExecution != null && intentId != null) {
            kotlinx.coroutines.withContext(ExecutionIdentity(modelExecution, intentId)) { invokeBody() }
        } else invokeBody()
        // Shared cooperative deadline; approval waiting does not consume tool execution time.
        val timeoutMs = handler.definition.timeoutMs
        val nativeOutcome = if (canonical == "linux.shell" && modelExecution != null)
            com.openminis.app.runtime.guest.NativeOffloadServer.beginShell(sessionId, checkNotNull(intentId)) else null
        if (intentId != null) executingIntents.add(intentId)
        val raw = try {
            if (timeoutMs == null) invokeHandler() else withTimeoutOrNull(timeoutMs) { invokeHandler() }
                ?: ToolExecutionResult("Error: tool call timed out after $timeoutMs ms.", false,
                    timedOut = true, failureKind = ToolFailureKind.TOOL_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult("Error: tool execution returned no definitive outcome: ${error.message ?: error.javaClass.simpleName}",
                false, failureKind = ToolFailureKind.OUTCOME_UNKNOWN)
        } finally {
            if (intentId != null) executingIntents.remove(intentId)
            if (nativeOutcome != null) com.openminis.app.runtime.guest.NativeOffloadServer.endShell(sessionId, nativeOutcome)
        }
        currentCoroutineContext().ensureActive()
        val nativeUnknown = nativeOutcome?.unknownKind ?: nativeOutcome?.takeIf {
            com.openminis.app.runtime.guest.NativeOffloadServer.nativeInFlight(it.executionId)
        }?.let { ToolFailureKind.OUTCOME_UNKNOWN }
        val classified = (if (nativeUnknown != null && raw.failureKind?.outcomeUnknown != true)
            raw.copy(failureKind = nativeUnknown) else raw).classifiedAfterExecution()
        // [T-tool-result-budget-android] The dispatch is where a result stops being a
        // tool's private business and becomes a message part, so it is where the bound
        // lives; tools that already bound themselves stay well under it.
        val result = if (boundOutput) ToolResultBudget.bounded(sessionId = sessionId, toolName = canonical, result = classified) else classified
        return if (modelExecution == null) result else result.copy(executionIntentId = intentId,
            executionRunId = modelExecution.runId, executionTaskId = modelExecution.taskId)
    }
}

interface ToolHandler {
    val definition: AgentToolDefinition
    val isMcpTool: Boolean get() = false
    suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String = ""): ToolExecutionResult
    /** Caller comes from the trusted transport, never from tool arguments. */
    suspend fun executeForCaller(argsJson: String, sessionId: String, context: Context, toolId: String, caller: String): ToolExecutionResult =
        execute(argsJson, sessionId, context, toolId)
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

/** Upper bound for the `delay` argument, so a typo cannot park a turn for hours. */
internal const val MAX_SHELL_DELAY_SEC = 600

/** Milliseconds to wait before a shell command starts: `delay` seconds, clamped to 0..[MAX_SHELL_DELAY_SEC]. */
internal fun shellDelayMs(args: JSONObject): Long =
    args.optInt("delay", 0).coerceIn(0, MAX_SHELL_DELAY_SEC) * 1_000L

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
        // `delay` waits in the agent's own coroutine, not in the shell, so other jobs keep the shell
        // and cancelling the turn ends the wait. The tool timeout only starts once it is over.
        val delayMs = shellDelayMs(args)
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        if (args.optBoolean("background", false)) {
            // A scheduled read-only run may not leave anything running behind it.
            if (readOnly) {
                return ToolExecutionResult("Error: shell_denied_readonly_tier: background jobs", false)
            }
            return ShellBackground.start(args, command, sessionId)
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
        description = com.openminis.app.tools.ToolText.goalActionsSummary(),
        parameters = mapOf(
            "tool_title" to com.openminis.app.tools.ToolText.TOOL_TITLE,
            "action" to com.openminis.app.data.model.AgentToolParam("string", "The goal action to run: get_goal, create_goal, update_goal, verify_outcome, or review_restored_task."),
            "goal" to com.openminis.app.data.model.AgentToolParam("string", "The goal text for create_goal and update_goal."),
            "acceptance_criteria" to com.openminis.app.data.model.AgentToolParam("string", "The conditions that you can verify to confirm completion. If you omit it on update, the old value stays."),
            "max_goal_rounds" to com.openminis.app.data.model.AgentToolParam("integer", "The maximum number of automatic continuation rounds, from 1 to 100. An update does not reset the rounds that the goal already used."),
            "status" to com.openminis.app.data.model.AgentToolParam("string", "The new status: completed, paused, waiting, or needs_user. Use it instead of goal text."),
            "task_id" to com.openminis.app.data.model.AgentToolParam("string", "The exact current task_id from get_goal."),
            "revision" to com.openminis.app.data.model.AgentToolParam("integer", "The current revision from get_goal."),
            "stop_generation" to com.openminis.app.data.model.AgentToolParam("integer", "The current stop_generation from get_goal."),
            "progress" to com.openminis.app.data.model.AgentToolParam("string", "How the acceptance criteria were met, or the concrete reason to wait or stop. The maximum is 2000 characters."),
            "verification" to com.openminis.app.data.model.AgentToolParam("string", "The method for verify_outcome: automatic (default) or user. With user, the actual user inspects the effect.", enumValues = listOf("automatic", "user")),
            "outcome" to com.openminis.app.data.model.AgentToolParam("object", "The one exact unknown execution from get_goal. Use it for verify_outcome only.", properties = mapOf(
                "execution_id" to com.openminis.app.data.model.AgentToolParam("string", "The exact ID of the unknown execution. A call ID alone is not unique."),
                "run_id" to com.openminis.app.data.model.AgentToolParam("string", "The original run ID of the unknown execution."),
            ), requiredProperties = listOf("execution_id", "run_id")),
            "evidence" to com.openminis.app.data.model.AgentToolParam("array", "The references to actual evidence, from 1 to 16. Minis hashes files. A tool result must be a persisted success.",
                items = com.openminis.app.data.model.AgentToolParam("object", "A file or a durable tool receipt.", properties = mapOf(
                    "type" to com.openminis.app.data.model.AgentToolParam("string", "One of file, document, or tool_result."),
                    "uri" to com.openminis.app.data.model.AgentToolParam("string", "A content URI that the user granted persistently (type=document). The app checks it in its own permission domain."),
                    "path" to com.openminis.app.data.model.AgentToolParam("string", "The path of a workspace, shared, or mounted file (type=file)."),
                    "sha256" to com.openminis.app.data.model.AgentToolParam("string", "The optional expected SHA-256 of the file. A mismatch rejects the completion."),
                    "call_id" to com.openminis.app.data.model.AgentToolParam("string", "The original tool call ID (type=tool_result)."),
                    "execution_id" to com.openminis.app.data.model.AgentToolParam("string", "The exact execution ID (type=tool_result) when a call ID was reused."),
                ), requiredProperties = listOf("type"))),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "goal"),
        timeoutMs = 605_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
        com.openminis.app.tools.GoalTools.execute(JSONObject(argsJson).optString("action"), argsJson, sessionId, context, toolId)
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
    override val definition: AgentToolDefinition
        get() = com.openminis.app.agent.subagents.SubAgentToolSchema.definition(
            com.openminis.app.agent.subagents.SubAgentStore.currentRoster().map { it.name },
            name = com.openminis.app.agent.subagents.SubAgentToolSchema.HANDLER_NAME,
        )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", "") }.getOrDefault("")
        val app = context.applicationContext as? com.openminis.app.MinisApp
        // Delegation is one level deep from a sub agent: its own session never delegates, whichever
        // way the tool is configured.
        val isSubAgentChild = com.openminis.app.data.db.ChatSessionEntity.isSubAgentSource(
            app?.chatRepository?.getSession(sessionId)?.source,
        )
        if (isSubAgentChild) {
            return ToolExecutionResult(
                com.openminis.app.agent.subagents.SubAgentTask.error(
                    "depth_limit", "A sub agent cannot delegate further. Do this work yourself.",
                ),
                false, toolTitle = title,
            )
        }
        // The Settings switch also gates callers that still hold an old schema.
        if (!com.openminis.app.agent.subagents.SubAgents.isEnabled()) {
            return ToolExecutionResult(com.openminis.app.agent.subagents.SubAgentTask.error(
                "subagents_disabled", "Sub agents are disabled in Settings.",
            ), false, toolTitle = title)
        }
        val actual = kotlin.coroutines.coroutineContext[ToolExecutor.ExecutionIdentity]
        val ownership = com.openminis.app.agent.subagents.SubAgentRuntime.ParentExecution(actual?.model?.runId,
            actual?.model?.taskId, actual?.model?.revision, actual?.model?.stopGeneration, actual?.intentId)
        val reply = try {
            kotlinx.coroutines.withContext(ownership) { com.openminis.app.agent.subagents.SubAgents.runtime(context).execute(argsJson, sessionId) }
        } catch (error: com.openminis.app.agent.subagents.SubAgentJobRegistry.StorageUnavailable) {
            val started = com.openminis.app.agent.subagents.SubAgents.runtime(context).registry.jobs.value.values.any {
                it.parentExecutionId == actual?.intentId && it.childSessionId != null
            }
            return ToolExecutionResult("Sub agent checkpoint could not be persisted; inspect its durable state before continuing.", false,
                toolTitle = title, failureKind = if (started) ToolFailureKind.OUTCOME_UNKNOWN else ToolFailureKind.CHECKPOINT_FAILURE,
                executionStarted = started)
        }
        return ToolExecutionResult(reply.text, reply.ok, toolTitle = title,
            failureKind = if (reply.outcomeUnknown) ToolFailureKind.OUTCOME_UNKNOWN else null)
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
        description = "Manage background jobs. Pass action=job_list (list all), job_kill (cancel by job_id), job_input (send text to a shell job's stdin by job_id; eof=true closes it), or job_output (read output by job_id; wait=true blocks until terminal status, up to timeout_ms).",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "Short summary of this call, shown to the user."),
            "action" to com.openminis.app.data.model.AgentToolParam("string", "job_list | job_kill | job_input | job_output."),
            "job_id" to com.openminis.app.data.model.AgentToolParam("string", "The id of the background job (job_kill/job_output)."),
            "wait" to com.openminis.app.data.model.AgentToolParam("boolean", "Block until the job reaches a terminal status (job_output, default false)."),
            "timeout_ms" to com.openminis.app.data.model.AgentToolParam("integer", "Maximum wait in milliseconds when wait=true (default 30000)."),
            "reason" to com.openminis.app.data.model.AgentToolParam("string", "Optional short reason for job_kill."),
            "input" to com.openminis.app.data.model.AgentToolParam("string", "Text to send as it is (job_input)."),
            "eof" to com.openminis.app.data.model.AgentToolParam("boolean", "Close the job's stdin after sending (job_input, default false)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "job_id", "wait", "timeout_ms", "reason", "input", "eof"),
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
