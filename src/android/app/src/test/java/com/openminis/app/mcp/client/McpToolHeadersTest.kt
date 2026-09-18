package com.openminis.app.mcp.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-param-headers-android] The ported Eta `McpToolHeaders` (Mangi-11/Eta @ c15de97):
 * which schema annotations become request headers, how the value is rendered, and what a
 * value that cannot travel as a header is turned into.
 */
class McpToolHeadersTest {

    private fun schema(vararg properties: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { (name, value) -> put(name, value) } })
    }

    private fun property(type: String, header: String? = null): JSONObject = JSONObject().apply {
        put("type", type)
        header?.let { put("x-mcp-header", it) }
    }

    @Test
    fun `an annotated string travels as a prefixed header`() {
        val headers = McpToolHeaders
            .fromSchema(schema("apiKey" to property("string", "X-Api-Key")))
            .extract(JSONObject().put("apiKey", "s3cret"))

        assertEquals(mapOf("Mcp-Param-X-Api-Key" to "s3cret"), headers)
    }

    @Test
    fun `an unannotated argument stays in the body`() {
        val headers = McpToolHeaders
            .fromSchema(schema("query" to property("string")))
            .extract(JSONObject().put("query", "pizza"))

        assertTrue(headers.isEmpty())
    }

    @Test
    fun `booleans and exact integers render, fractions and huge integers do not`() {
        val annotated = schema(
            "flag" to property("boolean", "X-Flag"),
            "count" to property("integer", "X-Count"),
            "ratio" to property("integer", "X-Ratio"),
            "huge" to property("integer", "X-Huge"),
        )
        val headers = McpToolHeaders.fromSchema(annotated).extract(
            JSONObject()
                .put("flag", true)
                .put("count", 42)
                .put("ratio", 1.5)
                .put("huge", 9_007_199_254_740_992L),
        )

        assertEquals(mapOf("Mcp-Param-X-Flag" to "true", "Mcp-Param-X-Count" to "42"), headers)
    }

    @Test
    fun `a value of the wrong type is left alone`() {
        val headers = McpToolHeaders
            .fromSchema(schema("count" to property("integer", "X-Count")))
            .extract(JSONObject().put("count", "42"))

        assertTrue(headers.isEmpty())
    }

    @Test
    fun `a missing or null argument produces no header`() {
        val codec = McpToolHeaders.fromSchema(schema("key" to property("string", "X-Key")))

        assertTrue(codec.extract(JSONObject()).isEmpty())
        assertTrue(codec.extract(JSONObject().put("key", JSONObject.NULL)).isEmpty())
    }

    @Test
    fun `nested properties are found and their path is followed`() {
        val nested = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put("token", property("string", "X-Token")),
            )
        val codec = McpToolHeaders.fromSchema(schema("auth" to nested))

        assertEquals(
            mapOf("Mcp-Param-X-Token" to "abc"),
            codec.extract(JSONObject().put("auth", JSONObject().put("token", "abc"))),
        )
        assertTrue(codec.extract(JSONObject().put("auth", JSONObject())).isEmpty())
    }

    @Test
    fun `an invalid header name or an unsupported type is ignored`() {
        val codec = McpToolHeaders.fromSchema(
            schema(
                "bad" to property("string", "Not A Header"),
                "object" to property("object", "X-Object"),
                "number" to property("number", "X-Number"),
            ),
        )

        assertTrue(codec.extract(JSONObject().put("bad", "x").put("object", "y").put("number", 1)).isEmpty())
    }

    @Test
    fun `plain ascii passes through unchanged`() {
        assertEquals("hello 42", encodeMcpHeaderValue("hello 42"))
        assertFalse(encodeMcpHeaderValue("hello").startsWith("=?base64?"))
    }

    @Test
    fun `anything a header cannot carry is base64-wrapped`() {
        assertTrue(encodeMcpHeaderValue("héllo").startsWith("=?base64?"))
        assertTrue(encodeMcpHeaderValue(" padded ").startsWith("=?base64?"))
        // A value that already looks like the wrapper must be wrapped again, or the
        // server cannot tell encoded bytes from a literal.
        assertTrue(encodeMcpHeaderValue("=?base64?AAA?=").startsWith("=?base64?"))
        assertTrue(encodeMcpHeaderValue("=?base64?AAA?=").length > "=?base64?AAA?=".length)
    }

    @Test
    fun `a wrapped value decodes back to the original bytes`() {
        val original = "ключ"
        val wrapped = encodeMcpHeaderValue(original)
        val payload = wrapped.removePrefix("=?base64?").removeSuffix("?=")

        assertEquals(original, String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8))
    }
}
