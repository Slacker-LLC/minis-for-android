package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-wire-name-android] The wire name a provider sees: legal for
 * `^[a-zA-Z0-9_-]{1,64}$` (dots in `mcp.<server>.<tool>` are rejected by
 * OpenAI-compatible endpoints with `400 Invalid 'tools[N].name'`), and — the part a
 * plain cut cannot guarantee — distinct for two different tools whose names only differ
 * after the cap. The digest suffix is Eta's scheme (`agent/mcp/McpRunContext.kt`,
 * Mangi-11/Eta @ c15de97).
 */
class AgentToolDefinitionApiNameTest {

    private fun tool(name: String) = AgentToolDefinition(
        name = name,
        description = "d",
        parameters = emptyMap(),
    )

    @Test
    fun `a short legal name is untouched`() {
        assertEquals("browser_use", tool("browser_use").apiName)
    }

    @Test
    fun `dots become underscores, as the dotted local scheme needs`() {
        assertEquals("mcp_github_search_code", tool("mcp.github.search_code").apiName)
    }

    @Test
    fun `a name that sanitizes to punctuation only is still distinguishable`() {
        val first = tool("。。。").apiName
        val second = tool("！！！").apiName

        assertTrue(first.startsWith("tool_"))
        assertEquals(13, first.length)
        assertFalse(first == second)
    }

    @Test
    fun `a long name is cut to the wire limit, not past it`() {
        val apiName = tool("mcp.server." + "t".repeat(200)).apiName

        assertEquals(64, apiName.length)
        assertTrue(apiName.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }

    @Test
    fun `two long names that share their prefix get different wire names`() {
        val prefix = "mcp.server." + "t".repeat(80)
        val first = tool(prefix + "alpha").apiName
        val second = tool(prefix + "beta").apiName

        assertFalse(first == second)
        assertEquals(64, first.length)
        assertEquals(64, second.length)
    }

    @Test
    fun `the wire name is stable for the same tool`() {
        val name = "mcp.server." + "x".repeat(100)

        assertEquals(tool(name).apiName, tool(name).apiName)
    }

    @Test
    fun `dispatch still recognises the canonical name and the wire name`() {
        val long = tool("mcp.server." + "y".repeat(100))

        assertTrue(long.matchesName("mcp.server." + "y".repeat(100)))
        assertTrue(long.matchesName(long.apiName))
        assertFalse(long.matchesName("mcp.server." + "z".repeat(100)))
    }
}
