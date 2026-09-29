package com.openminis.app.offload

import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OffloadPermissionManagerScheduledTierTest {
    @Test
    fun `routine tier applies only to the scoped session and is removed on exit`() = runBlocking {
        val sessionId = "tier-scope-read-only"
        assertNull(OffloadPermissionManager.tierFor(sessionId))

        OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.READ_ONLY) {
            assertEquals(ScheduledTaskPermissionTier.READ_ONLY, OffloadPermissionManager.tierFor(sessionId))
            assertNull(OffloadPermissionManager.tierFor("unrelated-session"))
            OffloadPermissionManager.recordScheduledTierDenial(sessionId, "linux.shell", "rm -rf /")
            assertEquals(
                listOf(OffloadPermissionManager.ScheduledTierDenial("linux.shell", "rm -rf /")),
                OffloadPermissionManager.consumeScheduledTierDenials(sessionId),
            )
        }

        assertNull(OffloadPermissionManager.tierFor(sessionId))
        assertNull(OffloadPermissionManager.tierFor("unrelated-session"))
    }

    @Test
    fun `nested scopes prefer the strictest tier and restore the parent tier`() = runBlocking {
        val sessionId = "tier-scope-nested"
        OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.FULL) {
            assertEquals(ScheduledTaskPermissionTier.FULL, OffloadPermissionManager.tierFor(sessionId))
            OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.READ_ONLY) {
                assertEquals(ScheduledTaskPermissionTier.READ_ONLY, OffloadPermissionManager.tierFor(sessionId))
            }
            assertEquals(ScheduledTaskPermissionTier.FULL, OffloadPermissionManager.tierFor(sessionId))
        }
        assertNull(OffloadPermissionManager.tierFor(sessionId))
    }

    @Test
    fun `denial tool names and summaries are sanitized before run history`() = runBlocking {
        val sessionId = "tier-scope-denial-sanitize"
        OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.READ_ONLY) {
            OffloadPermissionManager.recordScheduledTierDenial(
                sessionId, "linux.file.write\nforged", "/workspace/bad\npath\u001b",
            )
            assertEquals(
                listOf(OffloadPermissionManager.ScheduledTierDenial(
                    "linux.file.write forged", "/workspace/bad path",
                )),
                OffloadPermissionManager.consumeScheduledTierDenials(sessionId),
            )
        }
    }

    @Test
    fun `concurrent unattended turn cannot suppress the active routine tier`() = runBlocking {
        val sessionId = "tier-scope-concurrent-turn"
        val wakeEntered = CompletableDeferred<Unit>()
        val releaseWake = CompletableDeferred<Unit>()
        OffloadPermissionManager.withUnattendedSession(sessionId, ScheduledTaskPermissionTier.READ_ONLY) {
            coroutineScope {
                val wake = launch {
                    OffloadPermissionManager.withUnattendedSession(sessionId) {
                        wakeEntered.complete(Unit)
                        releaseWake.await()
                        OffloadPermissionManager.recordScheduledTierDenial(sessionId, "linux.shell", "rm")
                    }
                }
                wakeEntered.await()
                assertEquals(ScheduledTaskPermissionTier.READ_ONLY, OffloadPermissionManager.tierFor(sessionId))
                assertEquals(true, OffloadPermissionManager.isUnattendedSession(sessionId))
                releaseWake.complete(Unit)
                wake.join()
                assertEquals(ScheduledTaskPermissionTier.READ_ONLY, OffloadPermissionManager.tierFor(sessionId))
                OffloadPermissionManager.recordScheduledTierDenial(sessionId, "linux.shell", "rm")
            }
            assertEquals(
                listOf(OffloadPermissionManager.ScheduledTierDenial("linux.shell", "rm")),
                OffloadPermissionManager.consumeScheduledTierDenials(sessionId),
            )
        }
        assertNull(OffloadPermissionManager.tierFor(sessionId))
    }
}
