package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import com.openminis.app.tools.android.PrivilegedCommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * Structured Android Root execution for the local Agent.
 *
 * This deliberately keeps the upstream tool/args contract: the Agent chooses
 * one executable name and an argv array, never a raw shell command. The
 * privileged runner resolves the executable from trusted Android system
 * directories and keeps the process bounded and cancellable. MCP exposure
 * remains disabled by [ToolPermissionManager].
 */
class RootShellHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = "root.shell",
        description = "Run one Android Root tool with structured tool and args. " +
            "The executable is resolved only from trusted Android system directories; " +
            "arguments are passed without shell parsing. This tool is local-only and is not exposed to MCP.",
        parameters = mapOf(
            "tool" to AgentToolParam("string", "Executable name resolved only from trusted Android system directories"),
            "args" to AgentToolParam("array", "Arguments passed without shell parsing", items = AgentToolParam("string", "One argument")),
            "timeout_ms" to AgentToolParam("integer", "Timeout in ms (default 30000, max 120000)"),
        ),
        required = listOf("tool"),
    )

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val json = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: invalid root.shell arguments", false)
        val toolValue = json.opt("tool")
        val tool = (toolValue as? String)?.trim().orEmpty()
        if (tool.isEmpty()) return ToolExecutionResult("Error: tool is required", false)

        if (json.has("args") && json.optJSONArray("args") == null) {
            return ToolExecutionResult("Error: args must be an array of strings", false)
        }
        val argsJsonArray = json.optJSONArray("args")
        val args = buildList {
            if (argsJsonArray != null) {
                for (index in 0 until argsJsonArray.length()) {
                    val value = argsJsonArray.opt(index)
                    if (value !is String || value.contains('\u0000')) {
                        return ToolExecutionResult("Error: args[$index] must be a non-null string", false)
                    }
                    add(value)
                }
            }
        }
        val requestedMs = if (json.has("timeout_ms")) json.optLong("timeout_ms") else null
        val timeout = ToolTimeoutPolicy.resolve("root.shell", callerOverrideMs = requestedMs).timeoutMs
            ?: 30_000L
        val response = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId,
            argv = listOf(tool) + args,
            operation = "执行 Android Root 命令",
            risk = PrivilegedCommandRisk.classify(tool, args),
            timeoutMs = timeout,
            rootOnly = true,
        )
        if (!response.success) {
            val detail = response.unavailableReason ?: response.stderr.ifBlank { "Root execution failed" }
            val code = detail.substringBefore(':')
            val kind = when {
                response.timedOut -> ToolFailureKind.TOOL_TIMEOUT
                else -> when (code) {
                    "TOOL_TIMEOUT", "TIMEOUT" -> ToolFailureKind.TOOL_TIMEOUT
                    "TRANSPORT_TIMEOUT" -> ToolFailureKind.TRANSPORT_TIMEOUT
                    "PROCESS_KILLED" -> ToolFailureKind.PROCESS_KILLED
                    "CLEANUP_FAILURE" -> ToolFailureKind.CLEANUP_FAILURE
                    else -> null
                }
            }
            return ToolExecutionResult(
                output = "Error: $detail",
                success = false,
                timedOut = kind == ToolFailureKind.TOOL_TIMEOUT,
                failureKind = kind,
            )
        }
        val output = listOf(response.stdout, response.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return ToolExecutionResult(
            output = if (output.isBlank()) "(exit code: ${response.exitCode})" else output,
            success = response.exitCode == 0,
        )
    }
}
