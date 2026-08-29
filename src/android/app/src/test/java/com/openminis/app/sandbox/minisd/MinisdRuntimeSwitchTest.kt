package com.openminis.app.sandbox.minisd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisdRuntimeSwitchTest {
    @Test
    fun `runtime switch shutdown validates child and watchdog before kill`() {
        val command = MinisdBootstrap.runtimeSwitchShutdownCommand(
            "/data/user/0/com.openminis.app/files/minis/minisd.sock",
        )

        assertTrue(command.contains("refusing to kill unrecognized pidfile owner"))
        assertTrue(command.contains("unrecognized minisd parent"))
        assertTrue(command.contains("*minisd*--watchdog*"))
        assertTrue(command.contains("kill \"\$ppid\""))
        assertTrue(command.contains("kill \"\$pid\""))
    }

    @Test
    fun `runtime switch cleanup only removes transient sockets and pidfile`() {
        val command = MinisdBootstrap.runtimeSwitchShutdownCommand(
            "/data/user/0/com.openminis.app/files/minis/minisd.sock",
        )

        assertTrue(command.contains("/data/adb/minis/run/minisd.pid"))
        assertTrue(command.contains("/data/adb/minis/run/minisd.sock"))
        assertFalse(command.contains("rm -rf /data/adb/minis"))
        assertFalse(command.contains("/data/adb/minis/workspace"))
        assertFalse(command.contains("/data/adb/minis/memory"))
        assertFalse(command.contains("/data/adb/minis/home"))
    }
}
