package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetworkProxyTest {
    @Test
    fun `proxy uri embeds fixed user and opaque lowercase token`() {
        val token = "0123456789abcdef".repeat(4)
        assertEquals(
            "http://minis:$token@127.0.0.1:18787",
            RootNetworkProxy.buildProxyUri(token),
        )
    }

    @Test
    fun `proxy uri rejects non canonical tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootNetworkProxy.buildProxyUri("A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootNetworkProxy.buildProxyUri("0".repeat(63))
        }
    }

    @Test
    fun `ready handshake accepts only the managed child announcement`() {
        assertTrue(RootNetworkProxy.isReadyAnnouncement("READY 127.0.0.1:18787"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("READY 0.0.0.0:18787"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("READY 127.0.0.1:18787 extra"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("ready 127.0.0.1:18787"))
    }

    @Test
    fun `proxy launch owns an isolated process group and pid marker`() {
        val command = RootNetworkProxy.buildLaunchCommand(
            binaryPath = "/data/app/libminisnetproxy.so",
            pidFilePath = "/data/user/0/com.openminis.app/cache/proxy.pid",
            uid = 10234,
        )
        assertTrue(command.contains("setsid"))
        assertTrue(command.contains("echo \\$\\$"))
        assertTrue(command.contains("proxy.pid"))
        assertTrue(command.contains("chown 10234:10234"))
        assertTrue(command.contains("--auth-stdin"))
        assertFalse(command.contains("0123456789abcdef"))
    }

    @Test
    fun `proxy cleanup terminates the whole managed process group`() {
        val command = RootNetworkProxy.buildCleanupCommand("/data/user/0/com.openminis.app/cache/proxy.pid")
        assertTrue(command.contains("kill -TERM -\\$PID"))
        assertTrue(command.contains("kill -KILL -\\$PID"))
        assertTrue(command.contains("rm -f --"))
    }
}
