package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * Deferred tool loading. A session's full tool set (the "pool") is about 100 definitions / 100 KB;
 * small models lose the will to call tools under that much schema. The pool is split in two:
 *
 *  - resident tools ship with every request, exactly as before;
 *  - deferred tools are only *named* (see [indexFragment]) until the model loads them with `find_tools`
 *    or by calling one, which fails once and loads it. A loaded tool is offered from the next request on.
 *
 * The pool is the gate. [offered] can only ever return a subset of the pool, and [findDeferred] can only
 * load what the pool holds, so everything the session withholds (chat-only turns, a sub agent child's
 * `subagent`, MCP servers switched off for the session, `read_image` without vision) can neither be offered
 * nor loaded. Loading only changes what is *sent*; permissions, approval and argument validation run at
 * execution as before.
 *
 * Prompt caching: tool definitions sit at the front of the request, so any change to them invalidates the
 * cached prefix once. To make that happen only on the first load of a tool, [offered] is deterministic:
 * resident tools first in pool order, then loaded tools in pool order, never dependent on load order, and a
 * loaded tool stays loaded for the session.
 */
object ToolDeferral {
    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Tools that always ship. These are the `CORE` preset (persistent shell, file triad, goal / plan /
     * question / job controls, the catalog) plus what the base system prompt names and asks the model to use
     * unprompted: `browser_use` and the memory pair, and web search / fetch (small, and the first thing a
     * question about the world needs). The goal tools are named by the task context the runtime injects.
     * Tools a prompt module introduces for a narrower job (sub agents, terminals, balance, phone UI control)
     * are deferred and listed in the index instead.
     */
    val RESIDENT_NAMES: List<String> = listOf(
        "shell_execute", "file_read", "file_write", "file_edit",
        "ask_user_question",
        "get_goal", "create_goal", "update_goal", "verify_outcome", "review_restored_task",
        "todo_write",
        "job_output", "job_list", "job_kill", "job_input",
        "find_tools",
        "browser_use", "memory_write", "memory_get",
        "android.web.search", "android.web.fetch",
    )
    private val residentKeys = RESIDENT_NAMES.map(::key).toSet()

    /**
     * Whether a request to this model defers tools. The user's choice for the model wins (`true` on, `false`
     * off). Left on auto (`null`), the global switch decides, and a model-rules entry can opt a model family
     * out: [familyDefault] `false` means off, `true` or absent (no rule, or one that failed to parse) means on.
     * A global switch that is off turns every auto model off, whatever its rule says.
     */
    fun enabledFor(modelChoice: Boolean?, familyDefault: Boolean?, global: Boolean): Boolean =
        modelChoice ?: (global && (familyDefault ?: true))

    /** How many deferred tools one `find_tools` call may load. */
    const val MAX_LOAD_PER_CALL = 6

    /** Remote MCP tools are the user's own opt-in and the prompt tells the model to call them directly. */
    private val mcpTool: (AgentToolDefinition) -> Boolean = { def ->
        com.openminis.app.tools.runtime.ToolRegistry.handler(def.name)?.isMcpTool == true
    }

    class Split(val resident: List<AgentToolDefinition>, val deferred: List<AgentToolDefinition>)

    fun isResident(def: AgentToolDefinition, pinned: (AgentToolDefinition) -> Boolean = mcpTool): Boolean =
        key(def.name) in residentKeys || key(def.apiName) in residentKeys || pinned(def)

    fun split(pool: List<AgentToolDefinition>, pinned: (AgentToolDefinition) -> Boolean = mcpTool): Split {
        val (resident, deferred) = pool.partition { isResident(it, pinned) }
        return Split(resident, deferred)
    }

    /**
     * What a request carries: the whole [pool] when deferral is off, otherwise the resident tools followed by
     * the deferred tools named in [loadedNames] (call names or canonical names; any spelling
     * [AgentToolDefinition.matchesName] accepts).
     */
    fun offered(
        pool: List<AgentToolDefinition>,
        loadedNames: Set<String>,
        enabled: Boolean,
        pinned: (AgentToolDefinition) -> Boolean = mcpTool,
    ): List<AgentToolDefinition> {
        if (!enabled) return pool
        val parts = split(pool, pinned)
        if (parts.deferred.isEmpty()) return pool
        if (loadedNames.isEmpty()) return parts.resident
        return parts.resident + parts.deferred.filter { def -> loadedNames.any(def::matchesName) }
    }

    /**
     * The deferred pool tool a call or query names, or null when it names a resident tool or nothing the
     * pool holds. [canonicalize] maps an alias to the registry's canonical name (a call may use either).
     */
    fun findDeferred(
        pool: List<AgentToolDefinition>,
        name: String,
        canonicalize: (String) -> String? = { com.openminis.app.tools.runtime.ToolRegistry.canonicalName(it) },
        pinned: (AgentToolDefinition) -> Boolean = mcpTool,
    ): AgentToolDefinition? {
        val deferred = split(pool, pinned).deferred
        deferred.firstOrNull { it.matchesName(name) }?.let { return it }
        val canonical = canonicalize(name) ?: return null
        return deferred.firstOrNull { it.matchesName(canonical) }
    }

    /** A `find_tools` match. [rank]: 0 the query names exactly this tool, 1 its name holds every word, 2 only its description does. */
    class Hit(val tool: AgentToolDefinition, val rank: Int)

    /**
     * The pool tools a `find_tools` [query] matches, best first. A query made only of exact tool names (words
     * and commas) selects those tools; anything else is a keyword search where every word must appear in a
     * tool's names or description. An empty query lists the whole pool.
     */
    fun search(
        pool: List<AgentToolDefinition>,
        query: String,
        canonicalOf: (AgentToolDefinition) -> String? = { com.openminis.app.tools.runtime.ToolRegistry.canonicalName(it.name) },
    ): List<Hit> {
        val words = query.lowercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val tools = pool.distinctBy { it.apiName }
        fun names(def: AgentToolDefinition) = listOfNotNull(def.name, def.apiName, canonicalOf(def)).map { it.lowercase() }
        val hits = if (words.isEmpty()) {
            tools.map { Hit(it, 1) }
        } else {
            val exact = words.map { word -> tools.firstOrNull { word in names(it) } }
            if (exact.all { it != null }) {
                exact.filterNotNull().distinct().map { Hit(it, 0) }
            } else {
                tools.mapNotNull { def ->
                    val nameText = names(def).joinToString(" ")
                    val fullText = nameText + " " + def.description.lowercase()
                    when {
                        words.all(nameText::contains) -> Hit(def, 1)
                        words.all(fullText::contains) -> Hit(def, 2)
                        else -> null
                    }
                }
            }
        }
        return hits.sortedWith(compareBy({ it.rank }, { it.tool.apiName }))
    }

    /**
     * Which of the [hits] a query loads: the deferred tools it names exactly, or every deferred match when
     * there are at most [MAX_LOAD_PER_CALL]. A broad keyword query loads nothing, so one word like "android"
     * cannot drag half the pool in; the model narrows it with a name from the index.
     */
    fun selectToLoad(hits: List<Hit>, isDeferred: (AgentToolDefinition) -> Boolean): List<AgentToolDefinition> {
        val deferred = hits.filter { isDeferred(it.tool) }
        val exact = deferred.filter { it.rank == 0 }
        return when {
            exact.isNotEmpty() -> exact.take(MAX_LOAD_PER_CALL).map { it.tool }
            deferred.size <= MAX_LOAD_PER_CALL -> deferred.map { it.tool }
            else -> emptyList()
        }
    }

    /**
     * The system prompt's list of deferred tools, or null when nothing is deferred. Names only, sorted, and
     * independent of what is loaded, so it never changes the prompt prefix after a load.
     */
    fun indexFragment(deferred: List<AgentToolDefinition>): String? {
        if (deferred.isEmpty()) return null
        val names = deferred.map { it.apiName }.distinct().sorted()
        return buildString {
            append("Deferred tools: these exist but their definitions are not sent with every request, ")
            append("so they cannot be called until loaded. Call find_tools with the tool name (or a few ")
            append("keywords) to load one; it is callable from your next step. Calling an unloaded tool directly ")
            append("does not run it, it only loads it, so then call it again.\n")
            append(names.joinToString(", "))
        }
    }

    /** The index for one request: none when deferral is off for it, since then every tool ships. */
    fun indexFor(
        pool: List<AgentToolDefinition>,
        enabled: Boolean,
        pinned: (AgentToolDefinition) -> Boolean = mcpTool,
    ): String? = if (enabled) indexFragment(split(pool, pinned).deferred) else null

    /** What a refused call to a not-yet-loaded tool tells the model. */
    fun loadedOnDemandMessage(name: String): String =
        "Error: Tool '$name' is deferred and was not loaded, so this call did not run. It is loaded now and " +
            "available from your next step: call it again."
}
