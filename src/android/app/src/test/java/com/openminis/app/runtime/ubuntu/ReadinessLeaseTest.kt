package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "checked recently" window: the first check opens it, each use slides it, ten idle minutes or a failure end it. */
class ReadinessLeaseTest {
    private var clock = 1_000_000L
    private val lease = ReadinessLease(idleMs = 10 * MINUTE) { clock }

    @Test
    fun neverCheckedMeansNotLive() {
        assertFalse(lease.renewIfLive())
    }

    @Test
    fun liveInsideTheWindowAndEachUseExtendsIt() {
        lease.renew()
        clock += 9 * MINUTE
        assertTrue(lease.renewIfLive())
        clock += 9 * MINUTE          // 18 minutes after the check, 9 after the last use
        assertTrue(lease.renewIfLive())
    }

    @Test
    fun tenIdleMinutesEndItAndTheNextCheckOpensItAgain() {
        lease.renew()
        clock += 10 * MINUTE
        assertFalse(lease.renewIfLive())
        lease.renew()
        clock += MINUTE
        assertTrue(lease.renewIfLive())
    }

    @Test
    fun aFailureOrStopEndsItAtOnce() {
        lease.renew()
        lease.invalidate()
        assertFalse(lease.renewIfLive())
    }

    @Test
    fun anExpiredWindowIsNotRevivedByAskingAgain() {
        lease.renew()
        clock += 11 * MINUTE
        assertFalse(lease.renewIfLive())
        assertFalse(lease.renewIfLive())
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
