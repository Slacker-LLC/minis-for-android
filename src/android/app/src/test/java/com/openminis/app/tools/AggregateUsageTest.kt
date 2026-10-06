package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateUsageTest {
    private fun row(pkg: String, ms: Long, last: Long = 0) = AppUsage(pkg, ms, last)
    private val min = 60_000L

    @Test
    fun anAppUsedEveryDayBeatsOneUsedForAnHourOnOneDay() {
        val rows = (1..7).map { row("daily", 30 * min, last = it.toLong()) } + row("once", 60 * min)
        val ranked = aggregateUsage(rows, limit = 5)
        assertEquals(listOf("daily", "once"), ranked.map { it.packageName })
        assertEquals(210 * min, ranked[0].foregroundMs)
        assertEquals(7L, ranked[0].lastUsedMs)
    }

    @Test
    fun eachPackageAppearsOnceAndTheLimitCountsApps() {
        val rows = (1..7).flatMap { listOf(row("a", 10 * min), row("b", 9 * min), row("c", 8 * min)) }
        val ranked = aggregateUsage(rows, limit = 2)
        assertEquals(listOf("a", "b"), ranked.map { it.packageName })
    }

    @Test
    fun zeroForegroundRowsAreIgnored() {
        assertEquals(emptyList<AppUsage>(), aggregateUsage(listOf(row("idle", 0)), limit = 5))
    }

    @Test
    fun tiesBreakByPackageNameSoTheOrderIsStable() {
        val ranked = aggregateUsage(listOf(row("b", min), row("a", min)), limit = 5)
        assertEquals(listOf("a", "b"), ranked.map { it.packageName })
    }
}
