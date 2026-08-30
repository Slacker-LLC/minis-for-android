package io.github.slackerllc.minis.runtime.minisd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisdBootstrapTest {
    private val policy = """{"methods":{"system.ping":{"mode":"allow"}},"caller":{"appUid":12345,"requireToken":false}}"""

    @Test
    fun `policyForUid replaces template uid and preserves policy`() {
        val materialized = JSONObject(MinisdBootstrap.policyForUid(policy, 23456))
        assertEquals(23456, materialized.getJSONObject("caller").getInt("appUid"))
        assertFalse(materialized.getJSONObject("caller").getBoolean("requireToken"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `policyForUid rejects root uid`() {
        MinisdBootstrap.policyForUid("""{"methods":{},"caller":{"appUid":0}}""", 0)
    }

    @Test
    fun `socket names are uid scoped and abstract`() {
        assertEquals("@minis.minisd.app.12345.v1", MinisdBootstrap.appSocketName(12345))
        assertEquals("@minis.minisd.root.12345.v1", MinisdBootstrap.brokerSocketName(12345))
    }

    @Test
    fun `production supervisor is stateless and checks uid pid and starttime`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "@minis.minisd.app.12345.v1",
            policyJson = policy,
            forceRestart = false,
            binaryPath = "/data/app/~~hash==/lib/arm64/libminisd.so",
            socketPath = "@minis.minisd.root.12345.v1",
            leasePid = 4321,
            leaseStartTime = 987654,
        )

        assertTrue(command.contains("libminisd.so"))
        assertTrue(command.contains("--policy-json"))
        assertTrue(command.contains("--app-socket"))
        assertTrue(command.contains("LEASE_PID=4321"))
        assertTrue(command.contains("LEASE_STARTTIME=987654"))
        assertTrue(command.contains("/^Uid:/"))
        assertTrue(command.contains("print ${'$'}22"))
        assertTrue(command.contains("while kill -0 \"\$child\""))
        assertFalse(command.contains("--watchdog --socket"))
        assertFalse(command.contains("PIDFILE"))
        assertFalse(command.contains("/data/adb/minis/run/minisd.sock"))
        assertFalse(command.contains("/data/adb/minis/policy"))
        assertFalse(command.contains("/data/adb/minis/bin/minisd"))
    }

    @Test
    fun `persistent migration marker is after relabel and compatibility aliases`() {
        val command = MinisdBootstrap.persistentDataPreparationCommand("/data/user/0/io.github.slackerllc.minis/files", 12345)
        val copy = command.indexOf("copy_tree")
        val relabel = command.indexOf("chcon -hR")
        val aliases = command.indexOf("link_alias")
        val marker = command.lastIndexOf("MIGRATION_MARKER.tmp")

        assertTrue(copy >= 0)
        assertTrue(relabel > copy)
        assertTrue(aliases > relabel)
        assertTrue(marker > aliases)
        assertTrue(command.contains("ROOT='/data/adb/minis'"))
        listOf("workspace", "sessions", "memory", "skills", "shared", "home").forEach { name ->
            assertTrue(command.contains("\"${'$'}ROOT/$name\""))
        }
        assertTrue(command.contains("app_data_file:s0:"))
        assertFalse(command.contains("chmod 0777"))
    }

    @Test
    fun `force restart targets only apk binary socket lineage`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "@minis.minisd.app.12345.v1",
            policyJson = policy,
            forceRestart = true,
            binaryPath = "/data/app/lib/arm64/libminisd.so",
            socketPath = "@minis.minisd.root.12345.v1",
            leasePid = 4321,
            leaseStartTime = 987654,
        )
        assertTrue(command.contains("for proc in /proc/[0-9]*"))
        assertTrue(command.contains("kill \"\$pid\""))
        assertTrue(command.contains("@minis.minisd.root.12345.v1"))
        assertFalse(command.contains("PIDFILE"))
    }

    @Test
    fun `effective uid parser ignores su diagnostics`() {
        assertEquals(0, MinisdBootstrap.parseEffectiveUid("KernelSU warning\n0\n"))
        assertEquals(10394, MinisdBootstrap.parseEffectiveUid("notice\n10394\nmore"))
        assertEquals(null, MinisdBootstrap.parseEffectiveUid("permission denied"))
    }

    @Test
    fun `proc stat parser binds lease to process start time`() {
        val fields = buildList {
            add("S")
            repeat(18) { add("0") }
            add("4242")
        }
        assertEquals(4242L, MinisdBootstrap.parseProcessStatStartTime("123 (app with ) paren) ${fields.joinToString(" ")}"))
    }

    @Test
    fun `shellQuote protects apostrophes`() {
        assertEquals("'a'\"'\"'b'", MinisdBootstrap.shellQuote("a'b"))
    }
}
