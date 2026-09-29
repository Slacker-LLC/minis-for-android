package com.openminis.app.tools.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenLeaseTest {
    @Test fun perDisplayLeaseWaitsThenReturnsSanitizedHolder() = runBlocking {
        var now = 0L
        val lease = newLease({ now }, sleep = { now += it })
        try {
            assertTrue(lease.acquire(0, "attended-a", "Bot\nA", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            val blocked = lease.acquire(0, "attended-b", "Bot B", unattended = false)
            assertEquals(DeviceScreenLease.Acquisition.Busy("Bot A"), blocked)
            assertEquals(30L, now)
        } finally {
            lease.close()
        }
    }

    @Test fun sameSessionCanReenterAndPhysicalAndVirtualScreensAreIndependent() = runBlocking {
        val lease = newLease()
        try {
            assertTrue(lease.acquire(0, "same", "Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            assertTrue(lease.acquire(0, "same", "Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            assertTrue(lease.acquire(9, "other", "Routine", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            assertEquals("same", lease.owner(0)?.sessionId)
            assertEquals("other", lease.owner(9)?.sessionId)
        } finally {
            lease.close()
        }
    }

    @Test fun attendedPhysicalOperationPreemptsUnattendedAndOldOwnerSeesPreempted() = runBlocking {
        val lease = newLease()
        try {
            assertTrue(lease.acquire(0, "routine", "Night Routine", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            assertTrue(lease.acquire(0, "human", "Current Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            assertEquals(DeviceScreenLease.Acquisition.Preempted, lease.acquire(0, "routine", "Night Routine", unattended = true))
        } finally {
            lease.close()
        }
    }

    @Test fun releaseClearsPreemptionMarkerForFormerOwner() = runBlocking {
        var now = 0L
        val lease = newLease({ now }, sleep = { now += it })
        try {
            assertTrue(lease.acquire(0, "routine", "Night Routine", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            assertTrue(lease.acquire(0, "human", "Current Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            assertFalse(lease.release(0, "routine"))
            assertEquals(
                DeviceScreenLease.Acquisition.Busy("Current Chat"),
                lease.acquire(0, "routine", "Night Routine", unattended = true),
            )
        } finally {
            lease.close()
        }
    }

    @Test fun virtualScreenIsNeverPreempted() = runBlocking {
        var now = 0L
        val lease = newLease({ now }, sleep = { now += it })
        try {
            assertTrue(lease.acquire(9, "routine", "Night Routine", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            assertEquals(DeviceScreenLease.Acquisition.Busy("Night Routine"), lease.acquire(9, "human", "Current Chat", unattended = false))
        } finally {
            lease.close()
        }
    }

    @Test fun turnEndAndDeletedSessionReleaseTheirLease() = runBlocking {
        val lease = newLease()
        try {
            assertTrue(lease.acquire(9, "deleted", "Deleted Chat", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            lease.releaseSession("deleted")
            assertTrue(lease.acquire(9, "next", "Next Chat", unattended = true) is DeviceScreenLease.Acquisition.Granted)
            assertFalse(lease.release(9, "deleted"))
            lease.releaseSession("next")
            assertEquals(null, lease.owner(9))
        } finally {
            lease.close()
        }
    }

    @Test fun idleWatchdogReclaimsAfterConfiguredPeriod() = runBlocking {
        var now = 0L
        val lease = newLease({ now }, idle = 120L, sleep = { now += it })
        try {
            assertTrue(lease.acquire(0, "idle", "Idle Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
            now = 120L
            assertEquals("idle", lease.expireIdle().single().sessionId)
            assertEquals(null, lease.owner(0))
            assertTrue(lease.acquire(0, "next", "Next Chat", unattended = false) is DeviceScreenLease.Acquisition.Granted)
        } finally {
            lease.close()
        }
    }

    private fun newLease(
        clock: () -> Long = { 0L },
        idle: Long = 120L,
        sleep: suspend (Long) -> Unit = {},
    ) = DeviceScreenLease(
        clock = clock,
        waitTimeoutMs = 30L,
        idleTimeoutMs = idle,
        pollIntervalMs = 10L,
        sleep = sleep,
        watchdogEnabled = false,
    )
}
