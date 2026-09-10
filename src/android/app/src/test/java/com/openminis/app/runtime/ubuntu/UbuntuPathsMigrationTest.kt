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
    fun defaultHostPathsExposeLegacyMigrationSourcesBeforeInitialization() {
        assertEquals(UbuntuPaths.LEGACY_WORKSPACE, UbuntuPaths.hostWorkspace.replace('\\', '/'))
        assertEquals(UbuntuPaths.LEGACY_SESSIONS, UbuntuPaths.hostSessions.replace('\\', '/'))
        assertEquals(UbuntuPaths.LEGACY_MEMORY, UbuntuPaths.hostMemory.replace('\\', '/'))
        assertEquals(UbuntuPaths.LEGACY_HOME, UbuntuPaths.hostHome.replace('\\', '/'))
    }

    @Test
    fun activeLayoutMovesUserDataAwayFromLegacyRootOwnedPaths() {
        val filesDir = Files.createTempDirectory("minis-app-owned-layout").toFile()
        try {
            UbuntuPaths.useLayoutForTest(filesDir)

            val activeRoots = listOf(
                UbuntuPaths.hostWorkspace,
                UbuntuPaths.hostMemory,
                UbuntuPaths.hostSkills,
                UbuntuPaths.hostShared,
                UbuntuPaths.hostHome,
                UbuntuPaths.hostSessions,
            ).map { File(it).canonicalFile }

            assertEquals(
                listOf("workspace", "memory", "skills", "shared", "home", "sessions"),
                activeRoots.map { it.relativeTo(filesDir.canonicalFile).path.replace('\\', '/') },
            )
            assertTrue(activeRoots.all { it.path.startsWith(filesDir.canonicalPath + File.separator) })
            assertTrue(activeRoots.none { it.path.startsWith(UbuntuPaths.HOST_MINIS + "/") })

            val legacySources = listOf(
                UbuntuPaths.LEGACY_WORKSPACE,
                UbuntuPaths.LEGACY_MEMORY,
                UbuntuPaths.LEGACY_SKILLS,
                UbuntuPaths.LEGACY_SHARED,
                UbuntuPaths.LEGACY_HOME,
                UbuntuPaths.LEGACY_SESSIONS,
            )
            assertTrue(legacySources.all { it.startsWith(UbuntuPaths.HOST_MINIS + "/") })
            assertFalse(legacySources.contains(UbuntuPaths.HOST_ROOTFS))
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
