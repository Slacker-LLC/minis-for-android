package com.openminis.app.xposed.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/system/AccessibilityServiceEnforcerTest.kt`
 * (Mangi-11/Eta @ c15de97). The enforcer edits a setting the platform also writes, so the cases
 * that matter are the ones about what it does to everybody else's entries: the target is added
 * without touching them, removed without touching them, and never matched loosely enough to catch a
 * look-alike component.
 */
class AccessibilityServiceEnforcerTest {

    private val component =
        "llc.slacker.eta/com.openminis.app.accessibility.MinisAccessibilityService"

    @Test
    fun `adds the target while preserving other accessibility services`() {
        assertEquals(component, appendAccessibilityServiceIfMissing(null, component))

        val existing = "example.reader/.ReaderService:example.switch/.SwitchService"
        assertEquals(
            "$existing:$component",
            appendAccessibilityServiceIfMissing(existing, component),
        )
    }

    @Test
    fun `does not duplicate a full or short component identity`() {
        assertNull(appendAccessibilityServiceIfMissing(component, component))
        assertNull(
            appendAccessibilityServiceIfMissing(
                "example.reader/.ReaderService",
                "example.reader/example.reader.ReaderService",
            ),
        )
    }

    @Test
    fun `similar and cross package class names are not the target`() {
        val similar = "$component.backup"
        assertEquals(
            "$similar:$component",
            appendAccessibilityServiceIfMissing(similar, component),
        )
        assertEquals(
            "llc.slacker.eta/.accessibility.MinisAccessibilityService:$component",
            appendAccessibilityServiceIfMissing(
                "llc.slacker.eta/.accessibility.MinisAccessibilityService",
                component,
            ),
        )
    }

    @Test
    fun `a repair removes only this app and keeps the order`() {
        val existing = "example.reader/.ReaderService:$component:example.switch/.SwitchService"

        assertEquals(
            "example.reader/.ReaderService:example.switch/.SwitchService",
            removeAccessibilityServiceIfPresent(existing, component),
        )
        assertTrue(containsAccessibilityService(existing, component))
        assertFalse(
            containsAccessibilityService(
                "example.reader/.ReaderService:example.switch/.SwitchService",
                component,
            ),
        )
    }

    @Test
    fun `the repair limiter is bounded and resumes after its cooldown`() {
        val limiter = AccessibilityRepairLimiter(
            disabledDurationsMs = longArrayOf(500L, 1_000L, 2_000L),
            cooldownMs = 60_000L,
        )

        assertEquals(AccessibilityRepairAttempt(1, 500L), limiter.nextAttempt(1_000L))
        assertEquals(AccessibilityRepairAttempt(2, 1_000L), limiter.nextAttempt(2_000L))
        assertEquals(AccessibilityRepairAttempt(3, 2_000L), limiter.nextAttempt(3_000L))
        assertNull(limiter.nextAttempt(3_001L))
        assertNull(limiter.nextAttempt(62_999L))
        assertEquals(AccessibilityRepairAttempt(1, 500L), limiter.nextAttempt(63_000L))
    }

    @Test
    fun `the restore backoff escalates, caps, and resets after a stable window`() {
        val backoff = AccessibilityRestoreBackoff(
            delaysMs = longArrayOf(300L, 1_000L, 5_000L, 30_000L),
            stableWindowMs = 60_000L,
        )

        assertEquals(300L, backoff.delayFor(0L))
        backoff.recordRestore(1_000L)
        assertEquals(1_000L, backoff.delayFor(1_001L))
        backoff.recordRestore(2_000L)
        assertEquals(5_000L, backoff.delayFor(2_001L))
        backoff.recordRestore(3_000L)
        assertEquals(30_000L, backoff.delayFor(3_001L))
        backoff.recordRestore(4_000L)
        assertEquals(30_000L, backoff.delayFor(4_001L))
        assertEquals(300L, backoff.delayFor(64_000L))
    }

    @Test
    fun `a health status needs a matching protocol and an explicit state`() {
        assertEquals(
            AccessibilityConnectionStatus.CONNECTED,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.DISCONNECTED,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.UNKNOWN,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION - 1,
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.UNKNOWN,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED,
            ),
        )
    }

    @Test
    fun `health checks wait for an unlocked owner and a configured service`() {
        assertFalse(shouldScheduleAccessibilityHealthCheck(false, true))
        assertFalse(shouldScheduleAccessibilityHealthCheck(true, false))
        assertTrue(shouldScheduleAccessibilityHealthCheck(true, true))
    }

    @Test
    fun `a control request must be ordered, versioned, and come from this app's uid`() {
        assertTrue(
            isAccessibilityControlRequestValid(
                ordered = true,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_123,
                appUid = 10_123,
            ),
        )
        assertFalse(
            isAccessibilityControlRequestValid(
                ordered = false,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_123,
                appUid = 10_123,
            ),
        )
        assertFalse(
            isAccessibilityControlRequestValid(
                ordered = true,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_124,
                appUid = 10_123,
            ),
        )
    }

    @Test
    fun `the protection is off until the user turns it on`() {
        assertFalse(AccessibilityProtectionProtocol.DEFAULT_ENABLED)
    }
}
