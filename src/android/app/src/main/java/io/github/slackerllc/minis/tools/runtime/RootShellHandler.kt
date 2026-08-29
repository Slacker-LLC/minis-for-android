package io.github.slackerllc.minis.tools.runtime

import android.content.Context
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.sandbox.ubuntu.UbuntuRuntime
import io.github.slackerllc.minis.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * Compatibility name for structured Android-root execution.
 *
 * This deliberately has no `command` / shell-string parameter. Every call
 * travels through minisd `root.exec`, whose policy owns the executable
 * allowlist and argument deny rules; `root.shellRaw` remains denied.
 */
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
        val timeout = json.optLong("timeout_ms", 30_000).coerceIn(1_000, 120_000)
        if (!UbuntuRuntime.isInitialized) UbuntuRuntime.init(context)
        val response = UbuntuRuntime.client.rootExec(tool, argv, timeout)
        if (!response.ok) {
            val error = response.error
            return ToolExecutionResult(
                "Error: ${error?.code ?: "RUNTIME_UNAVAILABLE"}: ${error?.detail ?: "minisd root.exec failed"}",
                false,
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
