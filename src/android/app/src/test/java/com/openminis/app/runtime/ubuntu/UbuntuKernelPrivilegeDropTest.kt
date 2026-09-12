package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuKernelPrivilegeDropTest {
    @Test
    fun `interactive root shell preserves the forkpty controlling terminal`() {
        val child = "exec /system/bin/unshare -m /system/bin/sh -c 'echo ready'"

        val interactive = UbuntuKernel.buildRootShellOuterCommand(child, interactive = true)
        assertTrue(interactive.startsWith("exec /system/bin/sh -c "))
        assertFalse(interactive.contains("setsid"))

        val nonInteractive = UbuntuKernel.buildRootShellOuterCommand(child, interactive = false)
        assertTrue(nonInteractive.contains("exec /system/bin/setsid /system/bin/sh -c"))
    }

    @Test
    fun `guest launch forbids privilege regain before exec`() {
        val command = UbuntuKernel.buildGuestSetprivExec(
            uid = 10234,
            gid = 20234,
            envArgs = "'HOME=/home/minis'",
            shellArgs = "/bin/bash --noprofile --norc",
        )

        assertTrue(command.contains("--reuid=10234 --regid=20234 --clear-groups"))
        assertFalse(command.contains("--reuid=10234 --regid=10234"))
        assertTrue(command.contains("--inh-caps=-all --ambient-caps=-all --bounding-set=-all"))
        assertTrue(command.contains("--no-new-privs"))
        assertTrue(command.indexOf("--no-new-privs") < command.indexOf("/usr/bin/env -i"))
        assertFalse(command.contains("--keep-groups"))
    }

    @Test
    fun `guest identity follows actual app uid and gid without widening privileges`() {
        val commands = UbuntuKernel.buildGuestIdentityCommands(
            rootfs = "/data/adb/minis/rootfs",
            identity = UbuntuKernel.AppIdentity(uid = 10418, gid = 10419),
        ).joinToString("\n")

        assertTrue(commands.contains("minis:x:10418:10419:Minis:/home/minis:/bin/bash"))
        assertTrue(commands.contains("minis:x:10419:"))
        assertTrue(commands.contains("sed -i '/^minis:/d'"))
        assertTrue(commands.contains("[ -f '/data/adb/minis/rootfs/etc/passwd' ]"))
        assertFalse(commands.contains("10000"))
    }

    @Test
    fun `legacy migration rejects destination symlinks before root copy`() {
        val command = UbuntuKernel.buildLegacyMigrationCommand(
            identity = UbuntuKernel.AppIdentity(uid = 10234, gid = 20234),
            mappings = listOf(
                "/data/adb/minis/workspace" to "/data/user/0/pkg/files/minis/workspace",
            ),
        )

        assertTrue(command.contains("if [ ! -d '/data/user/0/pkg/files/minis' ]"))
        assertTrue(command.contains("legacy migration parent missing"))
        assertTrue(command.contains("legacy migration parent is symlink"))
        assertTrue(command.contains("[ ! -L \"\$DST\" ] || return 73"))
        assertTrue(command.contains("LINKS=\$(/system/bin/find \"\$DST\" -type l"))
        assertTrue(command.contains("[ -z \"\$LINKS\" ] || return 75"))
    }
}
