package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-tools-list-shape] The MCP `tools/list` shape.
 *
 * The server used to publish the Anthropic Messages shape, whose schema field is
 * `input_schema`. The MCP spec spells it `inputSchema`, so a spec client read no
 * schema at all and saw tools with no parameters: measured on device, all 58
 * exposed tools came back with the snake key alone, and `linux_file_copy` then
 * answered a call that had to guess the argument names.
 */
class AgentToolDefinitionMcpShapeTest {

    private val copy = AgentToolDefinition(
        name = "linux.file.copy",
        description = "Copy a file or directory inside the workspace.",
        parameters = mapOf(
            "source" to AgentToolParam("string", "Source path"),
            "destination" to AgentToolParam("string", "Destination path"),
        ),
        required = listOf("source", "destination"),
    )

    @Test
    fun `the schema is published under the spec name`() {
        val json = copy.toMcpJson()
        assertEquals("linux_file_copy", json.getString("name"))
        val schema = json.getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
        val props = schema.getJSONObject("properties")
        assertTrue(props.has("source"))
        assertTrue(props.has("destination"))
        assertEquals("string", props.getJSONObject("source").getString("type"))
        val required = schema.getJSONArray("required")
        assertEquals(2, required.length())
        assertEquals("source", required.getString(0))
        assertFalse("MCP clients must not be handed the Anthropic spelling", json.has("input_schema"))
    }

    @Test
    fun `the anthropic shape keeps its own spelling`() {
        val json = copy.toAnthropicJson()
        assertTrue(json.has("input_schema"))
        assertFalse(json.has("inputSchema"))
    }

    @Test
    fun `both shapes carry the same schema`() {
        assertEquals(
            copy.toAnthropicJson().getJSONObject("input_schema").toString(),
            copy.toMcpJson().getJSONObject("inputSchema").toString(),
        )
    }
}

