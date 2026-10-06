package com.openminis.app.runtime.guest

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeOffloadRegistryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun layout() {
        UbuntuPaths.useLayoutForTest(tmp.root)
    }

    @After
    fun reset() {
        UbuntuPaths.resetLayoutForTest()
    }

    @Test
    fun registeredHandlersAreLookedUpByName() {
        NativeOffloadServer.register("registry-test-cmd") { NativeOffloadResult(0, "ok") }
        assertNotNull(NativeOffloadServer.getHandler("registry-test-cmd"))
        assertTrue("registry-test-cmd" in NativeOffloadServer.registeredHandlers)
        assertNull(NativeOffloadServer.getHandler("no-such-command"))
        val result = NativeOffloadServer.getHandler("registry-test-cmd")!!
            .handle(NativeOffloadRequest(1, listOf("registry-test-cmd"), emptyMap(), "/"))
        assertEquals("ok", result.output)
    }

    @Test
    fun theOldTransportsReplyFilesAreSweptAndNothingElse() {
        val workspaceOffloads = File(UbuntuPaths.hostWorkspace, "offloads").apply { mkdirs() }
        val sessionOffloads = File(UbuntuPaths.hostSessions, "s1/offloads").apply { mkdirs() }
        val staleA = File(workspaceOffloads, ".native-offload-12-1").apply { writeText("x") }
        val staleB = File(sessionOffloads, ".native-offload-13-2").apply { writeText("x") }
        val userFile = File(workspaceOffloads, "result.txt").apply { writeText("keep") }
        val lookalike = File(sessionOffloads, "native-offload-notes.txt").apply { writeText("keep") }

        NativeOffloadServer.deleteLegacyReplyFiles()

        assertFalse(staleA.exists())
        assertFalse(staleB.exists())
        assertTrue(userFile.exists())
        assertTrue(lookalike.exists())
    }
}
