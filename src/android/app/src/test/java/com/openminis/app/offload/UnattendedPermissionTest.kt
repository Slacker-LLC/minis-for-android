package com.openminis.app.offload

import com.openminis.app.runtime.guest.OffloadGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
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
            suspendCancellableCoroutine { /* no ChatScreen is observing this request */ }
        }
        assertEquals(OffloadPermissionManager.AskOnceWaitResult.TimedOut, result)
        val timedOut = OffloadPermissionManager.PermissionCheckResult(false, unattendedTimeout = true)
        assertEquals("permission_denied_unattended", OffloadGate.denialCode(timedOut))
    }

    @Test
    fun `privacy and integration tools default deny only during unattended sessions`() {
        val calendar = OffloadPermissionManager.toolRegistry.first { it.toolName == "calendar" }
        val integration = OffloadPermissionManager.toolRegistry.first { it.toolName == "a11y_cli" }
        val media = OffloadPermissionManager.toolRegistry.first { it.toolName == "speak" }
        val vscreen = OffloadPermissionManager.toolRegistry.filter { it.toolName.startsWith("android.vscreen.") }

        assertEquals(
            OffloadPermissionManager.PermissionLevel.BYPASS,
            OffloadPermissionManager.resolveLevelForSession(calendar, null, unattended = false),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            OffloadPermissionManager.resolveLevelForSession(calendar, null, unattended = true),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            OffloadPermissionManager.resolveLevelForSession(integration, null, unattended = true),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.BYPASS,
            OffloadPermissionManager.resolveLevelForSession(calendar, "BYPASS", unattended = true),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.ASK_ONCE,
            OffloadPermissionManager.resolveLevelForSession(calendar, "ASK_ONCE", unattended = true),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
            OffloadPermissionManager.resolveLevelForSession(calendar, "not-a-level", unattended = true),
        )
        assertEquals(
            OffloadPermissionManager.PermissionLevel.BYPASS,
            OffloadPermissionManager.resolveLevelForSession(media, null, unattended = true),
        )
        assertEquals(
            setOf("android.vscreen.open", "android.vscreen.launch", "android.vscreen.close", "android.vscreen.status", "android.vscreen.ui"),
            vscreen.map { it.toolName }.toSet(),
        )
        assertTrue(vscreen.all { it.defaultLevel == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED })
        assertTrue(vscreen.all {
            OffloadPermissionManager.resolveLevelForSession(it, null, unattended = false) ==
                OffloadPermissionManager.PermissionLevel.NOT_ALLOWED
        })
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
    fun `concurrent ask once requests are presented one at a time`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<OffloadPermissionManager.Response>()
        var secondEntered = false

        val first = async {
            OffloadPermissionManager.awaitAskOnceResponse(unattended = false) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            OffloadPermissionManager.awaitAskOnceResponse(unattended = false) {
                secondEntered = true
                OffloadPermissionManager.Response.ALLOW_ONCE
            }
        }
        yield()
        assertFalse(secondEntered)

        releaseFirst.complete(OffloadPermissionManager.Response.DENY_SESSION)
        assertEquals(
            OffloadPermissionManager.AskOnceWaitResult.Responded(OffloadPermissionManager.Response.DENY_SESSION),
            first.await(),
        )
        assertEquals(
            OffloadPermissionManager.AskOnceWaitResult.Responded(OffloadPermissionManager.Response.ALLOW_ONCE),
            second.await(),
        )
        assertTrue(secondEntered)
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

    @Test
    fun `legacy ask-once choice fails closed to not allowed`() {
        assertEquals("NOT_ALLOWED", OffloadPermissionManager.migrateLegacyLevel("ASK_ONCE"))
        assertEquals("BYPASS", OffloadPermissionManager.migrateLegacyLevel("BYPASS"))
        assertEquals("NOT_ALLOWED", OffloadPermissionManager.migrateLegacyLevel("NOT_ALLOWED"))
        assertEquals(null, OffloadPermissionManager.migrateLegacyLevel(null))
    }
}
