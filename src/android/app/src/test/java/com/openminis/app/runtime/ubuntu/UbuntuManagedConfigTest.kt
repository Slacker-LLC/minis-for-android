package com.openminis.app.runtime.ubuntu

import com.openminis.app.sandbox.RootfsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuManagedConfigTest {
    @Test
    fun `managed config command is fixed to an allowlisted file`() {
        val command = UbuntuKernel.buildManagedRootfsConfigWriteCommand(
            "/data/adb/minis/rootfs",
            "etc/pip.conf",
            "index-url = https://example.test/a'b\n",
        )

        assertTrue(command.contains("TARGET='/data/adb/minis/rootfs/etc/pip.conf'"))
        assertTrue(command.contains("TEMP=\"\$TARGET.minis-tmp.\$\$\""))
        assertTrue(command.contains("printf %s 'index-url = https://example.test/a'\"'\"'b"))
        assertTrue(command.contains("chown 0:0 \"\$TEMP\""))
        assertTrue(command.contains("[ ! -L \"\$TARGET\" ] || exit 73"))
        assertTrue(command.contains("[ ! -L \"\$BACKUP\" ] || exit 74"))
        assertFalse(RootfsManager.isManagedRootfsConfig("etc/shadow"))
    }

    @Test
    fun `restore command does not accept arbitrary rootfs paths`() {
        val command = UbuntuKernel.buildManagedRootfsConfigRestoreCommand(
            "/data/adb/minis/rootfs",
            "etc/npmrc",
        )

        assertTrue(command.contains("BACKUP='/data/adb/minis/rootfs/etc/npmrc.bak'"))
        assertTrue(command.contains("mv -f -- \"\$BACKUP\" \"\$TARGET\""))
        assertTrue(command.contains("[ ! -L \"\$BACKUP\" ] || exit 73"))
        assertTrue(command.contains("[ ! -L \"\$TARGET\" ] || exit 74"))
        assertFalse(RootfsManager.isManagedRootfsConfig("etc/passwd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `write command rejects an unlisted rootfs file`() {
        UbuntuKernel.buildManagedRootfsConfigWriteCommand(
            "/data/adb/minis/rootfs",
            "etc/shadow",
            "not allowed",
        )
    }

    @Test
    fun `dns command replaces only the rootfs symlink and writes atomically`() {
        val command = UbuntuKernel.buildResolvConfWriteCommand(
            "/data/adb/minis/rootfs",
            "nameserver 1.1.1.1\n",
        )

        assertTrue(command.contains("if [ -L \"\$TARGET\" ]; then rm -f -- \"\$TARGET\"; fi"))
        assertTrue(command.contains("TEMP=\"\$TARGET.minis-dns-tmp.\$\$\""))
        assertTrue(command.contains("mv -f -- \"\$TEMP\" \"\$TARGET\""))
        assertTrue(command.contains("[ -d '/data/adb/minis/rootfs/etc' ] && [ ! -L '/data/adb/minis/rootfs/etc' ]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dns command rejects NUL content`() {
        UbuntuKernel.buildResolvConfWriteCommand(
            "/data/adb/minis/rootfs",
            "nameserver 1.1.1.1\u0000\n",
        )
    }

    @Test
    fun `mirror configs go where the guest's pip and npm read them`() {
        // The guest runs as the App UID with HOME=/home/minis: /root/.npmrc and /etc/pip/pip.conf are never read.
        assertTrue(RootfsManager.isManagedRootfsConfig(com.openminis.app.ui.sandbox.MirrorCategory.PIP.configPath))
        assertTrue(RootfsManager.isManagedRootfsConfig(com.openminis.app.ui.sandbox.MirrorCategory.NPM.configPath))
        assertTrue(com.openminis.app.ui.sandbox.MirrorCategory.PIP.configPath == "etc/pip.conf")
        assertTrue(com.openminis.app.ui.sandbox.MirrorCategory.NPM.configPath == "etc/npmrc")
        assertFalse(RootfsManager.isManagedRootfsConfig("root/.npmrc"))
        assertFalse(RootfsManager.isManagedRootfsConfig("etc/pip/pip.conf"))
    }

    @Test
    fun `the guest PATH finds tools installed into the persistent home first`() {
        assertTrue(UbuntuKernel.GUEST_PATH.startsWith("/home/minis/.local/bin:"))
        assertTrue(UbuntuKernel.GUEST_PATH.contains(":/usr/bin:"))
    }
}
