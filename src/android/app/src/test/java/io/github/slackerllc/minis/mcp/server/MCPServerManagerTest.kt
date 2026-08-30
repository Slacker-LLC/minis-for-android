package io.github.slackerllc.minis.mcp.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mcp-server] Fail-closed coverage for [MCPServerManager].
 *
 * JVM-only (no Robolectric in this module): the token gate and the restart
 * policy are the testable parts. The "configured → server binds" path and
 * the live supervisor need a real Context + a real port bind, so they are an
 * instrumented-test concern.
 */
class MCPServerManagerTest {

    @Test
    fun `fail closed when token not configured`() {
        // Fresh manager state: TokenStore never read as configured.
        assertFalse(MCPServerManager.start())
        assertFalse(MCPServerManager.running)
    }

    @Test
    fun `fail closed is idempotent`() {
        assertFalse(MCPServerManager.start())
        assertFalse(MCPServerManager.start())
    }

    @Test
    fun `linux tools unavailable when ubuntu runtime is down`() {
        // snapshot starts running=false; no UbuntuRuntime.init in a JVM test.
        assertFalse(MCPServerManager.linuxToolsAvailable())
    }

    @Test
    fun `restart gate allows five consecutive restarts then refuses`() {
        for (i in 0 until MCPServerManager.MAX_CONSECUTIVE_RESTARTS) {
            assertTrue("restart ${i + 1} allowed", MCPServerManager.shouldRestart(i))
        }
        assertFalse(MCPServerManager.shouldRestart(MCPServerManager.MAX_CONSECUTIVE_RESTARTS))
        assertFalse(MCPServerManager.shouldRestart(MCPServerManager.MAX_CONSECUTIVE_RESTARTS + 1))
    }

    @Test
    fun `stop is safe on JVM and leaves running false`() {
        // no appContext / server on the JVM: stop() must not throw or resurrect
        MCPServerManager.stop()
        assertFalse(MCPServerManager.running)
    }

    @Test
    fun `status reports gate state`() {
        val s = MCPServerManager.status()
        assertFalse(s.running)
        assertFalse(s.configured)
        assertTrue(s.port == MCPServerManager.PORT)
    }
}
