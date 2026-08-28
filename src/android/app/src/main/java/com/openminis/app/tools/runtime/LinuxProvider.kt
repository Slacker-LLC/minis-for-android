package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.sandbox.ubuntu.UbuntuRuntimeDiagnostics
import com.openminis.app.tools.ToolExecutionResult

/**
 * P4 provider for the `linux.` prefix (06 §4 存活语义 — survival semantics).
 *
 * The Ubuntu runtime can die or be unavailable while the agent loop is
 * alive. This provider must therefore never hold a reference to any runtime
 * singleton (UbuntuRuntime / MinisdClient): availability is constructor-
 * injected ([available]), so the provider stays pure JVM-testable logic.
 * Startup diagnostics are read only from [UbuntuRuntimeDiagnostics], a pure
 * state bridge published by the runtime after a recovery attempt.
 *
 * When the runtime is unavailable, shell/Python tools short-circuit to a
 * structured `ubuntu_runtime_unavailable` error that includes the concrete
 * root/minisd failure when recovery produced one. Workspace file tools are
 * different: they use App-owned host storage directly and remain usable even
 * before Ubuntu starts. When available, all tools pass through to [next].
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
        val initiallyAvailable = available()
        val recovery = if (initiallyAvailable) null else revive
        val usable = initiallyAvailable || (recovery?.invoke() ?: false)
        return if (toolName.startsWith("linux.file.") || usable) {
            next()
        } else {
            val detail = if (recovery != null) UbuntuRuntimeDiagnostics.lastError else null
            val suffix = detail?.let { ": $it" }.orEmpty()
            ToolExecutionResult(
                output = "Error: ubuntu_runtime_unavailable: $toolName$suffix",
                success = false,
            )
        }
    }
}
