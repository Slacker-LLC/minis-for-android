package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootRunnerTest {
    @Test
    fun `root maintenance uses isolated process group when setsid exists`() {
        val command = DirectRootRunner.buildProcessGroupCommand(
            "echo ok",
            "/data/adb/minis/runtime/runner-test.pid",
        )
        assertTrue(command.contains("command -v setsid"))
        assertTrue(command.contains("exec setsid /system/bin/sh -c"))
        assertTrue(command.contains("echo \$\$ >"))
        assertTrue(command.contains("__minis_status=\$?"))
        assertTrue(command.contains("rm -f --"))
        assertTrue(command.contains("else exec /system/bin/sh -c"))
    }

    @Test
    fun `cleanup validates pid before killing process group`() {
        val command = DirectRootRunner.buildProcessGroupCleanupCommand(
            "/data/adb/minis/runtime/runner-test.pid",
        )
        assertTrue(command.contains("case \"\$PID\" in ''|*[!0-9]*"))
        assertTrue(command.contains("kill -TERM -\$PID"))
        assertTrue(command.contains("kill -KILL -\$PID"))
        assertTrue(command.contains("rm -f --"))
        assertFalse(command.contains("rm -rf"))
    }
}
