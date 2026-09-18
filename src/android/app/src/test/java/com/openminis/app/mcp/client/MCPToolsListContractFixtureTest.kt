package com.openminis.app.mcp.client

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.runtime.MCPToolHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #41 regression coverage for the repository-observed external MCP
 * tools/list contract. This intentionally validates current Minis behavior;
 * it is not an upstream MCP conformance suite.
 */
class MCPToolsListContractFixtureTest {

    @Test
    fun `tools list fixture pins permissive parse and schema projection`() {
        val root = fixture()
        val page = MCPClientCodec.parseToolsList(root.getJSONObject("toolsList"))
        assertNotNull(page)

        val tools = page!!.tools
        assertEquals(listOf("planner_2", "lax_tool", "camel_only"), tools.map { it.name })
        // The fixture also carries an entry with an empty name; it must be
        // dropped and reported rather than silently vanish.
        assertEquals(1, page.skippedTools)

        val planner = tools.first { it.name == "planner_2" }
        assertEquals("Build a plan; do not execute it.", planner.description)
        val schema = planner.inputSchema
        assertNotNull(schema)
        assertEquals("object", schema!!.getString("type"))
        assertEquals("request", schema.getJSONArray("required").getString(0))

        val params = MCPToolHandler.schemaToParams(schema)
        assertEquals(setOf("request", "mode"), params.keys)
        assertEquals("string", params.getValue("request").type)
        assertEquals(listOf("fast", "deep"), params.getValue("mode").enumValues)

        // The projection helper consumes properties only; top-level type and
        // required are not returned as part of the parameter map.
        assertTrue(params.isNotEmpty())

        val lax = tools.first { it.name == "lax_tool" }
        assertNull(lax.description)
        assertNull(lax.inputSchema)
        assertTrue(MCPToolHandler.schemaToParams(lax.inputSchema).isEmpty())

        // [T-android-mcp-input-schema-casing] The MCP specification spelling is
        // `inputSchema`; the snake_case form is an Anthropic convention some
        // servers still send. Both must project into parameters now — before
        // this, a camelCase-only server contributed zero typed parameters and
        // its tools were callable with unvalidated arguments.
        val camelOnly = tools.first { it.name == "camel_only" }
        val camelSchema = camelOnly.inputSchema
        assertNotNull(camelSchema)
        assertEquals("object", camelSchema!!.getString("type"))
        assertEquals(setOf("ignored"), MCPToolHandler.schemaToParams(camelSchema).keys)
    }

    @Test
    fun `missing tools array is accepted as an empty page`() {
        val page = MCPClientCodec.parseToolsList(fixture().getJSONObject("missingTools"))
        assertNotNull(page)
        assertTrue(page!!.tools.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `fixture pins server and provider wire sanitization`() {
        val names = fixture().getJSONObject("nameCases")

        // MCPProvider.sanitizeId is private by design; reflection keeps this
        // regression test production-neutral while asserting the current rule.
        val sanitize = MCPProvider::class.java.getDeclaredMethod("sanitizeId", String::class.java)
        sanitize.isAccessible = true
        val sanitized = sanitize.invoke(MCPProvider, names.getString("serverInput")) as String
        assertEquals(names.getString("serverSanitized"), sanitized)

        val definition = AgentToolDefinition(
            name = names.getString("canonicalToolName"),
            description = "fixture",
            parameters = emptyMap(),
        )
        assertEquals(names.getString("providerWireName"), definition.apiName)
    }

    private fun fixture(): JSONObject {
        val stream = requireNotNull(
            MCPToolsListContractFixtureTest::class.java.classLoader
                ?.getResourceAsStream("mcp/tools-list-contract/repo-observed.json"),
        ) { "missing tools/list contract fixture" }
        return stream.bufferedReader().use { JSONObject(it.readText()) }
    }

    @Test
    fun `both schema spellings parse and the specification spelling wins`() {
        val frame = JSONObject(
            """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[
              {"name":"both",
               "inputSchema":{"type":"object","properties":{"camel":{"type":"string"}}},
               "input_schema":{"type":"object","properties":{"snake":{"type":"string"}}}},
              {"name":"snake_only",
               "input_schema":{"type":"object","properties":{"snake":{"type":"string"}}}}
            ]}}
            """.trimIndent(),
        )

        val tools = MCPClientCodec.parseToolsList(frame)!!.tools
        val both = tools.first { it.name == "both" }
        assertEquals(setOf("camel"), MCPToolHandler.schemaToParams(both.inputSchema).keys)
        val snakeOnly = tools.first { it.name == "snake_only" }
        assertEquals(setOf("snake"), MCPToolHandler.schemaToParams(snakeOnly.inputSchema).keys)
    }
}
