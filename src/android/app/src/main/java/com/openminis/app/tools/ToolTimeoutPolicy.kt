package com.openminis.app.tools

/**
 * Layered tool budgets. This intentionally has no global timeout: a local
 * process, a root broker call, and a remote MCP request have different costs
 * and different safe upper bounds.
 */
object ToolTimeoutPolicy {
    enum class Category {
        PROCESS,
        ROOT_PROCESS,
        NETWORK_MCP,
        DECLARED,
        UNBOUNDED_INTERACTIVE,
    }

    data class Budget(
        val category: Category,
        val defaultMs: Long?,
        val maxMs: Long?,
        val callerOverrideAllowed: Boolean,
    )

    data class Resolution(
        val category: Category,
        val timeoutMs: Long?,
        val callerOverrideApplied: Boolean,
    )

    private const val SECOND = 1_000L

    fun budgetFor(name: String, declaredTimeoutMs: Long? = null): Budget = when {
        name == "shell_execute" || name == "linux.shell" ->
            Budget(Category.PROCESS, 900 * SECOND, 900 * SECOND, true)
        name == "linux.python.run" ->
            Budget(Category.PROCESS, 300 * SECOND, 900 * SECOND, true)
        name == "root.shell" ->
            Budget(Category.ROOT_PROCESS, 30 * SECOND, 120 * SECOND, true)
        name.startsWith("mcp.") ->
            Budget(Category.NETWORK_MCP, 60 * SECOND, 300 * SECOND, true)
        declaredTimeoutMs != null ->
            Budget(Category.DECLARED, declaredTimeoutMs, declaredTimeoutMs, false)
        else -> Budget(Category.UNBOUNDED_INTERACTIVE, null, null, false)
    }

    /**
     * Resolve an optional caller-requested budget. Overrides are accepted only
     * for categories that explicitly opt in, never below one second, and never
     * above the category maximum. A malformed/non-positive override falls back
     * to the category default instead of disabling the deadline.
     */
    fun resolve(
        name: String,
        declaredTimeoutMs: Long? = null,
        callerOverrideMs: Long? = null,
    ): Resolution {
        val budget = budgetFor(name, declaredTimeoutMs)
        val requested = callerOverrideMs?.takeIf { it > 0 }
        val applied = budget.callerOverrideAllowed && requested != null
        val effective = when {
            budget.defaultMs == null -> null
            applied -> requested!!.coerceIn(SECOND, budget.maxMs ?: requested)
            else -> budget.defaultMs
        }
        return Resolution(budget.category, effective, applied)
    }
}
