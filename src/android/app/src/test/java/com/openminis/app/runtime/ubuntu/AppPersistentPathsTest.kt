package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppPersistentPathsTest {

    @Test
    fun `production layout is the fixed issue 50 host contract`() {
        assertEquals("/data/adb/minis/workspace", AppPersistentPaths.workspace.path)
        assertEquals("/data/adb/minis/sessions", AppPersistentPaths.sessions.path)
        assertEquals("/data/adb/minis/memory", AppPersistentPaths.memory.path)
        assertEquals("/data/adb/minis/skills", AppPersistentPaths.skills.path)
        assertEquals("/data/adb/minis/shared", AppPersistentPaths.shared.path)
    }

    @Test
    fun `test layout keeps memory skill shared session and workspace under injected root`() {
        val root = Files.createTempDirectory("minis-persistent-paths").toFile()
        try {
            val layout = AppPersistentPaths.at(root)
            assertEquals(File(root, "workspace"), layout.workspace)
            assertEquals(File(root, "sessions"), layout.sessions)
            assertEquals(File(root, "memory"), layout.memory)
            assertEquals(File(root, "skills"), layout.skills)
            assertEquals(File(root, "shared"), layout.shared)

            listOf(layout.workspace, layout.sessions, layout.memory, layout.skills, layout.shared)
                .forEach { dir ->
                    check(dir.mkdirs())
                    val probe = File(dir, "probe")
                    probe.writeText(dir.name)
                    assertEquals(dir.name, probe.readText())
                }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy production memory argument redirects to persistent memory while tests stay injectable`() {
        assertEquals(
            AppPersistentPaths.memory,
            AppPersistentPaths.memoryForRepository(
                File("/data/user/0/com.openminis.app/files/minis-global/memory"),
            ),
        )

        val injected = File("/tmp/minis-global/memory")
        assertSame(injected, AppPersistentPaths.memoryForRepository(injected))
    }
}
