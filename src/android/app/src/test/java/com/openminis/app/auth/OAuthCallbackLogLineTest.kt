package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OAuthCallbackLogLineTest {

    @Test
    fun theCodeAndStateNeverReachTheLogLine() {
        val line = OAuthCallbackServer.loggableRequestLine(
            "GET /callback?code=CANARY_CODE_123&state=CANARY_STATE_456 HTTP/1.1",
        )
        assertEquals("GET /callback (query: yes)", line)
        assertFalse(line.contains("CANARY"))
    }

    @Test
    fun aRequestWithoutAQueryIsSaidToHaveNone() {
        assertEquals("OPTIONS /callback (query: no)", OAuthCallbackServer.loggableRequestLine("OPTIONS /callback HTTP/1.1"))
    }

    @Test
    fun garbageAndOversizedInputStaysShort() {
        assertEquals(" (query: no)", OAuthCallbackServer.loggableRequestLine(""))
        val long = OAuthCallbackServer.loggableRequestLine("GET /" + "a".repeat(5_000) + "?code=SECRET HTTP/1.1")
        assertFalse(long.contains("SECRET"))
        assertEquals(true, long.length < 200)
    }
}
