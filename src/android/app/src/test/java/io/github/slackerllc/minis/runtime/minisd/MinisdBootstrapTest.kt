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
    fun `watchdog bootstrap is independent of rootfs health`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/legacy.app/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = false,
        )

        assertTrue(command.contains("minisd missing or not executable"))
        assertTrue(command.contains("--watchdog --policy"))
        assertTrue(command.contains("--app-socket"))
        assertFalse(command.contains("ubuntu rootfs missing"))
        assertFalse(command.contains("ROOTFS="))
    }

    @Test
    fun `stale pidfile is cleaned without killing unverified process`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/legacy.app/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = false,
        )

        assertTrue(command.contains("PIDFILE='/data/adb/minis/run/minisd.pid'"))
        assertTrue(command.contains("*[!0-9]*"))
        assertTrue(command.contains("[ -d \"/proc/\$pid\" ] ||"))
        assertTrue(command.contains("rm -f \"\$PIDFILE\""))
        assertFalse(command.contains("kill \"\$pid\""))
    }

    @Test
    fun `force restart only kills verified watchdog lineage`() {
        val command = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/user/0/legacy.app/files/minis/minisd.sock",
            policyJson = """{"methods":{},"caller":{"appUid":12345}}""",
            forceRestart = true,
        )

        assertTrue(command.contains("child_cmd"))
        assertTrue(command.contains("--socket*/data/adb/minis/run/minisd.sock"))
        assertTrue(command.contains("PPid:"))
        assertTrue(command.contains("parent_cmd"))
        assertTrue(command.contains("*minisd*--watchdog*"))
        assertTrue(command.contains("kill \"\$ppid\""))
        assertTrue(command.contains("kill \"\$pid\""))
        assertTrue(command.contains("rm -f \"\$PIDFILE\""))
    }

    @Test
    fun `effective uid parser ignores su diagnostics`() {
        assertEquals(0, MinisdBootstrap.parseEffectiveUid("KernelSU warning\n0\n"))
        assertEquals(10394, MinisdBootstrap.parseEffectiveUid("notice\n10394\nmore"))
        assertEquals(null, MinisdBootstrap.parseEffectiveUid("permission denied"))
    }

    @Test
    fun `shellQuote protects apostrophes`() {
        assertEquals("'a'\"'\"'b'", MinisdBootstrap.shellQuote("a'b"))
    }
}
