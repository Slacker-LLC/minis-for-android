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
    fun legacySourcesMapToBrokerTargetsWithoutCanonicalDestinationFiles() {
        val filesDir = Files.createTempDirectory("minis-legacy-src").toFile()
        try {
            val roots = UbuntuPaths.legacyMigrationRoots(filesDir)
            assertEquals(
                listOf("workspace", "memory", "skills", "shared", "home"),
                roots.map { it.target },
            )
            assertEquals(
                listOf(
                    "minis/workspace",
                    "minis-global/memory",
                    "minis-global/skills",
                    "minis-global/shared",
                    "minis/home",
                ),
                roots.map { it.source.relativeTo(filesDir).path.replace('\\', '/') },
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun sessionIdsRejectTraversalAndControlCharacters() {
        assertTrue(UbuntuPaths.isSafeSessionId("session-a_1.2"))
        assertFalse(UbuntuPaths.isSafeSessionId("../outside"))
        assertFalse(UbuntuPaths.isSafeSessionId("session/child"))
        assertFalse(UbuntuPaths.isSafeSessionId("session\u0000id"))
    }
}
