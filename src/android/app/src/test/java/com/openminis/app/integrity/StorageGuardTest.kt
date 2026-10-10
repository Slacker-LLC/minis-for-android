package com.openminis.app.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun `threshold is the larger of 3 GB and 5 percent`() {
        assertEquals(3 * gb, StorageGuard.thresholdBytes(32 * gb))
        assertEquals(256 * gb / 100 * 5, StorageGuard.thresholdBytes(256 * gb))
        assertTrue(StorageGuard.thresholdBytes(512 * gb) > 3 * gb)
    }

    @Test fun `pauses below the line and resumes only with margin`() {
        val total = 128 * gb
        val t = StorageGuard.thresholdBytes(total)
        assertFalse(StorageGuard.nextPaused(false, t + 1, total))
        assertTrue(StorageGuard.nextPaused(false, t - 1, total))
        // Paused: still paused just above the line, resumed once 25 percent clear of it.
        assertTrue(StorageGuard.nextPaused(true, t + 1, total))
        assertTrue(StorageGuard.nextPaused(true, t + t / 4 - 1, total))
        assertFalse(StorageGuard.nextPaused(true, t + t / 4, total))
    }

    @Test fun `bulk writers are space consumers and deleters are not`() {
        for (c in listOf("cp", "tar", "unzip", "fallocate", "rsync", "wget", "curl")) {
            assertTrue(c, StorageGuard.consumesSpace(c, "$c x y"))
        }
        for (c in listOf("rm", "rmdir", "ls", "cat", "find", "pm", "kill", "df", "du", "mv")) {
            assertFalse(c, StorageGuard.consumesSpace(c, "$c x"))
        }
        assertFalse(StorageGuard.consumesSpace("dd", "dd if=/dev/zero of=/dev/null count=1"))
        assertTrue(StorageGuard.consumesSpace("dd", "dd if=/dev/zero of=/sdcard/x"))
        assertFalse(StorageGuard.consumesSpace("truncate", "truncate x"))
        assertTrue(StorageGuard.consumesSpace("truncate", "truncate -s 10G x"))
    }

    @Test fun `the freeze script only signals recorded process groups`() {
        val stop = StorageGuard.signalGuestShellsScript("STOP")
        assertTrue(stop.contains("kill -STOP -\$PID"))
        assertTrue(stop.contains("shell-*.pid"))
        assertTrue(stop.contains("[ \"\$PID\" -gt 1 ]"))
        assertTrue(StorageGuard.signalGuestShellsScript("CONT").contains("kill -CONT"))
        try {
            StorageGuard.signalGuestShellsScript("KILL")
            error("must reject other signals")
        } catch (_: IllegalArgumentException) {
        }
    }
}
