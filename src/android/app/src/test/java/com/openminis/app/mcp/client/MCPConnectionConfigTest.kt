package com.openminis.app.mcp.client

import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import com.openminis.app.mcp.oauth.MCPOAuthConfig
import com.openminis.app.mcp.oauth.MCPOAuthStore.StoredTokens
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MCPConnectionConfigTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `app variables replace every reference in url headers env command and args`() {
        val config = MCPServerConfig(
            id = "docs",
            url = "https://\$\$HOST/mcp?key=\$\${KEY}",
            headers = mapOf("Authorization" to "Bearer \$\$TOKEN"),
            command = "\$\$BIN",
            args = listOf("--token", "\$\$TOKEN"),
            env = mapOf("API_KEY" to "\$\$KEY"),
        )
        val vars = mapOf("HOST" to "docs.example", "KEY" to "k-1", "TOKEN" to "t-2", "BIN" to "server")
        val out = MCPConnectionConfig.expand(config, vars)
        assertEquals("https://docs.example/mcp?key=k-1", out.url)
        assertEquals("Bearer t-2", out.headers["Authorization"])
        assertEquals("server", out.command)
        assertEquals(listOf("--token", "t-2"), out.args)
        assertEquals("k-1", out.env["API_KEY"])
    }

    @Test
    fun `a single dollar is left alone`() {
        val config = MCPServerConfig(id = "odata", url = "https://x.example/api?\$filter=a&\$top=5")
        assertEquals(config.url, MCPConnectionConfig.expand(config, emptyMap()).url)
    }

    @Test
    fun `an unset variable fails the connection by name and never sends the literal`() {
        val config = MCPServerConfig(id = "docs", url = "https://x.example", headers = mapOf("Authorization" to "Bearer \$\$MISSING"))
        try {
            MCPConnectionConfig.expand(config, mapOf("OTHER" to "secret-value"))
            fail("expected the missing variable to fail the connection")
        } catch (e: MCPTransportException) {
            assertTrue(e.message!!, e.message!!.contains("MISSING"))
            assertFalse("no variable value leaks into the error", e.message!!.contains("secret-value"))
        }
    }

    private fun oauthConfig() = MCPServerConfig(
        id = "docs",
        url = "https://docs.example/mcp",
        oauth = MCPOAuthConfig(
            clientId = "client",
            authorizationEndpoint = "https://auth.example/authorize",
            tokenEndpoint = server.url("/token").toString(),
        ),
    )

    @Test
    fun `a fresh token is used as stored and no token means none is sent`() {
        val stored = StoredTokens("access-1", "refresh-1", expiresAtMs = 10_000_000L)
        assertEquals("access-1", MCPConnectionConfig.resolveToken(oauthConfig(), stored, null, now = 1_000L) { fail() })
        assertNull(MCPConnectionConfig.resolveToken(oauthConfig(), null, null, now = 1_000L) { fail() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expiring token is refreshed and stored`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"access-2","expires_in":3600}"""))
        var saved: StoredTokens? = null
        val token = MCPConnectionConfig.resolveToken(
            oauthConfig(),
            StoredTokens("access-1", "refresh-1", expiresAtMs = 1_030_000L),
            clientSecret = "s3",
            now = 1_000_000L,
        ) { saved = it }
        assertEquals("access-2", token)
        assertEquals("the old refresh token is kept when none is returned", "refresh-1", saved?.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, saved?.expiresAtMs)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("grant_type=refresh_token") && body.contains("refresh_token=refresh-1") && body.contains("client_secret=s3"))
    }

    @Test
    fun `an expired token that cannot be refreshed fails instead of being sent`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val expired = StoredTokens("access-1", "refresh-1", expiresAtMs = 500L)
        for (stored in listOf(expired, expired.copy(refreshToken = null))) {
            try {
                MCPConnectionConfig.resolveToken(oauthConfig(), stored, null, now = 1_000L) { fail("must not save") }
                fail("expected the expired sign-in to fail")
            } catch (e: MCPTransportException) {
                assertTrue(e.message!!, e.message!!.contains("sign in again"))
            }
        }
    }
}
