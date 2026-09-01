package io.github.slackerllc.minis.tools.runtime

/**
 * P3 Tool Runtime — permission model (D8).
 *
 * Four levels per (tool × caller):
 *   LOCAL_ONLY   local agents only; invisible to MCP
 *   MCP_ALLOWED  remote may call directly (within token scope)
 *   MCP_CONFIRM  remote needs human confirmation (120s auto-deny)
 *   MCP_DENIED   remote never sees/calls
 *
 * Unknown tool → deny (explicit mapping only, no prefix matching).
 *
 * Pure Kotlin, no Android imports, for JVM unit tests (T-K1/T-K2).
 */
object ToolPermissionManager {

    enum class Level(val wire: String) {
        LOCAL_ONLY("LOCAL_ONLY"),
        MCP_ALLOWED("MCP_ALLOWED"),
        MCP_CONFIRM("MCP_CONFIRM"),
        MCP_DENIED("MCP_DENIED"),
    }

    const val CALLER_LOCAL = "local_agent"

    data class ToolPolicy(
        val local: Level = Level.MCP_ALLOWED,
        val mcp: Level = Level.LOCAL_ONLY,
    )

    /**
     * Default policy table (P3 draft from 06 §3.2). Local column is mostly
     * ALLOWED; MCP column per tool. Every registered tool must appear here
     * or be denied explicitly; unknown → deny.
     */
    private val table: Map<String, ToolPolicy> = mapOf(
        // system.*
        "system.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "system.jobs.list" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "system.jobs.kill" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "system.memory.read" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "system.memory.write" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "system.permissions" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_DENIED),
        "system.settings" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        // linux.*
        "linux.shell" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.write" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.edit" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.image.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.append" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.copy" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.move" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.delete" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.file.list" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.search" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.grep" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.head_tail" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.file.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "linux.python.run" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "linux.pip.install" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        // android.web.* / calendar / contacts / location / clipboard / time / intent (P10)
        "android.web.search" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.web.fetch" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.calendar.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.calendar.create" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.calendar.update" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.calendar.delete" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.contacts.search" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.contacts.manage" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.location.get" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.clipboard" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.time" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.intent.send" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.wifi.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.wifi.scan" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.bluetooth.status" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.bluetooth.paired" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.bluetooth.scan" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.tts.voices" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.tts.voice" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.tts.enabled" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.media.images" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.media.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.media.control" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.weather" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.phone.dial" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.settings.get" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.settings.set" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.sms.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.call_log.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        // android.*
        "android.capabilities" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.app" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.ui" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.app.list" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.app.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.app.launch" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.app.force_stop" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.app.restart" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.app.usage" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.package.install" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "android.package.uninstall" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "android.screenshot" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "android.ui.observe" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.input.tap" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.input.swipe" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.input.text" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.input.back" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.input.home" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.logs" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.logs.read" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.logs.clear" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_DENIED),
        // whole-tool entries: registered as a single handler name, must also
        // be explicitly listed (wildcard below only matches deeper names)
        "android.diagnose" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.deploy" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "system.jobs" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.diagnose.*" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.deploy.*" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "android.root.probe" to ToolPolicy(Level.MCP_CONFIRM, Level.LOCAL_ONLY),
        "android.browser.*" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        // root.*
        "root.shell" to ToolPolicy(Level.LOCAL_ONLY, Level.LOCAL_ONLY),
        // agent.* / mcp.*
        "agent.goal" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.todo" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.subagent" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.ralph" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.ask" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "mcp.*" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        // skill.*
        "skill.*" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        // memory_* — not yet migrated to Registry; permission reserved for future migration.
        "memory_write" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "memory_get" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        // browser_* — not yet migrated to Registry; permission reserved for future migration.
        "browser_use" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.browser" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
    )

    /** Tools whose default level is LOCAL_ONLY, kept for MCP server filtering. */
    val localOnlyTools: Set<String> get() = table.filterValues { it.mcp == Level.LOCAL_ONLY }.keys

    /** Tools visible to MCP (ALLOWED or CONFIRM) — used by tools/list filtering. */
    fun mcpVisibleTools(): Set<String> = table.filterValues { it.mcp != Level.LOCAL_ONLY && it.mcp != Level.MCP_DENIED }.keys

    fun isRegistered(tool: String): Boolean = policyFor(tool) != null

    fun policyFor(tool: String): ToolPolicy? =
        table[tool] ?: wildcardPolicy(tool)

    /**
     * `*` suffix matching for grouped tools: `android.diagnose.*` matches
     * `android.diagnose.process`; `mcp.*` / `skill.*` match their prefix.
     * Explicit keys always win. Multi-level fallback: `mcp.files.read_file`
     * walks up (`mcp.files.*` → `mcp.*`) so nested remote-MCP tool names are
     * still governed instead of dying on "unknown".
     */
    private fun wildcardPolicy(tool: String): ToolPolicy? {
        var idx = tool.lastIndexOf('.')
        while (idx > 0) {
            val group = tool.substring(0, idx + 1)
            table["${group}*"]?.let { return it }
            idx = tool.lastIndexOf('.', idx - 1)
        }
        return null
    }

    /**
     * Effective level for [tool] as seen by [caller] ("local_agent" or
     * "mcp:<token_id>"). Unknown tool → MCP_DENIED-equivalent refusal.
     */
    fun levelFor(tool: String, caller: String): Level {
        val policy = policyFor(tool) ?: return Level.MCP_DENIED
        return when {
            caller == CALLER_LOCAL -> policy.local
            caller.startsWith("mcp:") -> policy.mcp
            else -> Level.MCP_DENIED
        }
    }

    fun isAllowedFor(tool: String, caller: String): Boolean {
        val level = levelFor(tool, caller)
        return level == Level.MCP_ALLOWED || level == Level.MCP_CONFIRM ||
            caller == CALLER_LOCAL && level == Level.LOCAL_ONLY
    }

    /**
     * True only for MCP_ALLOWED (no human gate). MCP_CONFIRM is excluded:
     * a confirm-level call still needs the human approval gate, so it is
     * "allowed to start" but not "directly allowed to execute".
     */
    fun isDirectlyAllowed(tool: String, caller: String): Boolean =
        levelFor(tool, caller) == Level.MCP_ALLOWED

    fun needsConfirm(tool: String, caller: String): Boolean =
        levelFor(tool, caller) == Level.MCP_CONFIRM

    /**
     * Token scope gate: a token bound to [allowedSubset] (empty = all visible)
     * with [maxLevel] ceiling can call [tool] only if it's registered, not
     * LOCAL_ONLY/DENIED, within the subset, and level < = ceiling order.
     */
    fun tokenCanCall(
        tool: String,
        caller: String,
        allowedSubset: Set<String>,
        maxLevel: Level,
    ): Boolean {
        val level = levelFor(tool, caller)
        if (level == Level.MCP_DENIED || level == Level.LOCAL_ONLY) return false
        if (allowedSubset.isNotEmpty() && tool !in allowedSubset) return false
        return levelRank(level) <= levelRank(maxLevel)
    }

    private fun levelRank(l: Level): Int = when (l) {
        Level.MCP_ALLOWED -> 0
        Level.MCP_CONFIRM -> 1
        Level.LOCAL_ONLY -> 2
        Level.MCP_DENIED -> 3
    }
}
