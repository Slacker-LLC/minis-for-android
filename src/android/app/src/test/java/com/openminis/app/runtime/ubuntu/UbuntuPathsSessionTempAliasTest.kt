package com.openminis.app.runtime.ubuntu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class UbuntuPathsSessionTempAliasTest {
    @Test
    fun `guest tmp and offloads resolve to the same session directory`() {
        val sessionsRoot = Files.createTempDirectory("minis-session-paths").toFile()
        val guestTmp = UbuntuPaths.resolveSessionPath(sessionsRoot, "routine_1", "/tmp/draft.txt")
        val appAlias = UbuntuPaths.resolveSessionPath(sessionsRoot, "routine_1", "/var/minis/offloads/draft.txt")

        assertEquals(File(sessionsRoot, "routine_1/offloads/draft.txt").canonicalFile, guestTmp?.canonicalFile)
        assertEquals(guestTmp?.canonicalFile, appAlias?.canonicalFile)
    }
}
