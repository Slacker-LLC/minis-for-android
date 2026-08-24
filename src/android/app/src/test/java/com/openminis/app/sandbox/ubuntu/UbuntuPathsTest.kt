package com.openminis.app.sandbox.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
        // relative path falls back to workspace (linux.file.* contract)
        run {
            val p = UbuntuPaths.resolveHostPath("e2e/ok.txt")
            assertTrue(p != null && p!!.path.replace('\\', '/').endsWith("/workspace/e2e/ok.txt"))
            val q = UbuntuPaths.resolveHostPath("ok.txt")
            assertTrue(q != null && q!!.path.replace('\\', '/').endsWith("/workspace/ok.txt"))
            assertNull(UbuntuPaths.resolveHostPath("../escape.txt"))
        }
    }

    @Test
    fun `rejects escape and unknown prefixes`() {
        assertNull(UbuntuPaths.resolveGuest("/workspace/../policy"))
        assertNull(UbuntuPaths.resolveGuest("/etc/passwd"))
        assertNull(UbuntuPaths.resolveGuest("/data/adb/minis/policy/x"))
    }

    @Test
    fun `bind mount symlink cannot escape its host root`() {
        val root = Files.createTempDirectory("minis-path-root")
        val outside = Files.createTempDirectory("minis-path-outside")
        Files.createSymbolicLink(root.resolve("escape"), outside)
        UbuntuPaths.bindMounts["/mnt/test"] = root.toString()
        try {
            assertNull(UbuntuPaths.resolveHostPath("/mnt/test/escape/secret.txt"))
        } finally {
            UbuntuPaths.bindMounts.remove("/mnt/test")
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
