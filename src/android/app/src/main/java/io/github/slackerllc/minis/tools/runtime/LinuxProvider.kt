package io.github.slackerllc.minis.tools.runtime

import android.content.Context
import io.github.slackerllc.minis.tools.ToolExecutionResult

/**
 * P4 provider for the `linux.` prefix (06 §4 survival semantics).
 *
 * The Ubuntu runtime can die or be unavailable while the agent loop is
 * alive. This provider must therefore never hold a reference to any runtime
 * singleton (UbuntuRuntime / MinisdClient): availability is constructor-
 * injected ([available]), so the provider stays pure JVM-testable logic.
 *
 * Workspace file tools use App-owned host storage directly and remain usable
 * even before Ubuntu starts. Runtime-backed tools get one on-demand recovery
 * attempt through [revive]. If that recovery still fails, execution is handed
 * to the downstream handler so it can surface the concrete bootstrap error
 * (missing minisd, missing rootfs, auth mismatch, keeper failure, and so on)
 * instead of replacing it with a generic availability error. A provider that
 * has no recovery hook retains the fixed `ubuntu_runtime_unavailable` fallback.
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
        if (toolName.startsWith("linux.file.") || available()) {
            return next()
        }

        val recovery = revive
        if (recovery != null) {
            recovery.invoke()
            // The concrete handler performs its own readiness check. Let it
            // report the actual failure instead of hiding that detail here.
            return next()
        }

        return ToolExecutionResult(
            output = "Error: ubuntu_runtime_unavailable: $toolName",
            success = false,
        )
    }
}
