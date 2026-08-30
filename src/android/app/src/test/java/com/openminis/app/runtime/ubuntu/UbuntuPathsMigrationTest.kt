package com.openminis.app.runtime.ubuntu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UbuntuPathsMigrationTest {
    @After
    fun reset() {
        UbuntuPaths.resetLayoutForTest()
    }

    @Test
    fun defaultHostPathsAreCanonical() {
        assertEquals("/data/adb/minis/workspace", UbuntuPaths.hostWorkspace.replace('\\', '/'))
        assertEquals("/data/adb/minis/sessions", UbuntuPaths.hostSessions.replace('\\', '/'))
        assertEquals("/data/adb/minis/memory", UbuntuPaths.hostMemory.replace('\\', '/'))
        assertEquals("/data/adb/minis/home", UbuntuPaths.hostHome.replace('\\', '/'))
    }

    @Test
    fun copiesLegacyFilesDirOnceAndSkipsExisting() {
        val filesDir = Files.createTempDirectory("minis-legacy-src").toFile()
        val dest = Files.createTempDirectory("minis-legacy-dst").toFile()
        try {
            File(filesDir, "minis/workspace").mkdirs()
            File(filesDir, "minis/workspace/note.txt").writeText("from-legacy")
            File(filesDir, "minis-global/memory").mkdirs()
            File(filesDir, "minis-global/memory/soul.md").writeText("mem")
            File(filesDir, "minis-sessions/s1/workspace").mkdirs()
            File(filesDir, "minis-sessions/s1/workspace/a.txt").writeText("sess")
            File(dest, "workspace").mkdirs()
            File(dest, "workspace/note.txt").writeText("keep")

            val first = UbuntuPaths.migrateLegacyLayout(filesDir, dest)
            assertEquals(null, first.error)
            assertTrue(first.copied)
            assertEquals("keep", File(dest, "workspace/note.txt").readText())
            assertEquals("mem", File(dest, "memory/soul.md").readText())
            assertEquals("sess", File(dest, "sessions/s1/workspace/a.txt").readText())
            assertTrue(File(dest, "run/legacy-filesdir-migrated").isFile)

            File(filesDir, "minis-global/memory/soul.md").writeText("changed")
            val second = UbuntuPaths.migrateLegacyLayout(filesDir, dest)
            assertTrue(second.skipped)
            assertFalse(second.copied)
            assertEquals("mem", File(dest, "memory/soul.md").readText())
        } finally {
            filesDir.deleteRecursively()
            dest.deleteRecursively()
        }
    }

    @Test
    fun emptyLegacyStillWritesMarkerWhenDestReady() {
        val filesDir = Files.createTempDirectory("minis-legacy-empty").toFile()
        val dest = Files.createTempDirectory("minis-legacy-empty-dst").toFile()
        try {
            val result = UbuntuPaths.migrateLegacyLayout(filesDir, dest)
            assertEquals(null, result.error)
            assertTrue(result.skipped)
            assertTrue(File(dest, "run/legacy-filesdir-migrated").isFile)
        } finally {
            filesDir.deleteRecursively()
            dest.deleteRecursively()
        }
    }
}
