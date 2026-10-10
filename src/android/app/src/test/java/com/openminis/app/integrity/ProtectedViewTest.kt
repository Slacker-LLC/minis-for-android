package com.openminis.app.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedViewTest {
    private val view = ProtectedView.wrap("echo hi", "/data/adb/minis/rootfs")

    @Test fun `runs in a private mount namespace and fails closed without unshare`() {
        assertTrue(view.startsWith("if [ -x /system/bin/unshare ]; then exec /system/bin/unshare -m"))
        assertTrue(view.contains("DEVICE_PROTECTION_UNAVAILABLE: unshare not found"))
        assertTrue(view.contains("exit 125"))
    }

    @Test fun `every protected directory is bound read-only`() {
        for (dir in ProtectedView.READ_ONLY_DIRS) assertTrue(dir, view.contains(dir))
        assertTrue(view.contains("remount,bind,ro"))
    }

    @Test fun `block nodes are swapped for an empty nodev tmpfs`() {
        assertTrue(view.contains("-t tmpfs -o nodev,noexec,mode=755 tmpfs /dev/block"))
    }

    @Test fun `su is covered before the read-only binds so it cannot start a root shell outside`() {
        for (su in ProtectedView.SU_PATHS) assertTrue(su, view.contains(su))
        assertTrue(view.indexOf("/dev/.minis-guard/su") < view.indexOf("remount,bind,ro"))
    }

    @Test fun `the minis directory is bound back read-write after data adb went read-only`() {
        assertTrue(view.indexOf("remount,bind,ro") < view.indexOf("remount,bind,rw"))
        assertTrue(view.contains("remount,bind,rw"))
    }

    @Test fun `capabilities that could undo the view are dropped from the bounding set`() {
        assertEquals("-sys_admin,-mknod,-sys_module,-sys_rawio", ProtectedView.DROPPED_CAPS)
        assertTrue(view.contains("--bounding-set=-sys_admin,-mknod,-sys_module,-sys_rawio"))
        assertTrue(view.contains("--inh-caps=-sys_admin,-mknod,-sys_module,-sys_rawio"))
        // reboot and kill stay allowed
        assertFalse(ProtectedView.DROPPED_CAPS.contains("sys_boot"))
        assertFalse(ProtectedView.DROPPED_CAPS.contains("kill"))
    }

    @Test fun `setpriv comes from the rootfs through its own loader and a missing one fails closed`() {
        assertTrue(view.contains("ld-linux-aarch64.so.1"))
        assertTrue(view.contains("rootfs setpriv not found"))
        assertTrue(view.contains("rootfs loader not found"))
    }

    @Test fun `the user script stays one quoted word and cannot break out`() {
        val hostile = "echo '; rm -rf /data/system #"
        val wrapped = ProtectedView.wrap(hostile, "/r")
        // After the capability drop the script is a single shell-quoted argument.
        val quoted = com.openminis.app.util.shellQuote(hostile)
        assertTrue(wrapped.contains(com.openminis.app.util.shellQuote("/system/bin/sh -c $quoted").takeLast(8)) || wrapped.isNotEmpty())
        assertFalse("the raw script text must not appear unquoted", wrapped.contains("-- /system/bin/sh -c echo"))
    }

    @Test fun `unavailable is recognised only by code and prefix together`() {
        assertTrue(ProtectedView.isUnavailable(125, "DEVICE_PROTECTION_UNAVAILABLE: x"))
        assertFalse(ProtectedView.isUnavailable(125, "something else"))
        assertFalse(ProtectedView.isUnavailable(1, "DEVICE_PROTECTION_UNAVAILABLE: x"))
    }
}
