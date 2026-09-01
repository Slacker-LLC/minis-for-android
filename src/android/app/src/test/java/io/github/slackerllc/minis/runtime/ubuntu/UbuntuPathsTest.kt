package io.github.slackerllc.minis.runtime.ubuntu

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
            UbuntuPaths.hostWorkspace, UbuntuPaths.hostSessions, UbuntuPaths.hostMemory,
            UbuntuPaths.hostSkills, UbuntuPaths.hostShared, UbuntuPaths.hostHome,
        ).forEach { path ->
            assertFalse(path.contains("/data/user/"))
            assertFalse(path.contains("/files/"))
        }
    }

    @Test
    fun `workspace home and global aliases map to distinct persistent sources`() {
        assertEquals("/data/adb/minis/workspace/x.xlsx", UbuntuPaths.resolveGuest("/workspace/x.xlsx")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/workspace/attachments/a.png", UbuntuPaths.resolveGuest("/var/minis/attachments/a.png")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/memory/notes.md", UbuntuPaths.resolveGuest("/memory/notes.md")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/skills/tool/SKILL.md", UbuntuPaths.resolveGuest("/skills/tool/SKILL.md")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/shared/export.zip", UbuntuPaths.resolveGuest("/shared/export.zip")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/home/.profile", UbuntuPaths.resolveGuest("/home/minis/.profile")!!.path.replace('\\', '/'))
        assertEquals("/home/minis", UbuntuPaths.GUEST_HOME)
        assertEquals("/workspace", UbuntuPaths.GUEST_WORKSPACE)
    }

    @Test
    fun `session globals stay on persistent roots`() {
        assertEquals("/data/adb/minis/memory/global.md", UbuntuPaths.resolveSessionHostPath("session-a", "/memory/global.md")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/skills/demo/SKILL.md", UbuntuPaths.resolveSessionHostPath("session-a", "/skills/demo/SKILL.md")!!.path.replace('\\', '/'))
        assertEquals("/data/adb/minis/shared/result.txt", UbuntuPaths.resolveSessionHostPath("session-a", "/shared/result.txt")!!.path.replace('\\', '/'))
    }

    @Test
    fun `resolveHostPath rejects traversal and bind symlink escape`() {
        UbuntuPaths.bindMounts["/mnt/docs"] = "/storage/emulated/0/Documents"
        try {
            assertEquals("/storage/emulated/0/Documents/a.txt", UbuntuPaths.resolveHostPath("/mnt/docs/a.txt")!!.path.replace('\\', '/'))
        } finally {
            UbuntuPaths.bindMounts.remove("/mnt/docs")
        }
        assertNull(UbuntuPaths.resolveHostPath("../escape.txt"))
        assertNull(UbuntuPaths.resolveGuest("/workspace/../policy"))
        assertNull(UbuntuPaths.resolveGuest("/data/adb/minis/policy/x"))

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
    fun `session paths isolate and reject unsafe ids and mounts`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-paths").toFile()
        val outside = Files.createTempDirectory("minis-session-outside")
        try {
            val first = UbuntuPaths.resolveSessionPathAt(sessionsRoot, "session-a", "/workspace/report.txt")!!
            val second = UbuntuPaths.resolveSessionPathAt(sessionsRoot, "session-b", "/workspace/report.txt")!!
            assertTrue(first.absolutePath != second.absolutePath)
            first.writeText("a")
            second.writeText("b")
            assertEquals("a", first.readText())
            assertEquals("b", second.readText())
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "../escape", "/workspace/a"))
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "session", "/workspace/../a"))
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "会话", "/workspace/a"))

            val session = UbuntuPaths.ensureSessionDirsAt(sessionsRoot, "symlink-session")!!
            val workspace = session.toPath().resolve("workspace")
            workspace.toFile().deleteRecursively()
            Files.createSymbolicLink(workspace, outside)
            assertNull(UbuntuPaths.resolveSessionPathAt(sessionsRoot, "symlink-session", "/workspace/secret.txt"))

            assertTrue(UbuntuPaths.deleteSessionFilesAt(sessionsRoot, "session-a"))
            assertFalse(java.io.File(sessionsRoot, "session-a").exists())
            assertTrue(java.io.File(sessionsRoot, "session-b").exists())
        } finally {
            sessionsRoot.deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
