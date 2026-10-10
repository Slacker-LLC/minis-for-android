package com.openminis.app.offload

import com.openminis.app.runtime.ubuntu.RootAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootProcessTest {
    @Test
    fun `a refused or missing su stops the call with the CLI's existing error codes`() {
        assertEquals("PERMISSION_DENIED", RootProcess.unavailableFor(RootAccessStatus.DENIED)?.code)
        assertEquals("SERVICE_NOT_RUNNING", RootProcess.unavailableFor(RootAccessStatus.UNAVAILABLE)?.code)
    }

    @Test
    fun `states that are not a definite no still let su answer`() {
        for (status in listOf(
            RootAccessStatus.UNKNOWN, RootAccessStatus.NOT_GRANTED, RootAccessStatus.GRANTED, RootAccessStatus.TIMED_OUT,
        )) {
            assertNull("$status", RootProcess.unavailableFor(status))
        }
    }

    @Test
    fun `the runner's own failure follows the command's stderr`() {
        val result = RootProcess.toResult(124, "partial", "cmd said", "Root command timed out after 5000ms")
        assertEquals(124, result.exitCode)
        assertEquals("cmd said\nRoot command timed out after 5000ms", result.stderr)
        assertEquals("partial\ncmd said\nRoot command timed out after 5000ms", result.combined)
    }

    @Test
    fun `a clean run adds nothing to stderr`() {
        val result = RootProcess.toResult(0, "0\n", "", null)
        assertEquals("", result.stderr)
        assertEquals("0\n", result.combined)
    }
}
