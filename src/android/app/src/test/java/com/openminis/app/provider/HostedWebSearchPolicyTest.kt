package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-hosted-web-search] Ported from Eta `agent/model/ResponsesRequestBuilder.kt` (Mangi-11/Eta
 * @ c15de97). The rule decides what every Responses request carries, so the off path is asserted to
 * be the *same array* the caller passed - a request that grows a tool because of a default would be
 * a silent behaviour change for every existing entry.
 */
class HostedWebSearchPolicyTest {

    private fun tools(vararg types: String): JSONArray = JSONArray().also { array ->
        types.forEach { array.put(JSONObject().put("type", it)) }
    }

    @Test
    fun `off leaves the request exactly as it was`() {
        val managed = tools("function")

        assertSame(managed, HostedWebSearchPolicy.apply(managed, enabled = false))

        val empty = JSONArray()
        assertSame(empty, HostedWebSearchPolicy.apply(empty, enabled = false))
    }

    @Test
    fun `on adds the hosted tool next to the managed ones`() {
        val request = HostedWebSearchPolicy.apply(tools("function"), enabled = true)

        assertEquals(2, request.length())
        assertEquals("function", request.getJSONObject(0).getString("type"))
        assertEquals(HostedWebSearchPolicy.TOOL_TYPE, request.getJSONObject(1).getString("type"))
    }

    @Test
    fun `on adds it even when there are no managed tools`() {
        val request = HostedWebSearchPolicy.apply(JSONArray(), enabled = true)

        assertEquals(1, request.length())
        assertEquals(HostedWebSearchPolicy.TOOL_TYPE, request.getJSONObject(0).getString("type"))
    }

    @Test
    fun `a hosted tool the caller already passed is not duplicated`() {
        val already = tools("function", HostedWebSearchPolicy.TOOL_TYPE)

        val request = HostedWebSearchPolicy.apply(already, enabled = true)

        assertSame(already, request)
        assertEquals(2, request.length())
        assertTrue(request.toString().contains(HostedWebSearchPolicy.TOOL_TYPE))
    }

    @Test
    fun `adding never mutates the array it was handed`() {
        val managed = tools("function")

        HostedWebSearchPolicy.apply(managed, enabled = true)

        assertEquals("the caller's array is left untouched", 1, managed.length())
    }
}
