package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.tools.ToolExecutionResult

/**
 * P4 provider for the `linux.` prefix (06 §4 存活语义 — survival semantics).
 *
 * The Ubuntu runtime can die or be unavailable while the agent loop is
 * alive. This provider must therefore never hold a reference to any runtime
 * singleton (UbuntuRuntime / MinisdClient): availability is constructor-
 * injected ([available]), so the provider stays pure JVM-testable logic.
 *
 * When the runtime is unavailable, shell/Python tools short-circuit to a
 * fixed structured error (`ubuntu_runtime_unavailable`). Workspace file tools
 * are different: they use App-owned host storage directly and remain usable
 * even before Ubuntu starts. When available, all tools pass through to [next].
 */
class LinuxProvider(
    private val available: () -> Boolean,
    /** Optional on-demand recovery: called once when [available] is false
     *  (e.g. UbuntuRuntime.ensureReady — cheap when minisd is already up). */
    private val revive: (suspend () -> Boolean)? = null,
) : ToolProvider {

    override val id: String = "linux"
    override val prefixes: List<String> = listOf("linux.")

    override suspend fun execute(
        toolName: String,
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
        next: suspend () -> ToolExecutionResult,
    ): ToolExecutionResult {
        val usable = available() || (revive?.invoke() ?: false)
        return if (toolName.startsWith("linux.file.") || usable) next()
        else ToolExecutionResult(output = "Error: ubuntu_runtime_unavailable: $toolName", success = false)
    }
}
