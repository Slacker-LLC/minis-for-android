package com.openminis.app.mcp.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-schema-bounds] A remote server's `inputSchema` reaches this app
 * untrusted and is echoed into every provider request for that tool, so it is bounded
 * per tool and in total. The tool itself survives a dropped schema — it stays callable,
 * just untyped — which is what an MCP server accepts.
 */
class McpToolSchemaBoundsTest {

    private fun schemaWithPadding(chars: Int): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))
        .put("description", "x".repeat(chars))

    @Test
    fun `a schema that fits is returned as the same object`() {
        val schema = schemaWithPadding(10)

        assertSame(schema, MCPClientCodec.boundSchema(schema))
    }

    @Test
    fun `a schema past the per-tool limit is dropped`() {
        val oversized = schemaWithPadding(MCPClientCodec.MAX_TOOL_SCHEMA_CHARS)

        assertTrue(oversized.toString().length > MCPClientCodec.MAX_TOOL_SCHEMA_CHARS)
        assertNull(MCPClientCodec.boundSchema(oversized))
    }

    @Test
    fun `an absent or empty schema is absent`() {
        assertNull(MCPClientCodec.boundSchema(null))
        assertNull(MCPClientCodec.boundSchema(JSONObject()))
    }

    @Test
    fun `tools list parsing keeps the tool but drops its oversized schema`() {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put(
                "result",
                JSONObject().put(
                    "tools",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "bloated")
                            .put("description", "a tool with a huge schema")
                            .put("inputSchema", schemaWithPadding(MCPClientCodec.MAX_TOOL_SCHEMA_CHARS)),
                    ),
                ),
            )

        val page = MCPClientCodec.parseToolsList(frame)!!

        assertEquals(1, page.tools.size)
        assertEquals("bloated", page.tools[0].name)
        assertEquals("a tool with a huge schema", page.tools[0].description)
        assertNull(page.tools[0].inputSchema)
    }

    @Test
    fun `the total budget accepts until it is full and then refuses`() {
        val budget = MCPClientCodec.SchemaBudget(limit = 100)

        assertTrue(budget.accept(60))
        assertTrue(budget.accept(40))
        assertFalse(budget.accept(1))
    }

    @Test
    fun `the total budget refuses a schema that would overshoot it`() {
        val budget = MCPClientCodec.SchemaBudget(limit = 100)

        assertTrue(budget.accept(50))
        assertFalse(budget.accept(51))
        // A smaller one still fits: the refused schema did not consume the budget.
        assertTrue(budget.accept(50))
    }

    @Test
    fun `a zero-length schema never consumes budget`() {
        val budget = MCPClientCodec.SchemaBudget(limit = 10)

        assertFalse(budget.accept(0))
        assertFalse(budget.accept(-5))
        assertTrue(budget.accept(10))
    }

    @Test
    fun `the defaults leave room for a normal tool catalogue`() {
        assertTrue(MCPClientCodec.MAX_TOOL_SCHEMA_CHARS >= 16 * 1024)
        assertTrue(MCPClientCodec.MAX_TOTAL_SCHEMA_CHARS >= 8 * MCPClientCodec.MAX_TOOL_SCHEMA_CHARS)
    }
}
