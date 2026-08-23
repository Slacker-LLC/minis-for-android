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
 * When the runtime is unavailable, execution short-circuits to a fixed
 * structured error (`ubuntu_runtime_unavailable`) instead of reaching the
 * handler — the agent sees a recoverable failure, not a crashed loop.
 * When available, execution passes straight through to [next].
 */
class LinuxProvider(
    private val available: () -> Boolean,
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
    ): ToolExecutionResult =
        if (available()) next()
        else ToolExecutionResult(output = "Error: ubuntu_runtime_unavailable: $toolName", success = false)
}
