package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.minisd.MinisdClient
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolTimeoutPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Structured Android-root execution; arbitrary root shell strings stay denied. */
class RootShellHandler : ToolHandler {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "root.shell",
        description = "Run one allowlisted Android root tool through minisd root.exec. " +
            "Use structured args only; arbitrary shell commands are not supported.",
        parameters = mapOf(
            "tool" to AgentToolParam("string", "Allowlisted executable", listOf("pm", "am", "settings", "dumpsys", "getprop", "mount")),
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
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val executionId = MinisdClient.newExecutionId("root")
        val response = try {
            UbuntuRuntime.client.rootExec(tool, argv, timeout, executionId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { UbuntuRuntime.client.cancelExecution(executionId) }
            }
            throw cancelled
        }
        if (!response.ok) {
            val code = response.error?.code ?: "RUNTIME_UNAVAILABLE"
            if (code == "USER_CANCELLATION") {
                throw CancellationException(response.error?.detail ?: "root execution cancelled by user")
            }
            val kind = when (code) {
                "TOOL_TIMEOUT", "TIMEOUT" -> ToolFailureKind.TOOL_TIMEOUT
                "TRANSPORT_TIMEOUT" -> ToolFailureKind.TRANSPORT_TIMEOUT
                "PROCESS_KILLED" -> ToolFailureKind.PROCESS_KILLED
                "CLEANUP_FAILURE" -> ToolFailureKind.CLEANUP_FAILURE
                else -> null
            }
            return ToolExecutionResult(
                output = "Error: $code: ${response.error?.detail ?: "minisd root.exec failed"}",
                success = false,
                timedOut = kind == ToolFailureKind.TOOL_TIMEOUT,
                failureKind = kind,
            )
        }
        val result = response.result ?: return ToolExecutionResult("Error: malformed minisd root.exec result", false)
        val stdout = result.optString("stdout")
        val stderr = result.optString("stderr")
        val exitCode = result.optInt("exit_code", 1)
        val output = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
        return ToolExecutionResult(if (output.isBlank()) "(exit code: $exitCode)" else output, exitCode == 0)
    }
}
