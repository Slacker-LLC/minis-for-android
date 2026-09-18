package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuMountPolicyTest {
    @Test
    fun `guest mounts match upstream pseudo filesystem surface`() {
        val commands = UbuntuMountPolicy.setupCommands()
        assertTrue(commands.any { it.contains("/system/bin/mount -o bind /dev") })
        assertTrue(commands.any { it.contains("/system/bin/mount -o bind /proc") })
        assertTrue(commands.any { it.contains("/system/bin/mount -o bind /sys") })
        assertTrue(commands.any { it.contains("test ! -L \"\$ROOTFS\"") })
        assertTrue(commands.any { it.contains("target=\"\$ROOTFS/\$mountpoint\"") })
        assertFalse(commands.any { it.contains("hidepid=") })
        assertFalse(commands.any { it.contains("mount -t tmpfs") })
        assertFalse(commands.any { it.contains("--rbind") || it.contains("--make-rslave") })
    }

    @Test
    fun `broad device mount is never modified through rootfs entries`() {
        val commands = UbuntuMountPolicy.setupCommands()
        assertFalse(commands.any { it.contains("rm -f") && it.contains("\$ROOTFS/dev") })
        assertFalse(commands.any { it.contains("ln -s") && it.contains("\$ROOTFS/dev") })
    }
}
