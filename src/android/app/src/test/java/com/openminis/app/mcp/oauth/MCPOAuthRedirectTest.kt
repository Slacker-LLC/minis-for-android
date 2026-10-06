package com.openminis.app.mcp.oauth

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MCPOAuthRedirectTest {
    @Test
    fun theDefaultRedirectIsAccepted() {
        assertNull(MCPOAuthController.redirectProblem(MCPOAuthController.DEFAULT_REDIRECT_URI))
        assertNull(MCPOAuthController.redirectProblem("http://127.0.0.1:54546/cb"))
    }

    @Test
    fun aRedirectWithoutAPortWouldNeverReachTheListener() {
        assertNotNull(MCPOAuthController.redirectProblem("http://127.0.0.1/callback"))
        assertNotNull(MCPOAuthController.redirectProblem("http://localhost/callback"))
    }

    @Test
    fun httpsCannotBeServedByAPlainHttpListener() {
        assertNotNull(MCPOAuthController.redirectProblem("https://127.0.0.1:54546/callback"))
    }

    @Test
    fun nonLoopbackHostsAndGarbageAreRefused() {
        assertNotNull(MCPOAuthController.redirectProblem("http://example.com:54546/callback"))
        assertNotNull(MCPOAuthController.redirectProblem("not a url"))
        assertNotNull(MCPOAuthController.redirectProblem("http://localhost:54546"))
    }
}
