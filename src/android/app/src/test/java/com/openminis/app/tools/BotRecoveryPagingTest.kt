package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BotRecoveryPagingTest {

    @Test
    fun `every page is walked until a short one`() = runBlocking {
        val rows = (1..75).toList()
        val visited = mutableListOf<Int>()
        var fetches = 0
        BotDelegationCoordinator.forEachPage(32, { after: Int? ->
            fetches++
            rows.filter { after == null || it > after }.take(32)
        }) { visited += it }
        assertEquals(rows, visited)
        assertEquals(3, fetches)
    }

    @Test
    fun `rows the callback leaves unchanged do not loop forever`() = runBlocking {
        // An undeliverable receipt stays undelivered; the cursor still moves past it.
        val rows = (1..64).toList()
        var fetches = 0
        BotDelegationCoordinator.forEachPage(32, { after: Int? ->
            fetches++
            check(fetches < 10) { "pager looped" }
            rows.filter { after == null || it > after }.take(32)
        }) { }
        assertEquals("two full pages, then the empty one ends it", 3, fetches)
    }

    @Test
    fun `a failed receipt delivery is retried a bounded number of times, with growing pauses`() {
        val pauses = generateSequence(1) { it + 1 }
            .map { BotDelegationCoordinator.receiptRetryDelayMs(it) }
            .takeWhile { it != null }
            .toList()
        assertEquals(4, pauses.size)
        assertEquals(pauses.sortedBy { it }, pauses)
        assertEquals(null, BotDelegationCoordinator.receiptRetryDelayMs(5))
        assertEquals(null, BotDelegationCoordinator.receiptRetryDelayMs(0))
    }
}
