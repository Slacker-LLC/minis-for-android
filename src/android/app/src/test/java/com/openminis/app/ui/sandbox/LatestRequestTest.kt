package com.openminis.app.ui.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestTest {
    @Test
    fun theNewestTicketIsCurrent() {
        val latest = LatestRequest()
        val a = latest.next()
        assertTrue(latest.isCurrent(a))
    }

    @Test
    fun aSlowEarlierLoadLosesToALaterOneThatFinishedFirst() {
        val latest = LatestRequest()
        val slowDirectoryA = latest.next()
        val fastDirectoryB = latest.next()
        // B finishes and publishes; A finishes afterwards and must be dropped.
        assertTrue(latest.isCurrent(fastDirectoryB))
        assertFalse(latest.isCurrent(slowDirectoryA))
    }

    @Test
    fun aNewLoadInvalidatesEveryOlderOne() {
        val latest = LatestRequest()
        val tickets = (1..5).map { latest.next() }
        assertTrue(tickets.dropLast(1).none { latest.isCurrent(it) })
        assertTrue(latest.isCurrent(tickets.last()))
    }
}
