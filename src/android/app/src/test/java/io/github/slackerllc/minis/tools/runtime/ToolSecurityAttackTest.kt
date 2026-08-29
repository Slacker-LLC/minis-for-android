package io.github.slackerllc.minis.tools.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Attack-surface tests (T-K1): parameter-injection / path-traversal tool
 * names must never resolve to a usable policy.
 *
 * policyFor is exact-key lookup plus one `group.*` wildcard suffix — no
 * prefix matching, no normalization, no trimming. Anything that isn't a
 * registered key falls through to wildcardPolicy or null (unknown → deny).
 * These cases pin that a remote caller can never upgrade an attack name
 * into MCP_ALLOWED; CONFIRM at best (which still gates on a human).
 */
class ToolSecurityAttackTest {

    private val attacker = "mcp:attacker"

    /** Acceptable outcomes: unknown (null policy) or MCP level != ALLOWED. */
    private fun assertNotAllowed(tool: String) {
        val policy = ToolPermissionManager.policyFor(tool)
        assertTrue(
            "attack name '$tool' unexpectedly resolved to a policy: $policy",
            policy == null || policy.mcp != ToolPermissionManager.Level.MCP_ALLOWED,
        )
        assertNotEquals(
            ToolPermissionManager.Level.MCP_ALLOWED,
            ToolPermissionManager.levelFor(tool, attacker),
        )
        // attack names must never be directly allowed (no human gate);
        // CONFIRM is acceptable — it still needs the human approval gate.
        assertFalse(ToolPermissionManager.isDirectlyAllowed(tool, attacker))
    }

    @Test
    fun `root prefixed tool names never allowed for mcp`() {
        for (tool in listOf(
            "root.shell", "root.shellRaw", "root.exec", "root.su", "root.sh",
            "root.shell2", "root..shell", "root.shell ", "ROOT.shell", "root\n.shell",
        )) {
            assertNotAllowed(tool)
        }
        // the one registered root.* entry is LOCAL_ONLY, invisible to MCP
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("root.shell", attacker),
        )
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
    }

    @Test
    fun `path traversal tool names never resolve to allowed policy`() {
        for (tool in listOf(
            "../../root.shell",
            "/data/adb/root.shell",
            "/system/bin/sh",
            "linux.shell/../../etc/passwd",
            "linux.file.read/../../root.shell",
            "..%2Froot.shell",
            "linux.shell;../../root.shell",
            "shell_execute",
            "sh -c",
        )) {
            assertNotAllowed(tool)
        }
    }

    @Test
    fun `wildcard smuggling never reaches allowed`() {
        for (tool in listOf(
            "mcp.root.shell",
            "mcp.*.root.shell",
            "android.diagnose.*.root.shell",
            "*",
            ".*",
            "android.diagnose.",
        )) {
            assertNotAllowed(tool)
        }
    }

    @Test
    fun `unknown tool names default to deny for every caller`() {
        repeat(32) {
            val tool = "unknown." + UUID.randomUUID().toString().substring(0, 8)
            assertNull(ToolPermissionManager.policyFor(tool))
            assertFalse(ToolPermissionManager.isRegistered(tool))
            assertEquals(ToolPermissionManager.Level.MCP_DENIED, ToolPermissionManager.levelFor(tool, attacker))
            assertEquals(ToolPermissionManager.Level.MCP_DENIED, ToolPermissionManager.levelFor(tool, "local_agent"))
            assertFalse(ToolPermissionManager.isAllowedFor(tool, attacker))
            assertFalse(
                ToolPermissionManager.tokenCanCall(
                    tool, attacker,
                    allowedSubset = emptySet(),
                    maxLevel = ToolPermissionManager.Level.MCP_CONFIRM,
                ),
            )
        }
    }

    @Test
    fun `empty and malformed names are denied`() {
        for (tool in listOf("", " ", ".", "..", "....", "\u0000", "root.shell\u0000")) {
            assertNotAllowed(tool)
        }
    }

    @Test
    fun `unknown caller strings get denied even for allowed tools`() {
        for (caller in listOf("", "attacker", "LOCAL_AGENT", "local_agent\u0000mcp:x", "mcp")) {
            assertEquals(
                ToolPermissionManager.Level.MCP_DENIED,
                ToolPermissionManager.levelFor("system.info", caller),
            )
        }
        // only the exact bareword is the local caller
        assertEquals(
            ToolPermissionManager.Level.MCP_ALLOWED,
            ToolPermissionManager.levelFor("system.info", "local_agent"),
        )
    }
}
