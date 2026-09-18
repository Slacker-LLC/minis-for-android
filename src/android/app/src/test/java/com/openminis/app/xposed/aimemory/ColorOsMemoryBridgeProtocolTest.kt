package com.openminis.app.xposed.aimemory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentColorOsMemoryToolsTest.kt` (Mangi-11/Eta
 * @ c15de97). What these cases pin down is the bound on the bridge: a request that round-trips, a
 * root command that cannot carry anything but the fixed provider and an encoded argument, and
 * refusals for anything that is not this module's own envelope.
 */
class ColorOsMemoryBridgeProtocolTest {

    @Test
    fun `the bridge round-trips a bounded payload`() {
        val encodedRequest = ColorOsMemoryBridgeProtocol.encodeRequest(
            ColorOsMemoryBridgeProtocol.OPERATION_SEARCH,
            JSONObject().put("query", "快递").put("limit", 10),
        )
        val request = requireNotNull(ColorOsMemoryBridgeProtocol.decodeRequest(encodedRequest))
        assertEquals(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, request.operation)
        assertEquals("快递", request.args.getString("query"))

        val content = JSONObject().put("ok", true).put("count", 2).toString()
        val envelope = ColorOsMemoryBridgeProtocol.encodeResponse(content)
        val stdout = "Result: Bundle[{${ColorOsMemoryBridgeProtocol.RESULT_KEY}=$envelope}]"
        assertEquals(content, ColorOsMemoryBridgeProtocol.decodeShellResponse(stdout))
    }

    @Test
    fun `the root command carries only the fixed provider and the encoded argument`() {
        val encodedRequest = ColorOsMemoryBridgeProtocol.encodeRequest(
            ColorOsMemoryBridgeProtocol.OPERATION_PLACES,
            JSONObject().put("query", "公司"),
        )
        val command = ColorOsMemoryBridgeProtocol.buildRootCommand(encodedRequest)

        assertTrue(command.contains(ColorOsMemoryBridgeProtocol.PROVIDER_URI))
        assertTrue(command.contains(ColorOsMemoryBridgeProtocol.METHOD))
        assertTrue(command.contains(encodedRequest))
        assertFalse("the query itself must not reach the shell", command.contains("公司"))
    }

    @Test
    fun `anything that is not this module's envelope is refused`() {
        assertNull(ColorOsMemoryBridgeProtocol.decodeRequest("not-base64!!"))
        assertNull(ColorOsMemoryBridgeProtocol.decodeRequest(""))
        assertNull(
            "a foreign method's answer is not this bridge's",
            ColorOsMemoryBridgeProtocol.decodeShellResponse("Result: Bundle[{other=x}]"),
        )
        assertNull(
            ColorOsMemoryBridgeProtocol.decodeShellResponse(
                "Result: Bundle[{${ColorOsMemoryBridgeProtocol.RESULT_KEY}=9:AAAA}]",
            ),
        )
    }

    @Test
    fun `a reserved SQLite column name is quoted`() {
        assertEquals("\"order\"", quoteColorOsMemoryIdentifier("order"))
        assertEquals("\"a\"\"b\"", quoteColorOsMemoryIdentifier("a\"b"))
    }
}
