package com.openminis.app.tools.runtime

import com.openminis.app.offload.OffloadPermissionManager
import kotlinx.coroutines.runBlocking

/**
 * P3 Tool Runtime — caller exposure model for the fork runtime.
 *
 * This table remains an internal local-vs-MCP routing boundary. It is not a
 * second user-facing Agent permission model. For local Agent calls that map to
 * an upstream OpenMinis permission item, [isAllowedFor] delegates to the
 * canonical [OffloadPermissionManager] tri-state gate first.
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

    private val table: Map<String, ToolPolicy> = mapOf(
        "system.info" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "system.jobs.list" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_ALLOWED),
        "system.jobs.kill" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "system.memory.read" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "system.memory.write" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "system.permissions" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_DENIED),
        "system.settings" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
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
        "android.diagnose" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.deploy" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "system.jobs" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.diagnose.*" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.deploy.*" to ToolPolicy(Level.MCP_CONFIRM, Level.MCP_CONFIRM),
        "android.root.probe" to ToolPolicy(Level.MCP_CONFIRM, Level.LOCAL_ONLY),
        "android.browser.*" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        // Upstream-compatible structured Root entry: available to the local
        // Agent, never exposed to remote MCP callers.
        "root.shell" to ToolPolicy(Level.LOCAL_ONLY, Level.LOCAL_ONLY),
        "agent.goal" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.todo" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.subagent" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.ralph" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "agent.ask" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "mcp.*" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "delegate_bot" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "list_bots" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "check_delegation" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "skill.*" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "memory_write" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "memory_get" to ToolPolicy(Level.MCP_ALLOWED, Level.LOCAL_ONLY),
        "browser_use" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
        "android.browser" to ToolPolicy(Level.MCP_ALLOWED, Level.MCP_CONFIRM),
    )

    private data class UpstreamAgentPermission(
        val toolName: String,
        val displayName: String,
    )

    /**
     * Adapter from fork-only structured tool names to the exact upstream
     * OpenMinis permission entries. Unmapped fork tools keep their existing
     * execution semantics; the structured local-only Root entry is handled by
     * RootShellHandler and is not a remote MCP capability.
     */
    private fun upstreamAgentPermissionFor(tool: String): UpstreamAgentPermission? = when {
        tool.startsWith("android.calendar.") -> UpstreamAgentPermission("calendar", "Calendar")
        tool == "android.location.get" -> UpstreamAgentPermission("location", "Location")
        tool == "android.clipboard" -> UpstreamAgentPermission("clipboard", "Clipboard")
        tool.startsWith("android.contacts.") -> UpstreamAgentPermission("contacts", "Contacts")
        tool == "android.media.images" -> UpstreamAgentPermission("photos", "Photos")
        tool == "android.ui" || tool == "android.ui.observe" || tool.startsWith("android.input.") ->
            UpstreamAgentPermission("a11y_cli", "android-a11y-cli")
        else -> null
    }

    val localOnlyTools: Set<String> get() = table.filterValues { it.mcp == Level.LOCAL_ONLY }.keys

    fun mcpVisibleTools(): Set<String> =
        table.filterValues { it.mcp != Level.LOCAL_ONLY && it.mcp != Level.MCP_DENIED }.keys

    fun isRegistered(tool: String): Boolean = policyFor(tool) != null

    fun policyFor(tool: String): ToolPolicy? = table[tool] ?: wildcardPolicy(tool)

    private fun wildcardPolicy(tool: String): ToolPolicy? {
        var idx = tool.lastIndexOf('.')
        while (idx > 0) {
            val group = tool.substring(0, idx + 1)
            table["${group}*"]?.let { return it }
            idx = tool.lastIndexOf('.', idx - 1)
        }
        return null
    }

    fun levelFor(tool: String, caller: String): Level {
        val policy = policyFor(tool) ?: return Level.MCP_DENIED
        return when {
            caller == CALLER_LOCAL -> policy.local
            caller.startsWith("mcp:") -> policy.mcp
            else -> Level.MCP_DENIED
        }
    }

    fun isAllowedFor(tool: String, caller: String): Boolean {
        if (caller == CALLER_LOCAL) {
            upstreamAgentPermissionFor(tool)?.let { upstream ->
                val allowed = runBlocking {
                    OffloadPermissionManager.checkPermission(
                        upstream.toolName,
                        upstream.displayName,
                        OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
                    )
                }
                if (!allowed) return false
            }
        }
        val level = levelFor(tool, caller)
        return level == Level.MCP_ALLOWED || level == Level.MCP_CONFIRM ||
            caller == CALLER_LOCAL && level == Level.LOCAL_ONLY
    }

    fun isDirectlyAllowed(tool: String, caller: String): Boolean =
        levelFor(tool, caller) == Level.MCP_ALLOWED

    fun needsConfirm(tool: String, caller: String): Boolean =
        levelFor(tool, caller) == Level.MCP_CONFIRM

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
