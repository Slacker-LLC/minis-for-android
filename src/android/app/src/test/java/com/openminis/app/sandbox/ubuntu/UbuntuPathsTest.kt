package com.openminis.app.sandbox.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UbuntuPathsTest {
    @Test
    fun `workspace and var minis aliases map to host`() {
        assertEquals(
            "/data/adb/minis/workspace/x.xlsx",
            UbuntuPaths.resolveGuest("/workspace/x.xlsx")!!.path.replace('\\', '/'),
        )
        assertEquals(
            "/data/adb/minis/workspace/attachments/a.png",
            UbuntuPaths.resolveGuest("/var/minis/attachments/a.png")!!.path.replace('\\', '/'),
        )
        assertEquals(
            "/data/adb/minis/memory/notes.md",
            UbuntuPaths.resolveGuest("/memory/notes.md")!!.path.replace('\\', '/'),
        )
    }

    @Test
    fun `resolveHostPath uses bind mounts`() {
        UbuntuPaths.bindMounts["/mnt/docs"] = "/storage/emulated/0/Documents"
        try {
            assertEquals(
                "/storage/emulated/0/Documents/a.txt",
                UbuntuPaths.resolveHostPath("/mnt/docs/a.txt")!!.path.replace('\\', '/'),
            )
        } finally {
            UbuntuPaths.bindMounts.remove("/mnt/docs")
        }
    }

    @Test
    fun `rejects escape and unknown prefixes`() {
        assertNull(UbuntuPaths.resolveGuest("/workspace/../policy"))
        assertNull(UbuntuPaths.resolveGuest("/etc/passwd"))
        assertNull(UbuntuPaths.resolveGuest("/data/adb/minis/policy/x"))
    }
}
