package com.openminis.app.tools.runtime

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-tool-arg-schema] Coverage for execution-time argument conformance.
 *
 * The two properties that matter most here are: reject the shapes a tool helper
 * would mishandle, and never reject the shapes `ToolJsonRepair` legitimately
 * produces (stringified non-string values).
 */
class ToolCallValidatorTest {

    private fun numberTool() = AgentToolDefinition(
        name = "numeric_tool",
        description = "takes a count",
        parameters = mapOf("count" to AgentToolParam("integer", "How many")),
        required = listOf("count"),
    )

    private fun modeTool() = AgentToolDefinition(
        name = "mode_tool",
        description = "takes an enum",
        parameters = mapOf(
            "mode" to AgentToolParam("string", "Mode", enumValues = listOf("fast", "deep")),
        ),
        required = listOf("mode"),
    )

    private fun listTool() = AgentToolDefinition(
        name = "list_tool",
        description = "takes a list of numbers",
        parameters = mapOf(
            "ids" to AgentToolParam("array", "Ids", items = AgentToolParam("integer", "One id")),
        ),
        required = listOf("ids"),
    )

    private fun nestedTool() = AgentToolDefinition(
        name = "nested_tool",
        description = "takes a nested object",
        parameters = mapOf(
            "target" to AgentToolParam(
                "object",
                "Where to write",
                properties = mapOf(
                    "path" to AgentToolParam("string", "Path"),
                    "line" to AgentToolParam("integer", "Line"),
                ),
                requiredProperties = listOf("path"),
            ),
        ),
        required = listOf("target"),
    )

    private fun validate(tool: AgentToolDefinition, json: String) =
        ToolCallValidator.validate(tool, JSONObject(json))

    // ── Type conformance ──

    @Test
    fun `non numeric string for an integer is rejected`() {
        val rejection = validate(numberTool(), "{\"count\":\"many\"}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_TYPE", rejection!!.code)
        assertTrueMessageNames(rejection, "count", "an integer")
    }

    @Test
    fun `integer accepts numbers and their string form`() {
        // The native forms and the JSON-string forms of the same values: the
        // latter only exists because ToolJsonRepair stringifies non-string
        // required values before preflight looks at them.
        for (json in listOf(
            "{\"count\":0}",
            "{\"count\":42}",
            "{\"count\":\"42\"}",
            "{\"count\":\"3.0\"}",
        )) {
            assertNull("expected $json to pass", validate(numberTool(), json))
        }
    }

    // ── Enum membership ──

    @Test
    fun `value outside the declared enum is rejected`() {
        val rejection = validate(modeTool(), "{\"mode\":\"turbo\"}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_ENUM", rejection!!.code)
        assertTrueMessageNames(rejection, "mode", "fast")
    }

    @Test
    fun `declared enum members pass`() {
        for (json in listOf("{\"mode\":\"fast\"}", "{\"mode\":\"deep\"}")) {
            assertNull("expected $json to pass", validate(modeTool(), json))
        }
    }

    // ── Arrays ──

    @Test
    fun `array element that violates the item type is rejected`() {
        val rejection = validate(listTool(), "{\"ids\":[1,2,\"nope\"]}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_TYPE", rejection!!.code)
        assertTrueMessageNames(rejection, "ids[2]", "an integer")
    }

    @Test
    fun `array accepts a stringified array and rejects a scalar`() {
        // "[1,2]" is what repair produces for a non-string required array.
        assertNull(validate(listTool(), "{\"ids\":\"[1,2]\"}"))
        val rejection = validate(listTool(), "{\"ids\":\"1,2\"}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_TYPE", rejection!!.code)
    }

    // ── Nested objects ──

    @Test
    fun `nested object missing its required key is rejected`() {
        val rejection = validate(nestedTool(), "{\"target\":{\"line\":3}}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_OBJECT_INCOMPLETE", rejection!!.code)
        assertTrueMessageNames(rejection, "target", "path")
    }

    @Test
    fun `nested object property type is checked`() {
        val rejection = validate(nestedTool(), "{\"target\":{\"path\":\"/a\",\"line\":\"soon\"}}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_TYPE", rejection!!.code)
        assertTrueMessageNames(rejection, "target.line", "an integer")
    }

    @Test
    fun `well formed nested object passes`() {
        assertNull(validate(nestedTool(), "{\"target\":{\"path\":\"/a\",\"line\":3}}"))
    }

    // ── Fail-closed edges ──

    @Test
    fun `undeclared keys are left to the tool helper`() {
        // tool_title is injected across all tools and helpers read their own
        // optional fields, so extra keys must not be a rejection.
        assertNull(
            validate(numberTool(), "{\"count\":1,\"tool_title\":\"Counting\",\"extra\":true}"),
        )
    }

    @Test
    fun `unsupported declared type fails closed`() {
        val tool = AgentToolDefinition(
            name = "weird_tool",
            description = "declares a type we cannot check",
            parameters = mapOf("blob" to AgentToolParam("binary", "Blob")),
            required = listOf("blob"),
        )
        val rejection = validate(tool, "{\"blob\":\"x\"}")
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENT_SCHEMA_UNUSABLE", rejection!!.code)
    }

    @Test
    fun `oversized argument payload is rejected before any helper runs`() {
        val payload = JSONObject().apply {
            put("count", 1)
            put("padding", "x".repeat(ToolCallValidator.MAX_ARGUMENT_BYTES))
        }
        val rejection = ToolCallValidator.validate(numberTool(), payload)
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENTS_TOO_LARGE", rejection!!.code)
    }

    @Test
    fun `deeply nested arguments are rejected instead of recursing`() {
        // A schema that refers to itself: the mutable map is captured by
        // reference, so the node's own `properties` map holds the node.
        val children = mutableMapOf<String, AgentToolParam>()
        val recursive = AgentToolParam("object", "Recursive node", properties = children)
        children["child"] = recursive
        val tool = AgentToolDefinition(
            name = "deep_tool",
            description = "accepts a recursive node",
            parameters = mapOf("node" to recursive),
            required = listOf("node"),
        )
        // Build one level past the recursion bound.
        val deep = JSONObject()
        var cursor = deep
        repeat(ToolCallValidator.MAX_NESTING_DEPTH + 2) {
            val child = JSONObject()
            cursor.put("child", child)
            cursor = child
        }
        val rejection = ToolCallValidator.validate(tool, JSONObject().put("node", deep))
        assertNotNull(rejection)
        assertEquals("TOOL_ARGUMENTS_TOO_DEEP", rejection!!.code)
    }

    @Test
    fun `explicit null is left to preflight`() {
        assertNull(validate(numberTool(), "{\"count\":null}"))
    }

    @Test
    fun `empty arguments are left to preflight`() {
        assertNull(validate(numberTool(), "{}"))
    }

    @Test
    fun `rejection messages never echo a long value`() {
        val longValue = "y".repeat(500)
        val rejection = validate(numberTool(), JSONObject().put("count", longValue).toString())
        assertNotNull(rejection)
        assertEquals(true, rejection!!.message.length <= 512)
        assertEquals(false, rejection.message.contains(longValue))
    }

    @Test
    fun `array helper is not confused by a json array value`() {
        val tool = AgentToolDefinition(
            name = "any_tool",
            description = "unused",
            parameters = mapOf(
                "ids" to AgentToolParam("array", "Ids", items = AgentToolParam("string", "Id")),
            ),
        )
        val args = JSONObject().put("ids", JSONArray().put("a").put("b"))
        assertNull(ToolCallValidator.validate(tool, args))
    }

    private fun assertTrueMessageNames(
        rejection: ToolArgumentRejection,
        vararg fragments: String,
    ) {
        for (fragment in fragments) {
            assertEquals(
                "message '${rejection.message}' must mention '$fragment'",
                true,
                rejection.message.contains(fragment),
            )
        }
    }
}
