package com.openminis.app.sandbox.minisd

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
    fun `watchdog command validates runtime assets and namespace owner before spawn`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/dev.openminispet.android/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = false,
            appMountNamespacePid = 4321,
        )

        assertTrue(command.contains("minisd missing or not executable"))
        assertTrue(command.contains("ubuntu rootfs missing"))
        assertTrue(command.contains("APP_MNT_PID=4321"))
        assertTrue(command.contains("APP_UID=12345"))
        assertTrue(command.contains("/^Uid:/"))
        assertTrue(command.contains("app mount namespace target mismatch"))
        assertTrue(command.contains("--watchdog --mount-ns-pid \"\$APP_MNT_PID\" --policy"))
        assertTrue(command.contains("--app-socket"))
        assertFalse(command.contains("minisd.pid"))
    }

    @Test
    fun `force restart targets watchdog parent through pidfile`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/dev.openminispet.android/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = true,
            appMountNamespacePid = 4321,
        )

        assertTrue(command.contains("/data/adb/minis/run/minisd.pid"))
        assertTrue(command.contains("child_cmd"))
        assertTrue(command.contains("--socket*/data/adb/minis/run/minisd.sock"))
        assertTrue(command.contains("PPid:"))
        assertTrue(command.contains("--watchdog"))
    }

    @Test
    fun `effective uid parser ignores su diagnostics`() {
        assertEquals(0, MinisdBootstrap.parseEffectiveUid("KernelSU warning\n0\n"))
        assertEquals(10394, MinisdBootstrap.parseEffectiveUid("notice\n10394\nmore"))
        assertEquals(null, MinisdBootstrap.parseEffectiveUid("permission denied"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `watchdog command rejects invalid app namespace pid`() {
        MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/dev.openminispet.android/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = false,
            appMountNamespacePid = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `watchdog command rejects policy without app uid`() {
        MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/dev.openminispet.android/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":0}}""",
            forceRestart = false,
            appMountNamespacePid = 4321,
        )
    }

    @Test
    fun `shellQuote protects apostrophes`() {
        assertEquals("'a'\"'\"'b'", MinisdBootstrap.shellQuote("a'b"))
    }
}
