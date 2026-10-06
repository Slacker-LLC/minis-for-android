package com.openminis.app.debug

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RpcParamsTest {
    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected a -32602 RPCException")
        } catch (e: RPCException) {
            assertEquals(-32602, e.code)
        }
    }

    @Test
    fun `absent params mean leave it alone`() {
        val p = JSONObject()
        assertNull(RpcParams.string(p, "apiKey"))
        assertNull(RpcParams.boolean(p, "enabled"))
        assertNull(RpcParams.stringList(p, "args"))
        assertNull(RpcParams.stringMap(p, "headers"))
    }

    @Test
    fun `well-typed values are read as they are`() {
        val p = JSONObject()
            .put("apiKey", "")
            .put("enabled", false)
            .put("args", JSONArray(listOf("-y", "pkg")))
            .put("headers", JSONObject().put("Authorization", "Bearer x"))
        assertEquals("", RpcParams.string(p, "apiKey"))
        assertEquals(false, RpcParams.boolean(p, "enabled"))
        assertEquals(listOf("-y", "pkg"), RpcParams.stringList(p, "args"))
        assertEquals(mapOf("Authorization" to "Bearer x"), RpcParams.stringMap(p, "headers"))
        assertEquals(emptyList<String>(), RpcParams.stringList(JSONObject().put("args", JSONArray()), "args"))
    }

    @Test
    fun `null is not a string, so it cannot overwrite a credential with the text null`() {
        assertInvalid { RpcParams.string(JSONObject().put("apiKey", JSONObject.NULL), "apiKey") }
    }

    @Test
    fun `a wrong scalar type is refused`() {
        assertInvalid { RpcParams.string(JSONObject().put("apiKey", 12345), "apiKey") }
        assertInvalid { RpcParams.string(JSONObject().put("apiKey", JSONObject()), "apiKey") }
        assertInvalid { RpcParams.boolean(JSONObject().put("enabled", "true"), "enabled") }
        assertInvalid { RpcParams.boolean(JSONObject().put("enabled", 1), "enabled") }
    }

    @Test
    fun `a wrong collection shape is refused instead of becoming an empty one`() {
        assertInvalid { RpcParams.stringList(JSONObject().put("args", "not an array"), "args") }
        assertInvalid { RpcParams.stringList(JSONObject().put("args", JSONObject.NULL), "args") }
        assertInvalid { RpcParams.stringList(JSONObject().put("args", JSONArray(listOf("a", 2))), "args") }
        assertInvalid { RpcParams.stringMap(JSONObject().put("headers", "x"), "headers") }
        assertInvalid { RpcParams.stringMap(JSONObject().put("env", JSONArray()), "env") }
        assertInvalid { RpcParams.stringMap(JSONObject().put("env", JSONObject().put("K", 1)), "env") }
    }
}
