package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A timed-out foreground call keeps what the command printed; callers still match on the first line. */
class RootPersistentShellTimeoutTest {
    @Test
    fun timeoutWithoutOutputIsTheBareMessage() {
        assertEquals("command timed out after 5000ms", RootPersistentShell.timedOutOutput(5000, ""))
        assertEquals("command timed out after 5000ms", RootPersistentShell.timedOutOutput(5000, "\n\n"))
    }

    @Test
    fun partialOutputFollowsTheFixedFirstLine() {
        val text = RootPersistentShell.timedOutOutput(5000, "step 1\nstep 2\n")
        assertTrue(text.startsWith("command timed out after 5000ms"))
        assertTrue(text.endsWith("step 1\nstep 2"))
    }
}
