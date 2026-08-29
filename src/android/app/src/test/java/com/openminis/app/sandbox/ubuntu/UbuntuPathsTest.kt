package com.openminis.app.sandbox.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UbuntuPathsTest {
    @Test
    fun `persistent host paths are fixed and never filesDir based`() {
        assertEquals("/data/adb/minis/workspace", UbuntuPaths.hostWorkspace)
        assertEquals("/data/adb/minis/sessions", UbuntuPaths.hostSessions)
        assertEquals("/data/adb/minis/memory", UbuntuPaths.hostMemory)
        assertEquals("/data/adb/minis/skills", UbuntuPaths.hostSkills)
        assertEquals("/data/adb/minis/shared", UbuntuPaths.hostShared)
        assertEquals("/data/adb/minis/home", UbuntuPaths.hostHome)
        listOf(
            UbuntuPaths.hostWorkspace,
            UbuntuPaths.hostSessions,
            UbuntuPaths.hostMemory,
            UbuntuPaths.hostSkills,
            UbuntuPaths.hostShared,
            UbuntuPaths.hostHome,
        ).forEach { path ->
            assertFalse(path.contains("/data/user/"))
            assertFalse(path.contains("/files/"))
        }
    }

    @Test
    fun `workspace home and global aliases map to distinct persistent sources`() {
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
        assertEquals(
            "/data/adb/minis/home/.profile",
            UbuntuPaths.resolveGuest("/home/minis/.profile")!!.path.replace('\\', '/'),
        )
        assertTrue(UbuntuPaths.hostHome != UbuntuPaths.hostWorkspace)
        assertEquals("/home/minis", UbuntuPaths.GUEST_HOME)
        assertEquals("/workspace", UbuntuPaths.GUEST_WORKSPACE)
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
        val p = UbuntuPaths.resolveHostPath("e2e/ok.txt")
        assertTrue(p != null && p.path.replace('\\', '/').endsWith("/workspace/e2e/ok.txt"))
        val q = UbuntuPaths.resolveHostPath("ok.txt")
        assertTrue(q != null && q.path.replace('\\', '/').endsWith("/workspace/ok.txt"))
        assertNull(UbuntuPaths.resolveHostPath("../escape.txt"))
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

    @Test
    fun `session paths isolate identical guest names and delete without orphans`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-paths").toFile()
        try {
            val first = UbuntuPaths.resolveSessionPathAt(
                sessionsRoot,
                "session-a",
                "/var/minis/workspace/report.txt",
            )!!
            val second = UbuntuPaths.resolveSessionPathAt(
                sessionsRoot,
                "session-b",
                "/var/minis/workspace/report.txt",
            )!!
            assertTrue(first.absolutePath.contains("session-a${java.io.File.separator}workspace"))
            assertTrue(second.absolutePath.contains("session-b${java.io.File.separator}workspace"))
            assertTrue(first.absolutePath != second.absolutePath)
            first.writeText("a")
            second.writeText("b")
            assertEquals("a", first.readText())
            assertEquals("b", second.readText())

            val attachment = UbuntuPaths.resolveSessionPathAt(
                sessionsRoot,
                "session-a",
                "/var/minis/attachments/photo.png",
            )!!
            assertTrue(attachment.path.contains("attachments"))
            assertFalse(attachment.path.contains("workspace${java.io.File.separator}attachments"))
            assertEquals(
                attachment.canonicalFile,
                UbuntuPaths.resolveSessionPathAt(
                    sessionsRoot,
                    "session-a",
                    "/workspace/attachments/photo.png",
                )!!.canonicalFile,
            )
            assertEquals(
                attachment.canonicalFile,
                UbuntuPaths.resolveSessionPathAt(
                    sessionsRoot,
                    "session-a",
                    "/var/minis/workspace/attachments/photo.png",
                )!!.canonicalFile,
            )

            val firstRoot = java.io.File(sessionsRoot, "session-a")
            val secondRoot = java.io.File(sessionsRoot, "session-b")
            assertTrue(UbuntuPaths.deleteSessionFilesAt(sessionsRoot, "session-a"))
            assertFalse(firstRoot.exists())
            assertTrue(secondRoot.exists())
            assertTrue(second.exists())
            assertEquals(listOf("session-b"), sessionsRoot.list()?.sorted())
        } finally {
            sessionsRoot.deleteRecursively()
        }
    }

    @Test
    fun `session resolver rejects traversal invalid ids and symlink mounts`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-invalid").toFile()
        val outside = Files.createTempDirectory("minis-session-outside")
        try {
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "../escape", "/workspace/a"))
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "session", "/workspace/../a"))
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "会话", "/workspace/a"))

            val session = UbuntuPaths.ensureSessionDirsAt(sessionsRoot, "symlink-session")!!
            val workspace = session.toPath().resolve("workspace")
            workspace.toFile().deleteRecursively()
            Files.createSymbolicLink(workspace, outside)
            assertNull(
                UbuntuPaths.resolveSessionPathAt(
                    sessionsRoot,
                    "symlink-session",
                    "/workspace/secret.txt",
                ),
            )
        } finally {
            sessionsRoot.deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
