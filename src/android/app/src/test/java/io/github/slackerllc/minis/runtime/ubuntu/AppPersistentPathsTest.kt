package io.github.slackerllc.minis.runtime.ubuntu

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
        assertEquals("/data/adb/minis/home", AppPersistentPaths.home.path)
    }

    @Test
    fun `test layout stays under injected root`() {
        val root = Files.createTempDirectory("minis-persistent-paths").toFile()
        try {
            val layout = AppPersistentPaths.at(root)
            val expected = listOf("workspace", "sessions", "memory", "skills", "shared", "home")
            val actual = listOf(layout.workspace, layout.sessions, layout.memory, layout.skills, layout.shared, layout.home)
            assertEquals(expected, actual.map { it.name })
            actual.forEach { dir ->
                check(dir.mkdirs())
                File(dir, "probe").writeText(dir.name)
                assertEquals(dir.name, File(dir, "probe").readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy android memory argument redirects while arbitrary tests stay injectable`() {
        assertEquals(
            AppPersistentPaths.memory,
            AppPersistentPaths.memoryForRepository(
                File("/data/user/0/io.github.slackerllc.minis/files/minis-global/memory"),
            ),
        )
        val injected = File("/tmp/minis-global/memory")
        assertSame(injected, AppPersistentPaths.memoryForRepository(injected))
    }
}
