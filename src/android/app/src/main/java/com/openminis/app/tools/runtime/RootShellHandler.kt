package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import org.json.JSONObject

/** Structured Android Root execution governed by the user-owned access mode. */
class RootShellHandler : ToolHandler {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "root.shell",
        description = "Run one Android Root tool through minisd using structured tool and args. " +
            "Standard mode auto-runs read-only allowlisted requests and asks the user once for other exact requests. " +
            "Only the user can enable Full Access in App settings.",
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
        val tool = json.optString("tool").trim()
        if (tool.isEmpty()) return ToolExecutionResult("Error: tool is required", false)
        val args = json.optJSONArray("args")
        val argv = mutableListOf<String>()
        if (args != null) {
            for (index in 0 until args.length()) {
                val value = args.opt(index)
                if (value !is String || value.contains('\u0000')) {
                    return ToolExecutionResult("Error: args[$index] must be a non-null string", false)
                }
                argv += value
            }
        }
        val requestedMs = if (json.has("timeout_ms")) json.optLong("timeout_ms") else null
        val timeout = ToolTimeoutPolicy.resolve("root.shell", callerOverrideMs = requestedMs).timeoutMs
            ?: 30_000L
        val response = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId,
            argv = listOf(tool) + argv,
            operation = "执行 Android Root 命令",
            risk = CommandRisk.READ_ONLY,
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
        val output = listOf(response.stdout, response.stderr).filter { it.isNotBlank() }.joinToString("\n")
        return ToolExecutionResult(
            if (output.isBlank()) "(exit code: ${response.exitCode})" else output,
            response.exitCode == 0,
        )
    }
}
