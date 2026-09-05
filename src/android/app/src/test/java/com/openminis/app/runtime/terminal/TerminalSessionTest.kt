package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for Issues #186 & #189 in TerminalSession. */
class TerminalSessionTest {

    @Test
    fun `buildLaunchScript includes dynamic guest uid and gid`() {
        val script = TerminalSession.buildLaunchScript(guestUid = 10347, guestGid = 10347, sessionId = null)
        assertTrue(script.contains("--uid 10347 --gid 10347"))
        assertFalse(script.contains("--uid 10000 --gid 10000"))
        assertFalse(script.contains("--session-root"))
    }

    @Test
    fun `buildLaunchScript includes session root when sessionId is provided`() {
        val script = TerminalSession.buildLaunchScript(guestUid = 10347, guestGid = 10347, sessionId = "test-session-42")
        assertTrue(script.contains("--session-root /data/adb/minis/sessions/test-session-42"))
        assertTrue(script.contains("mkdir -p /data/adb/minis/sessions/test-session-42"))
        assertTrue(script.contains("--uid 10347 --gid 10347"))
    }

    @Test
    fun `buildLaunchScript omits session root when sessionId is blank`() {
        val script = TerminalSession.buildLaunchScript(guestUid = 10000, guestGid = 10000, sessionId = "  ")
        assertFalse(script.contains("--session-root"))
        assertTrue(script.contains("--uid 10000 --gid 10000"))
    }
}
