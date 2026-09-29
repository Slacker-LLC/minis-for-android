package com.openminis.app.offload

import com.openminis.app.runtime.guest.OffloadGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedPermissionTest {
    @Test
    fun `unattended ask once timeout is classified as a denial`() = runBlocking {
        val result = OffloadPermissionManager.awaitAskOnceResponse(
            unattended = true,
            timeoutMs = 20L,
        ) {
            kotlinx.coroutines.suspendCancellableCoroutine { /* no ChatScreen is observing this request */ }
        }
        assertEquals(OffloadPermissionManager.AskOnceWaitResult.TimedOut, result)
        val timedOut = OffloadPermissionManager.PermissionCheckResult(false, unattendedTimeout = true)
        assertEquals("permission_denied_unattended", OffloadGate.denialCode(timedOut))
    }

    @Test
    fun `interactive ask once still waits for a user response beyond unattended timeout`() = runBlocking {
        val startedAt = System.currentTimeMillis()
        val result = OffloadPermissionManager.awaitAskOnceResponse(
            unattended = false,
            timeoutMs = 1L,
        ) {
            delay(25L)
            OffloadPermissionManager.Response.ALLOW_ONCE
        }
        assertEquals(
            OffloadPermissionManager.AskOnceWaitResult.Responded(OffloadPermissionManager.Response.ALLOW_ONCE),
            result,
        )
        assertTrue(System.currentTimeMillis() - startedAt >= 20L)
        assertEquals("permission_denied", OffloadGate.denialCode(OffloadPermissionManager.PermissionCheckResult(false)))
    }

    @Test
    fun `unattended session marker is removed after work`() = runBlocking {
        val sessionId = "unattended-test"
        assertFalse(OffloadPermissionManager.isUnattendedSession(sessionId))
        OffloadPermissionManager.withUnattendedSession(sessionId) {
            assertTrue(OffloadPermissionManager.isUnattendedSession(sessionId))
        }
        assertFalse(OffloadPermissionManager.isUnattendedSession(sessionId))
    }
}
