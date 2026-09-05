package com.openminis.app.runtime.minisd

import com.openminis.app.sandbox.RootfsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsDnsRefreshTest {

    @Test
    fun testFormatResolvConfFallback() {
        val content = RootfsManager.formatResolvConf(emptyList())
        assertTrue(content.contains("nameserver 223.5.5.5"))
        assertTrue(content.contains("nameserver 1.1.1.1"))
        assertTrue(content.contains("nameserver 8.8.8.8"))
    }

    @Test
    fun testFormatResolvConfCustomServers() {
        val servers = listOf("10.0.0.1", "10.0.0.2")
        val content = RootfsManager.formatResolvConf(servers)
        assertEquals("nameserver 10.0.0.1\nnameserver 10.0.0.2\n", content)
    }

    @Test
    fun testWatchdogCommandContainsResolvConfAndPermissionsRemediation() {
        val cmd = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/adb/minis/run/minisd.sock",
            policyJson = "{\"methods\":{}}",
            forceRestart = false,
            appUid = 10392,
        )

        // Checklist A: resolv.conf fallback population & 644 permission
        assertTrue(cmd.contains("/data/adb/minis/rootfs/etc/resolv.conf"))
        assertTrue(cmd.contains("chmod 644 /data/adb/minis/rootfs/etc/resolv.conf"))
        assertTrue(cmd.contains("223.5.5.5"))

        // Checklist D: minis-config & minis-model-use & opt parent directory 755 permissions
        assertTrue(cmd.contains("chmod 755 /data/adb/minis/rootfs/opt /data/adb/minis/rootfs/opt/minis /data/adb/minis/rootfs/opt/minis/bin"))
        assertTrue(cmd.contains("chmod 755 /data/adb/minis/rootfs/opt/minis/bin/minis-config /data/adb/minis/rootfs/opt/minis/bin/minis-model-use"))

        // Checklist E: /data/adb/minis/home fast-path ownership by appUid
        assertTrue(cmd.contains("chown 10392:10392 /data/adb/minis/home"))
        assertTrue(cmd.contains("for d in .cache .local .config; do [ -d \"/data/adb/minis/home/\$d\" ] && chown -R 10392:10392 \"/data/adb/minis/home/\$d\""))
        assertTrue(cmd.contains("chmod 755 /data/adb/minis/home"))
    }

    @Test
    fun testWatchdogCommandWithoutAppUidSkipsHomeChown() {
        val cmd = MinisdBootstrap.watchdogCommand(
            appSocket = "/data/adb/minis/run/minisd.sock",
            policyJson = "{\"methods\":{}}",
            forceRestart = false,
            appUid = 0,
        )

        assertFalse(cmd.contains("chown 0:0 /data/adb/minis/home"))
    }

    @Test
    fun testMinisdProtocolUbuntuRefreshDns() {
        val req = MinisdProtocol.ubuntuRefreshDns(id = 42, nameservers = listOf("1.1.1.1", "8.8.8.8"))
        assertEquals(42L, req.id)
        assertEquals("ubuntu.refreshDns", req.method)
        val arr = req.params.getJSONArray("nameservers")
        assertEquals(2, arr.length())
        assertEquals("1.1.1.1", arr.getString(0))
        assertEquals("8.8.8.8", arr.getString(1))
    }
}
