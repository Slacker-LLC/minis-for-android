package com.openminis.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tunnel subsystem's pure, secret-handling and state-mapping pieces:
 *  - redaction never lets a JWT-shaped token or a token= value into logs;
 *  - phase vocabulary is shared (no client-specific states);
 *  - status snapshots carry the三层 health fields the UIs render.
 */
class CloudflareTunnelRedactionTest {

    @Test
    fun `jwt shaped tokens are redacted`() {
        val line = "2026-01-01T00:00:00Z ERR token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U failed"
        val redacted = CloudflareTunnelManager.redact(line)
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(redacted.contains("<redacted-token>") || redacted.contains("<redacted>"))
    }

    @Test
    fun `tunnel token assignments are redacted`() {
        val line = "Invalid token: TUNNEL_TOKEN=abc123def456ghi789xyz"
        val redacted = CloudflareTunnelManager.redact(line)
        assertFalse(redacted.contains("abc123def456ghi789xyz"))
    }

    @Test
    fun `ordinary log lines pass through unchanged`() {
        val line = "Registered tunnel connection connIndex=0/4 edge=fra1"
        assertEquals(line, CloudflareTunnelManager.redact(line))
    }

    @Test
    fun `phase vocabulary is the shared contract`() {
        // Both UIs must render the SAME enum; no client-private copy.
        assertTrue(CloudflareTunnelManager.Phase.HEALTHY == "healthy")
        assertTrue(CloudflareTunnelManager.Phase.ORIGIN_DOWN == "origin-down")
        assertTrue(CloudflareTunnelManager.Phase.AUTH_FAILED == "auth-failed")
        assertTrue(CloudflareTunnelManager.Phase.UNCONFIGURED == "unconfigured")
    }

    @Test
    fun `health snapshot defaults are non-empty and honest`() {
        val h = CloudflareTunnelManager.HealthSnapshot()
        assertTrue(h.phase.isNotEmpty())
        assertFalse(h.running)
        assertEquals(0, h.edgeConnected)
        assertEquals("http2", h.protocol)
        assertEquals("auto", h.configuredProtocol)
    }
}
