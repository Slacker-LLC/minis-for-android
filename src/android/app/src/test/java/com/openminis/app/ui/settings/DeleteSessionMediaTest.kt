package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeleteSessionMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun media(vararg paths: String): File {
        val root = tmp.newFolder("media")
        for (p in paths) File(root, p).apply { parentFile.mkdirs(); writeText("x") }
        return root
    }

    @Test
    fun `a session's media folders go and other sessions' stay`() {
        val root = media("2026/10/05/s1/a.png", "2026/10/06/s1/b.png", "2026/10/06/s2/c.png")
        assertTrue(deleteSessionMedia(root, "s1"))
        assertFalse(File(root, "2026/10/05/s1").exists())
        assertFalse(File(root, "2026/10/06/s1").exists())
        assertEquals("x", File(root, "2026/10/06/s2/c.png").readText())
    }

    @Test
    fun `a missing media directory is nothing to delete`() {
        assertTrue(deleteSessionMedia(File(tmp.root, "none"), "s1"))
    }

    @Test
    fun `a folder that cannot be removed is reported, not hidden`() {
        val root = media("2026/10/06/s1/a.png")
        val parent = File(root, "2026/10/06/s1")
        // Without write permission on the folder holding the file, the file cannot be unlinked.
        parent.setWritable(false)
        try {
            assumeFalse(
                "running as a user that ignores directory permissions",
                runCatching { File(parent, "probe").createNewFile() }.getOrDefault(false),
            )
            assertFalse(deleteSessionMedia(root, "s1"))
            assertTrue("what could not be removed is still there", File(parent, "a.png").exists())
        } finally {
            parent.setWritable(true)
        }
    }
}
