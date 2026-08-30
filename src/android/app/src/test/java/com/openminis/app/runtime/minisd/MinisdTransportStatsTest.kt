package com.openminis.app.runtime.minisd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MinisdTransportStatsTest {
    @Before
    fun setUp() {
        MinisdTransportStats.resetForTests()
    }

    @After
    fun tearDown() {
        MinisdTransportStats.resetForTests()
    }

    @Test
    fun `aggregates frequency bytes duration and max by safe dimensions`() {
        repeat(2) {
            MinisdTransportStats.record(
                method = "ubuntu.status",
                transport = MinisdTransportStats.Transport.LOCAL_SOCKET,
                requestBytes = 120,
                responseBytes = 240,
                durationMs = 3L + it,
                outcome = "OK",
                fallback = false,
            )
        }

        val row = MinisdTransportStats.snapshot().single()
        assertEquals("ubuntu.status", row.method)
        assertEquals(MinisdTransportStats.Transport.LOCAL_SOCKET, row.transport)
        assertEquals("OK", row.outcome)
        assertEquals(false, row.fallback)
        assertEquals(2L, row.calls)
        assertEquals(240L, row.requestBytes)
        assertEquals(480L, row.responseBytes)
        assertEquals(7L, row.totalDurationMs)
        assertEquals(4L, row.maxDurationMs)
    }

    @Test
    fun `separates local and fallback transport attempts`() {
        MinisdTransportStats.record(
            "ubuntu.exec",
            MinisdTransportStats.Transport.LOCAL_SOCKET,
            300,
            0,
            2,
            "TRANSPORT_FAILED",
            true,
        )
        MinisdTransportStats.record(
            "ubuntu.exec",
            MinisdTransportStats.Transport.SU_CALL,
            300,
            800,
            40,
            "OK",
            true,
        )

        val rows = MinisdTransportStats.snapshot()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.transport == MinisdTransportStats.Transport.LOCAL_SOCKET && it.calls == 1L })
        assertTrue(rows.any { it.transport == MinisdTransportStats.Transport.SU_CALL && it.responseBytes == 800L })
    }

    @Test
    fun `negative samples clamp rather than corrupt aggregates`() {
        MinisdTransportStats.record(
            "system.ping",
            MinisdTransportStats.Transport.NONE,
            -1,
            -2,
            -3,
            "RUNTIME_UNAVAILABLE",
            false,
        )

        val row = MinisdTransportStats.snapshot().single()
        assertEquals(0L, row.requestBytes)
        assertEquals(0L, row.responseBytes)
        assertEquals(0L, row.totalDurationMs)
        assertEquals(0L, row.maxDurationMs)
    }
}
