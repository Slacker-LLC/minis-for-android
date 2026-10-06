package com.openminis.app.tools.runtime

import com.openminis.app.tools.android.PrivilegedCommandRunner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: Root is locally available through structured argv only. */
class RootShellHandlerTest {

    @Test
    fun `root shell definition exposes structured argv surface`() {
        val def = RootShellHandler().definition
        assertEquals("root.shell", def.name)
        assertTrue(def.parameters.containsKey("tool"))
        assertTrue(def.parameters.containsKey("args"))
        assertTrue(def.parameters.containsKey("timeout_ms"))
        assertFalse(def.parameters.containsKey("command"))
        assertFalse(def.parameters.containsKey("access_mode"))
        assertEquals(listOf("tool"), def.required)
        assertTrue(def.description.contains("structured"))
        assertTrue(def.description.contains("trusted Android system"))
    }

    @Test
    fun `root shell is local only and hidden from mcp`() {
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("root.shell", ToolPermissionManager.CALLER_LOCAL),
        )
        assertEquals(
            ToolPermissionManager.Level.LOCAL_ONLY,
            ToolPermissionManager.levelFor("root.shell", "mcp:attacker"),
        )
        assertTrue(ToolPermissionManager.isAllowedFor("root.shell", ToolPermissionManager.CALLER_LOCAL))
        assertFalse(ToolPermissionManager.isAllowedFor("root.shell", "mcp:attacker"))
        assertFalse(ToolPermissionManager.mcpVisibleTools().contains("root.shell"))
    }

    @Test
    fun `root shell remains discoverable locally but hidden from mcp`() {
        ToolRegistry.register(RootShellHandler(), aliasNames = listOf("shell_root"))
        try {
            assertTrue(ToolRegistry.contains("root.shell"))
            assertEquals("root.shell", ToolRegistry.canonicalName("shell_root"))
            assertTrue(ToolRegistry.definition("root.shell") != null)
            assertTrue(ToolRegistry.definitions().any { it.name == "root.shell" })
            assertTrue(
                ToolRegistry.definitionsForCaller(ToolPermissionManager.CALLER_LOCAL)
                    .any { it.name == "root.shell" },
            )
            assertFalse(
                ToolRegistry.definitionsForCaller("mcp:attacker")
                    .any { it.name == "root.shell" },
            )
        } finally {
            ToolRegistry.unregister("root.shell")
        }
    }

    @Test
    fun `standard local agent schema includes structured root shell`() {
        ToolRegistry.register(RootShellHandler())
        try {
            assertTrue(
                com.openminis.app.tools.AgentTools.makeAgentTools()
                    .any { it.name == "root.shell" },
            )
        } finally {
            ToolRegistry.unregister("root.shell")
        }
    }

    private fun call(tool: String, vararg args: String) = runBlocking {
        RootShellHandler().execute(
            JSONObject().put("tool", tool).put("args", JSONArray(args.toList())).toString(),
            "session",
            TestContext.dummy(),
            "call-1",
        )
    }

    @Test
    fun `root shell refuses a shell or interpreter before anything runs`() {
        // Each of these hands Root a script through the structured contract. The refusal comes before the
        // trusted-path lookup and DirectRootRunner, so it holds on a host with no Android tree at all.
        val scripts = listOf(
            arrayOf("sh", "-c", "id"),
            arrayOf("SH", "-c", "id"),
            arrayOf("bash", "/data/local/tmp/x.sh"),
            arrayOf("su", "-c", "id"),
            arrayOf("toybox", "sh", "-c", "id"),
            arrayOf("busybox", "sh"),
            arrayOf("env", "sh", "-c", "id"),
            arrayOf("xargs", "sh"),
            arrayOf("nohup", "sh", "-c", "id"),
            arrayOf("setsid", "sh"),
            arrayOf("nsenter", "-t", "1", "-m", "sh"),
            arrayOf("awk", "BEGIN{system(\"id\")}"),
            arrayOf("python3", "-c", "import os"),
            arrayOf("app_process", "/system/bin", "Main"),
            arrayOf("find", "/", "-exec", "id", ";"),
            arrayOf("find", "/", "-execdir", "id", ";"),
            arrayOf("tar", "-xf", "a.tar", "--to-command=sh"),
            arrayOf("sqlite3", "db", ".shell id"),
            arrayOf("sqlite3", "db", "select 1;\n.system id"),
            arrayOf("sqlite3", "-cmd", ".shell id", "db"),
            arrayOf("ip", "netns", "exec", "ns", "sh"),
        )
        for (argv in scripts) {
            val tool = argv.first()
            val args = argv.drop(1)
            val result = call(tool, *args.toTypedArray())
            assertFalse("${argv.joinToString(" ")} must be refused", result.success)
            // The reason must be the interpreter rule, not "tool not found" (a JVM host has no /system/bin, so
            // a missing tool would also fail without proving anything).
            assertTrue(result.output, result.output.startsWith("Error: ") && result.output.contains("root.shell"))
            assertFalse(result.output, result.output.contains("not found"))
            assertTrue(
                "${argv.joinToString(" ")} must be refused by the interpreter rule",
                PrivilegedCommandRunner.genericRootToolDenial(tool, args) != null,
            )
        }
    }

    @Test
    fun `ordinary Android tools stay allowed by the interpreter rule`() {
        val allowed = listOf(
            "getprop" to listOf("ro.product.model"),
            "pm" to listOf("list", "packages"),
            "settings" to listOf("get", "global", "adb_enabled"),
            "dumpsys" to listOf("battery"),
            "find" to listOf("/data/system", "-name", "*.xml"),
            "sqlite3" to listOf("/data/data/x/db", "select name from sqlite_master;"),
            "ip" to listOf("addr", "show"),
            "cat" to listOf("/proc/version"),
        )
        for ((tool, args) in allowed) {
            assertNull("$tool ${args.joinToString(" ")}", PrivilegedCommandRunner.genericRootToolDenial(tool, args))
        }
    }

}
