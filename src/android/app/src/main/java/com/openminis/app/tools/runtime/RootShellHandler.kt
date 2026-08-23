package com.openminis.app.tools.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.DangerousCommandPolicy
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.android.RootCommandRunner
import org.json.JSONObject

/**
 * `root.shell` — the second shell (architecture doc §9): runs a command in the
 * Android Root domain via KernelSU, NOT in Ubuntu. LOCAL_ONLY by policy
 * (ToolPermissionManager) so remote MCP clients can never reach it; the local
 * Agent gets it through the same ToolRuntime gate as every other tool.
 *
 * Dangerous-command policy applies here too — root is a superset of the Ubuntu
 * shell, so the same destructive patterns (rm -rf /, mkfs, dd of=/dev, …) are
 * refused before execution.
 */
class RootShellHandler : ToolHandler {

    companion object {
        private const val TAG = "RootShellHandler"
    }

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "root.shell",
        description = "Run a shell command in the Android Root domain (KernelSU). " +
            "Use for Android system commands (cmd, pm, am, settings, dumpsys, getprop, mount…). " +
            "This is NOT the Ubuntu shell — use linux.shell for Python/git/apt work. " +
            "Destructive commands are refused by policy.",
        parameters = mapOf(
            "command" to AgentToolParam("string", "Shell command to run as root (Android domain)"),
            "timeout_ms" to AgentToolParam("integer", "Timeout in ms (default 30000, max 120000)"),
        ),
        required = listOf("command"),
    )

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Invalid root.shell arguments", false)
        val command = args.optString("command").trim()
        if (command.isEmpty()) {
            return ToolExecutionResult("Empty command", false)
        }
        val danger = DangerousCommandPolicy.dangerousReason(command)
        if (danger != null) {
            Log.w(TAG, "[$sessionId] blocked dangerous root command: $danger")
            return ToolExecutionResult("blocked: $danger", false)
        }
        val timeout = args.optLong("timeout_ms", 30_000).coerceIn(1_000, 120_000)
        val result = RootCommandRunner.run(listOf("sh", "-c", command), timeout)
        val output = buildString {
            if (result.stdout.isNotEmpty()) append(result.stdout)
            if (result.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(result.stderr)
            }
            if (result.unavailableReason != null) {
                if (isNotEmpty()) append('\n')
                append("unavailable: ").append(result.unavailableReason)
            }
            if (result.timedOut) {
                if (isNotEmpty()) append('\n')
                append("(timed out)")
            }
        }
        return ToolExecutionResult(
            if (output.isEmpty()) "(exit code: ${result.exitCode})" else output,
            result.success && !result.timedOut,
        )
    }
}
