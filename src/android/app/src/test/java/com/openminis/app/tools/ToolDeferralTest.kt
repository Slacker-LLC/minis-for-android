package com.openminis.app.tools

import com.openminis.app.agent.AgentConversation
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.remote.AgentPresetRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deferred tool loading: what a request carries, what can be loaded, and what can never be loaded.
 * Pure over a fake pool; the real-pool tests at the end guard the resident list against drifting from the
 * CORE preset and from the tools the shipped prompt names.
 */
class ToolDeferralTest {
    private fun def(name: String, description: String = "does $name") =
        AgentToolDefinition(name = name, description = description, parameters = emptyMap())

    private val mcp: (AgentToolDefinition) -> Boolean = { it.name.startsWith("mcp.") }
    private val canonical: (String) -> String? = { raw ->
        mapOf("android_weather" to "android.weather", "web_search" to "android.web.search")[raw]
    }

    private val pool = listOf(
        def("shell_execute"), def("find_tools"), def("browser_use"), def("android.web.search"),
        def("android_ui", "drive the phone screen"), def("android.weather", "forecast for a place"),
        def("android.calendar.read", "read calendar events"), def("terminal", "real TTY terminals"),
        def("mcp.docs.search", "remote docs search"),
    )

    private fun offered(loaded: Set<String> = emptySet(), enabled: Boolean = true, from: List<AgentToolDefinition> = pool) =
        ToolDeferral.offered(from, loaded, enabled, mcp).map { it.name }

    // ── What a request carries ──────────────────────────────────────────────

    @Test
    fun `deferred tools are withheld until loaded, resident and MCP tools always ship`() {
        assertEquals(
            listOf("shell_execute", "find_tools", "browser_use", "android.web.search", "mcp.docs.search"),
            offered(),
        )
    }

    @Test
    fun `a loaded tool is offered, matched by any spelling of its name`() {
        assertTrue("android.weather" in offered(setOf("android_weather")))
        assertTrue("terminal" in offered(setOf("Terminal")))
        assertFalse("android_ui" in offered(setOf("android_weather")))
    }

    @Test
    fun `the offered order does not depend on the order tools were loaded`() {
        assertEquals(offered(setOf("terminal", "android.weather")), offered(setOf("android.weather", "terminal")))
        // Resident tools stay one stable prefix; loaded tools follow in pool order.
        assertEquals(
            listOf("shell_execute", "find_tools", "browser_use", "android.web.search", "mcp.docs.search", "android.weather", "terminal"),
            offered(setOf("terminal", "android.weather")),
        )
    }

    @Test
    fun `with deferral off the whole pool ships`() {
        assertEquals(pool.map { it.name }, offered(enabled = false))
    }

    @Test
    fun `a pool with nothing deferred is returned as it is`() {
        val core = listOf(def("shell_execute"), def("find_tools"))
        assertEquals(core, ToolDeferral.offered(core, emptySet(), true, mcp))
        assertNull(ToolDeferral.indexFragment(ToolDeferral.split(core, mcp).deferred))
    }

    @Test
    fun `a name that is not in the pool is never offered even if listed as loaded`() {
        assertEquals(offered(), offered(setOf("subagent", "read_image", "mcp.disabled.tool")))
    }

    // ── What can be loaded ─────────────────────────────────────────────────

    @Test
    fun `findDeferred resolves aliases and wire names of deferred tools`() {
        assertEquals("android.weather", ToolDeferral.findDeferred(pool, "android_weather", canonical, mcp)?.name)
        assertEquals("android.weather", ToolDeferral.findDeferred(pool, "android.weather", canonical, mcp)?.name)
        assertEquals("terminal", ToolDeferral.findDeferred(pool, "TERMINAL", canonical, mcp)?.name)
    }

    @Test
    fun `findDeferred refuses resident tools, MCP tools and names the pool does not hold`() {
        for (name in listOf("shell_execute", "browser_use", "web_search", "mcp.docs.search", "subagent", "root.shell", "")) {
            assertNull("'$name' must not load", ToolDeferral.findDeferred(pool, name, canonical, mcp))
        }
    }

    @Test
    fun `a tool withheld from the pool cannot be loaded`() {
        // The pool is what AgentSession built for this session: a sub agent child's pool has no subagent,
        // a session with an MCP server switched off has none of its tools.
        val withheld = pool.filterNot { it.name == "terminal" }
        assertNull(ToolDeferral.findDeferred(withheld, "terminal", canonical, mcp))
        assertFalse("terminal" in offered(setOf("terminal"), from = withheld))
    }

    // ── find_tools search and selection ────────────────────────────────────

    private fun hits(query: String) = ToolDeferral.search(pool, query) { it.name }.map { it.tool.name to it.rank }

    @Test
    fun `a query of exact tool names selects those tools in any spelling and separator`() {
        assertEquals(listOf("android_ui" to 0, "terminal" to 0), hits("terminal, android_ui").sortedBy { it.first })
        assertEquals(listOf("terminal" to 0), hits("TERMINAL"))
    }

    @Test
    fun `a keyword query needs every word and ranks name hits before description hits`() {
        assertEquals(listOf("android.weather" to 1), hits("weather"))
        assertEquals(listOf("android.weather" to 1), hits("android weather"))
        assertEquals(listOf("android_ui" to 2), hits("phone screen"))
        assertTrue(hits("zzz").isEmpty())
    }

    @Test
    fun `an empty query lists the whole pool`() {
        assertEquals(pool.size, hits("").size)
    }

    private fun load(query: String): List<String> =
        ToolDeferral.selectToLoad(ToolDeferral.search(pool, query) { it.name }) { it.name in setOf("android_ui", "android.weather", "android.calendar.read", "terminal") }
            .map { it.name }

    @Test
    fun `selectToLoad loads the deferred tool a query names and nothing else`() {
        assertEquals(listOf("terminal"), load("terminal"))
        assertEquals(listOf("terminal", "android.weather").sorted(), load("terminal android.weather").sorted())
    }

    @Test
    fun `selectToLoad loads a small keyword match set`() {
        assertEquals(listOf("android.weather"), load("forecast"))
    }

    @Test
    fun `selectToLoad refuses to load for a broad keyword query`() {
        val big = (1..9).map { def("android.thing$it", "android helper") }
        val hits = ToolDeferral.search(big, "android") { it.name }
        assertEquals(9, hits.size)
        assertTrue(ToolDeferral.selectToLoad(hits) { true }.isEmpty())
    }

    @Test
    fun `selectToLoad never loads resident tools`() {
        val residentOnly = ToolDeferral.search(pool, "shell_execute") { it.name }
        assertEquals(1, residentOnly.size)
        assertTrue(ToolDeferral.selectToLoad(residentOnly) { false }.isEmpty())
    }

    // ── The prompt index ───────────────────────────────────────────────────

    @Test
    fun `the index names only deferred tools, sorted, and does not change when tools load`() {
        val deferred = ToolDeferral.split(pool, mcp).deferred
        val fragment = ToolDeferral.indexFragment(deferred)!!
        val names = fragment.lines().last()
        assertEquals("android_calendar_read, android_ui, android_weather, terminal", names)
        for (resident in listOf("shell_execute", "browser_use", "mcp_docs_search", "android_web_search")) {
            assertFalse("$resident must not be listed as deferred", names.contains(resident))
        }
        // Built from the deferred list alone, so loading cannot reach it.
        assertEquals(fragment, ToolDeferral.indexFragment(ToolDeferral.split(pool, mcp).deferred))
    }

    @Test
    fun `a refused call to an unloaded tool says it was not run`() {
        val message = ToolDeferral.loadedOnDemandMessage("terminal")
        assertTrue(message.startsWith("Error:"))
        assertTrue(message.contains("did not run"))
    }

    // ── History seeds the loaded set after a restart ───────────────────────

    @Test
    fun `tools the restored history called are remembered`() {
        val conversation = AgentConversation()
        assertTrue(conversation.calledToolNames.isEmpty())
        conversation.replaceHistory(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(AgentContentPart.ToolUse("1", "terminal", JSONObject())),
                ),
            ),
        )
        assertEquals(setOf("terminal"), conversation.calledToolNames)
        conversation.clear()
        assertTrue(conversation.calledToolNames.isEmpty())
    }

    // ── Real pool: drift guards ────────────────────────────────────────────

    private fun fullPool() = AgentTools.makeAgentTools(
        subAgentRosterNames = listOf("General Sub Agent"), subAgentModelHandles = listOf("a", "b"),
    )

    @Test
    fun `every CORE preset tool is resident`() {
        val core = AgentTools.makeAgentTools(presetToolset = AgentPresetRegistry.Toolset.CORE)
        assertTrue(core.isNotEmpty())
        val deferred = ToolDeferral.split(core, mcp).deferred
        assertTrue("CORE tools must never be deferred: ${deferred.map { it.name }}", deferred.isEmpty())
    }

    @Test
    fun `every tool the shipped tool list names is resident`() {
        val text = File("src/main/assets/prompts/tools.available.md").readText()
        val named = Regex("(?m)^- ([a-z_]+):").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("expected the tool bullets, got $named", named.containsAll(listOf("shell_execute", "browser_use")))
        val pool = fullPool()
        for (name in named) {
            val definition = pool.firstOrNull { it.matchesName(name) }
            assertNotNull("$name is named by tools.available.md but is not a pool tool", definition)
            assertTrue("$name is named by tools.available.md and must ship", ToolDeferral.isResident(definition!!, mcp))
        }
    }

    @Test
    fun `deferral shrinks the definitions a request carries`() {
        val all = fullPool()
        val sent = ToolDeferral.offered(all, emptySet(), true, mcp)
        fun bytes(list: List<AgentToolDefinition>) = list.sumOf { it.toOpenAIJson().toString().length }
        assertTrue("${bytes(sent)} of ${bytes(all)} bytes", bytes(sent) < bytes(all) * 0.7)
        val sentNames = sent.map { it.name }
        assertTrue("find_tools" in sentNames)
        assertTrue("subagent is a deferred tool", all.any { it.name == "subagent" } && "subagent" !in sentNames)
    }
}
