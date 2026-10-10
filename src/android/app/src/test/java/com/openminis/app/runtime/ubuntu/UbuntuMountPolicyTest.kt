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

    @Test
    fun `the host pty directory is mounted after the device bind, and only into a real directory`() {
        val commands = UbuntuMountPolicy.setupCommands()
        val dev = commands.indexOfFirst { it.contains("/system/bin/mount -o bind /dev \"") }
        val pts = commands.indexOfFirst { it.contains("mount -o bind /dev/pts") }
        assertTrue("devpts must come after /dev or the bind would hide it", pts > dev && dev >= 0)
        val command = commands[pts]
        assertTrue("never follows a link planted at the target", command.contains("! -L \"\$ROOTFS/dev/pts\""))
        assertTrue("a device without devpts keeps working", command.contains("|| true"))
        assertFalse(command.contains("--rbind"))
    }
}
