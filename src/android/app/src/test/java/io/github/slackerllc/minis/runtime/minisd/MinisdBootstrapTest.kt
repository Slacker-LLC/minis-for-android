package io.github.slackerllc.minis.runtime.minisd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisdBootstrapTest {

    @Test
    fun `policyForUid replaces template uid and preserves policy`() {
        val template = """
            {
              "methods": {"system.ping": {"mode": "allow"}},
              "caller": {"appUid": 0, "requireToken": false}
            }
        """.trimIndent()

        val materialized = JSONObject(MinisdBootstrap.policyForUid(template, 12345))

        assertEquals(12345, materialized.getJSONObject("caller").getInt("appUid"))
        assertFalse(materialized.getJSONObject("caller").getBoolean("requireToken"))
        assertEquals(
            "allow",
            materialized.getJSONObject("methods").getJSONObject("system.ping").getString("mode"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `policyForUid rejects root uid`() {
        MinisdBootstrap.policyForUid(
            """{"methods":{},"caller":{"appUid":0}}""",
            0,
        )
    }

    @Test
    fun `socket names are uid scoped and abstract`() {
        assertEquals("@minis.minisd.app.12345.v1", MinisdBootstrap.appSocketName(12345))
        assertEquals("@minis.minisd.root.12345.v1", MinisdBootstrap.brokerSocketName(12345))
    }

    @Test
    fun `watchdog uses apk binary and in-memory policy`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "@minis.minisd.app.12345.v1",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = false,
            binaryPath = "/data/app/~~hash==/lib/arm64/libminisd.so",
            socketPath = "@minis.minisd.root.12345.v1",
            leasePid = 4321,
            leaseStartTime = 987654,
        )

        assertTrue(command.contains("--watchdog"))
        assertTrue(command.contains("--policy-json"))
        assertTrue(command.contains("--lease-pid \"\$LEASE_PID\""))
        assertTrue(command.contains("--lease-starttime \"\$LEASE_STARTTIME\""))
        assertTrue(command.contains("--app-socket \"\$APP_SOCKET\""))
        assertTrue(command.contains("libminisd.so"))
        assertFalse(command.contains("--policy \"\$POLICY\""))
        assertFalse(command.contains("PIDFILE"))
        assertFalse(command.contains("/data/adb/minis/bin/minisd"))
    }

    @Test
    fun `force restart scans only exact broker lineage without pidfile`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "@minis.minisd.app.12345.v1",
            policyJson = "{}",
            forceRestart = true,
            binaryPath = "/data/app/lib/arm64/libminisd.so",
            socketPath = "@minis.minisd.root.12345.v1",
            leasePid = 4321,
            leaseStartTime = 987654,
        )

        assertTrue(command.contains("for proc in /proc/[0-9]*"))
        assertTrue(command.contains("--watchdog"))
        assertTrue(command.contains("kill \"\$pid\""))
        assertTrue(command.contains("@minis.minisd.root.12345.v1"))
        assertTrue(command.contains("LEASE_STARTTIME=987654"))
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
        assertEquals(4242L, MinisdBootstrap.parseProcessStatStartTime("123 (app) ${fields.joinToString(" ")}"))
    }

    @Test
    fun `shellQuote protects apostrophes`() {
        assertEquals("'a'\"'\"'b'", MinisdBootstrap.shellQuote("a'b"))
    }
}
