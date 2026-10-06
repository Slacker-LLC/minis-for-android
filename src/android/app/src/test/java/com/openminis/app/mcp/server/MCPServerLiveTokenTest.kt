package com.openminis.app.mcp.server

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tools/call is gated on the token as it is when the call runs, not as it was when the headers
 * arrived. These are the refusals; they return before any tool is executed, so no Context is needed.
 */
class MCPServerLiveTokenTest {

    private val accepted = TokenStore.Token(id = "t1", token = "secret-one", scope = setOf("linux.file.read"))
    private val request = MCPCodec.MCPRequest(
        id = 1,
        method = MCPCodec.METHOD_TOOLS_CALL,
        params = JSONObject().put("name", "linux.file.read").put("arguments", JSONObject()),
    )

    @After
    fun reset() {
        TokenStore.setInMemoryForTest(emptyList())
    }

    private fun call(): String = runBlocking { MCPServer(null).handleToolCall(request, accepted, "mcp:t1") }

    @Test
    fun aTokenRevokedAfterTheHeadersWereAcceptedIsRefused() {
        TokenStore.setInMemoryForTest(emptyList())
        assertTrue(call().contains("permission_denied"))
    }

    @Test
    fun aRotatedTokenIdWithANewSecretIsRefused() {
        TokenStore.setInMemoryForTest(listOf(accepted.copy(token = "secret-two")))
        assertTrue(call().contains("permission_denied"))
    }

    @Test
    fun aScopeNarrowedMeanwhileAppliesToTheCallInFlight() {
        TokenStore.setInMemoryForTest(listOf(accepted.copy(scope = setOf("android.something.else"))))
        assertTrue(call().contains("permission_denied"))
    }
}
