package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
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

    @Test
    fun `legacy migration keeps newer files and resumes past trees it already copied`() {
        val command = UbuntuKernel.buildLegacyMigrationCommand(
            identity = UbuntuKernel.AppIdentity(uid = 10234, gid = 20234),
            mappings = listOf(
                "/data/adb/minis/workspace" to "/data/user/0/pkg/files/minis/workspace",
                "/data/adb/minis/home" to "/data/user/0/pkg/files/minis/home",
            ),
        )
        assertTrue("a file already written in the new tree is not overwritten", command.contains("cp -a -n "))
        assertFalse(command.contains("cp -a \""))
        assertTrue(command.contains("PROGRESS='/data/user/0/pkg/files/minis/.legacy-migration'"))
        assertTrue("a progress dir planted as a link is refused", command.contains("[ ! -L \"\$PROGRESS\" ]"))
        assertTrue("a finished tree is skipped on retry", command.contains("[ ! -e \"\$DONE\" ] || return 0"))
        assertTrue("a progress file planted as a link is refused", command.contains("[ ! -L \"\$DONE\" ] || return 76"))
        assertTrue(command.contains("copy_tree '/data/adb/minis/workspace' '/data/user/0/pkg/files/minis/workspace' 0-workspace"))
        assertTrue(command.contains("copy_tree '/data/adb/minis/home' '/data/user/0/pkg/files/minis/home' 1-home"))
        // The destination link check still guards a tree that has not been copied yet.
        assertTrue(command.contains("[ -z \"\$LINKS\" ] || return 75"))
    }

    /** Runs the destination link check of the migration script on a real shell, against a source and destination tree. */
    private fun linkCheck(source: java.io.File, destination: java.io.File): Int {
        val script = "check() { SRC='${source.path}'; DST='${destination.path}'; " +
            UbuntuKernel.LEGACY_LINK_CHECK.replace("/system/bin/find", "find") + "return 0; }; check"
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor()
    }

    @Test
    fun `a link the legacy copy itself left behind does not block the retry, any other link does`() {
        val root = java.nio.file.Files.createTempDirectory("minis-link-check").toFile()
        try {
            val source = root.resolve("src").apply { mkdirs() }
            val destination = root.resolve("dst").apply { mkdirs() }
            fun link(dir: java.io.File, name: String, target: String) =
                java.nio.file.Files.createSymbolicLink(dir.resolve(name).toPath(), java.nio.file.Paths.get(target))
            try {
                link(source, "tool", "bin/real")
            } catch (error: Exception) {
                org.junit.Assume.assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            assertEquals("no links at all", 0, linkCheck(source, destination))

            link(destination, "tool", "bin/real")
            assertEquals("the copy of a source link", 0, linkCheck(source, destination))

            link(destination, "planted", "/data/adb")
            assertEquals("a link the source never had", 75, linkCheck(source, destination))

            destination.resolve("planted").delete()
            destination.resolve("tool").delete()
            link(destination, "tool", "/data/adb")
            assertEquals("a source link retargeted to somewhere else", 75, linkCheck(source, destination))
        } finally {
            root.deleteRecursively()
        }
    }
}
