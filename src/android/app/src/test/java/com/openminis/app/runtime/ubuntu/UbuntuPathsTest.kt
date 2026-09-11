package com.openminis.app.runtime.ubuntu

import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption

class UbuntuPathsTest {
    @Test
    fun `workspace and var minis aliases map to host`() {
        assertEquals(
            "/data/adb/minis/workspace/x.xlsx",
            UbuntuPaths.resolveGuest("/workspace/x.xlsx")!!.androidPath(),
        )
        assertEquals(
            "/data/adb/minis/workspace/attachments/a.png",
            UbuntuPaths.resolveGuest("/var/minis/attachments/a.png")!!.androidPath(),
        )
        assertEquals(
            "/data/adb/minis/memory/notes.md",
            UbuntuPaths.resolveGuest("/memory/notes.md")!!.androidPath(),
        )
    }

    @Test
    fun `resolveHostPath uses bind mounts`() {
        UbuntuPaths.bindMounts["/mnt/docs"] = "/storage/emulated/0/Documents"
        try {
            assertEquals(
                "/storage/emulated/0/Documents/a.txt",
                UbuntuPaths.resolveHostPath("/mnt/docs/a.txt")!!.androidPath(),
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
        try {
            try {
                Files.createSymbolicLink(root.resolve("escape"), outside)
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            UbuntuPaths.bindMounts["/mnt/test"] = root.toString()
            assertNull(UbuntuPaths.resolveHostPath("/mnt/test/escape/secret.txt"))
        } finally {
            UbuntuPaths.bindMounts.remove("/mnt/test")
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session paths isolate identical guest names and clean up independently`() {
        val filesDir = Files.createTempDirectory("minis-session-paths").toFile()
        val sessionsRoot = java.io.File(filesDir, "minis-sessions")
        try {
            val first = UbuntuPaths.resolveSessionPath(
                sessionsRoot,
                "session-a",
                "/var/minis/workspace/report.txt",
            )!!
            val second = UbuntuPaths.resolveSessionPath(
                sessionsRoot,
                "session-b",
                "/var/minis/workspace/report.txt",
            )!!
            assertTrue(first.absolutePath.contains("minis-sessions${java.io.File.separator}session-a"))
            assertTrue(second.absolutePath.contains("minis-sessions${java.io.File.separator}session-b"))
            assertTrue(first.absolutePath != second.absolutePath)
            first.writeText("a")
            second.writeText("b")
            assertEquals("a", first.readText())
            assertEquals("b", second.readText())

            val attachment = UbuntuPaths.resolveSessionPath(
                sessionsRoot,
                "session-a",
                "/var/minis/attachments/photo.png",
            )!!
            assertTrue(attachment.path.contains("attachments"))
            assertTrue(!attachment.path.contains("workspace${java.io.File.separator}attachments"))
            assertEquals(
                attachment.canonicalFile,
                UbuntuPaths.resolveSessionPath(
                    sessionsRoot,
                    "session-a",
                    "/workspace/attachments/photo.png",
                )!!.canonicalFile,
            )
            assertEquals(
                attachment.canonicalFile,
                UbuntuPaths.resolveSessionPath(
                    sessionsRoot,
                    "session-a",
                    "/var/minis/workspace/attachments/photo.png",
                )!!.canonicalFile,
            )

            assertTrue(first.exists())
            assertTrue(second.exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `session directory setup rejects symlinked bind source`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-symlink-source")
        val outside = Files.createTempDirectory("minis-session-symlink-outside")
        try {
            val session = sessionsRoot.resolve("session-a")
            Files.createDirectories(session)
            try {
                Files.createSymbolicLink(session.resolve("workspace"), outside)
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            assertNull(UbuntuPaths.ensureSessionDirsAt(sessionsRoot.toFile(), "session-a"))
        } finally {
            sessionsRoot.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session directory setup rejects symlinked nested bind target`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-symlink-target")
        val outside = Files.createTempDirectory("minis-session-target-outside")
        try {
            val session = UbuntuPaths.ensureSessionDirsAt(sessionsRoot.toFile(), "session-a")!!
            val nested = File(File(session, "workspace"), "attachments")
            assertTrue(nested.delete())
            try {
                Files.createSymbolicLink(nested.toPath(), outside)
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            assertNull(UbuntuPaths.ensureSessionDirsAt(sessionsRoot.toFile(), "session-a"))
        } finally {
            sessionsRoot.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session cleanup deletes symlink entry without following target`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-delete-link")
        val outside = Files.createTempDirectory("minis-session-delete-outside")
        val marker = outside.resolve("keep.txt")
        Files.writeString(marker, "keep")
        val link = sessionsRoot.resolve("session-a")
        try {
            try {
                Files.createSymbolicLink(link, outside)
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            assertTrue(UbuntuPaths.deleteSessionAt(sessionsRoot.toFile(), "session-a"))
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(marker))
        } finally {
            Files.deleteIfExists(link)
            outside.toFile().deleteRecursively()
            sessionsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session cleanup does not follow nested symlinks`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-delete-nested")
        val outside = Files.createTempDirectory("minis-session-delete-nested-outside")
        val marker = outside.resolve("keep.txt")
        Files.writeString(marker, "keep")
        try {
            val session = UbuntuPaths.ensureSessionDirsAt(sessionsRoot.toFile(), "session-a")!!
            val link = File(session, "workspace/escape").toPath()
            try {
                Files.createSymbolicLink(link, outside)
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            assertTrue(UbuntuPaths.deleteSessionAt(sessionsRoot.toFile(), "session-a"))
            assertTrue(Files.exists(marker))
        } finally {
            outside.toFile().deleteRecursively()
            sessionsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session resolver rejects traversal and invalid ids`() {
        val filesDir = Files.createTempDirectory("minis-session-invalid").toFile()
        try {
            assertNull(UbuntuPaths.resolveSessionPath(filesDir, "../escape", "/workspace/a"))
            assertNull(UbuntuPaths.resolveSessionPath(filesDir, "session", "/workspace/../a"))
            assertNull(UbuntuPaths.resolveSessionPath(filesDir, "会话", "/workspace/a"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun File.androidPath(): String =
        path.replace('\\', '/').replace(Regex("^[A-Za-z]:(?=/)"), "")

    private fun symlinksUnavailable(error: Throwable): Boolean =
        error is UnsupportedOperationException ||
            error is SecurityException ||
            (System.getProperty("os.name").orEmpty().startsWith("Windows") && error is FileSystemException)
}
