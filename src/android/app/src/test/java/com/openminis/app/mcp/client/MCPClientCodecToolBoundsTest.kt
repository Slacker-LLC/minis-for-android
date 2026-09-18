package com.openminis.app.mcp.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-tool-bounds] A remote server chooses its own tool entries, so
 * the client bounds what it will project into the local registry: names that
 * cannot be dispatched are dropped (and counted) and descriptions are capped
 * before they reach every prompt.
 */
class MCPClientCodecToolBoundsTest {

    private fun page(vararg tools: JSONObject): MCPClientCodec.ToolsPage {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put(
                "result",
                JSONObject().put("tools", JSONArray(tools.toList())),
            )
        return MCPClientCodec.parseToolsList(frame)!!
    }

    @Test
    fun `description is truncated at the cap`() {
        val long = "d".repeat(MCPClientCodec.MAX_TOOL_DESCRIPTION_CHARS + 500)
        val parsed = page(JSONObject().put("name", "verbose").put("description", long))

        val description = parsed.tools.single().description!!
        assertEquals(MCPClientCodec.MAX_TOOL_DESCRIPTION_CHARS + 1, description.length)
        assertTrue(description.endsWith("…"))
    }

    @Test
    fun `description at the cap is left alone`() {
        val exact = "d".repeat(MCPClientCodec.MAX_TOOL_DESCRIPTION_CHARS)
        val parsed = page(JSONObject().put("name", "exact").put("description", exact))

        assertEquals(exact, parsed.tools.single().description)
    }

    @Test
    fun `over long tool name is dropped and counted`() {
        val long = "n".repeat(MCPClientCodec.MAX_TOOL_NAME_CHARS + 1)
        val parsed = page(
            JSONObject().put("name", long),
            JSONObject().put("name", "usable"),
        )

        assertEquals(listOf("usable"), parsed.tools.map { it.name })
        assertEquals(1, parsed.skippedTools)
    }

    @Test
    fun `tool name at the cap is kept`() {
        val exact = "n".repeat(MCPClientCodec.MAX_TOOL_NAME_CHARS)
        val parsed = page(JSONObject().put("name", exact))

        assertEquals(exact, parsed.tools.single().name)
        assertEquals(0, parsed.skippedTools)
    }

    @Test
    fun `blank name is dropped and counted`() {
        val parsed = page(JSONObject().put("name", "").put("description", "orphan"))

        assertTrue(parsed.tools.isEmpty())
        assertEquals(1, parsed.skippedTools)
    }

    @Test
    fun `blank description stays absent`() {
        val parsed = page(JSONObject().put("name", "quiet").put("description", "   "))

        assertNull(parsed.tools.single().description)
    }
}
