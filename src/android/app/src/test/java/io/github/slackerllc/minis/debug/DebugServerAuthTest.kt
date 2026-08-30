package io.github.slackerllc.minis.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-debugserver-auth] The auth gate contract (hardened 2026-08-21):
 * EVERY connection - loopback included - must present the exact per-install
 * token. Loopback is not a trust boundary on Android: any local process or
 * web page can reach 127.0.0.1, and the RPC surface can read files, export
 * API keys, run shell commands and drive the UI. The developer workflow
 * reads the token via adb run-as instead of relying on a loopback exemption.
 */
class DebugServerAuthTest {

    private val token = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4"

    @Test
    fun `loopback without token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = null, expectedToken = token))
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "", expectedToken = token))
    }

    @Test
    fun `loopback with wrong token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "wrong", expectedToken = token))
    }

    @Test
    fun `loopback with correct token is accepted`() {
        assertTrue(DebugServer.isAuthorized(isLoopback = true, providedToken = token, expectedToken = token))
    }

    @Test
    fun `remote without token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = null, expectedToken = token))
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "", expectedToken = token))
    }

    @Test
    fun `remote with wrong token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "deadbeef", expectedToken = token))
        // Same length, one char off — the constant-time compare must still reject.
        assertFalse(
            DebugServer.isAuthorized(
                isLoopback = false,
                providedToken = token.dropLast(1) + "X",
                expectedToken = token,
            ),
        )
    }

    @Test
    fun `remote with correct token is accepted`() {
        assertTrue(DebugServer.isAuthorized(isLoopback = false, providedToken = token, expectedToken = token))
    }

    @Test
    fun `empty expected token never authorizes remote`() {
        // Defensive: a failed token write must not silently open the gate.
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "", expectedToken = ""))
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "x", expectedToken = ""))
    }
}
