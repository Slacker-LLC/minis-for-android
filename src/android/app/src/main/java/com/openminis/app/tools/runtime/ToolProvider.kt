package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.tools.ToolExecutionResult

/**
 * P4 Provider layer (06 §1). A [ToolProvider] claims one or more tool-name
 * prefixes and may wrap handler execution with provider-specific semantics
 * (e.g. the LinuxProvider `ubuntu_runtime_unavailable` gate). Default is
 * pass-through.
 *
 * Providers must stay pure logic: no imports of runtime singletons
 * (UbuntuRuntime / MinisdClient). Availability checks are constructor-injected
 * so JVM unit tests stay green.
 */
interface ToolProvider {

    /** Stable id, e.g. "linux". */
    val id: String

    /** Tool-name prefixes this provider claims, e.g. "linux.". */
    val prefixes: List<String>

    /** True if this provider owns [toolName] (prefix match). */
    fun handles(toolName: String): Boolean = prefixes.any { toolName.startsWith(it) }

    /**
     * Wrap [next] (the registered handler execution) with provider semantics.
     * Default: pass-through.
     */
    suspend fun execute(
        toolName: String,
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
        next: suspend () -> ToolExecutionResult,
    ): ToolExecutionResult = next()
}

/**
 * Routes a tool name to the provider claiming its prefix (06 §1).
 * No provider claims it → null → ToolExecutor keeps its default handler path
 * (backward compatible with the pre-router behaviour).
 */
object ProviderRouter {

    private val providers = linkedMapOf<String, ToolProvider>()

    fun register(provider: ToolProvider) {
        providers[provider.id] = provider
    }

    fun route(toolName: String): ToolProvider? =
        providers.values.firstOrNull { it.handles(toolName) }

    /** Test / re-init support (also called by MinisApp before re-registering). */
    fun reset() = providers.clear()
}

/**
 * Pass-through provider for prefixes without extra semantics yet
 * (06 §2 D12: android.* / root.* / system.*+agent.* / mcp.* / skill.*).
 * Real per-prefix gates land as their providers get built (P5+).
 */
class PrefixProvider(
    override val id: String,
    override val prefixes: List<String>,
) : ToolProvider
