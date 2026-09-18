package com.openminis.app.runtime.ubuntu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsRefreshCoordinatorTest {
    @Test
    fun `slow prior write cannot overwrite the latest network resolver`() = runTest {
        val coordinator = DnsRefreshCoordinator()
        var active = listOf("192.0.2.1")
        val writes = mutableListOf<List<String>>()
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val old = async { coordinator.refresh({ reads++; active }) { gate.await(); writes += it; true } }
        runCurrent()
        active = listOf("198.51.100.1")
        val next = async { coordinator.refresh({ reads++; active }) { writes += it; true } }
        runCurrent()
        // A second network transition occurs while the preceding RPC is still in flight.
        active = listOf("203.0.113.1")
        assertEquals(1, reads)
        assertTrue(writes.isEmpty())
        gate.complete(Unit)
        assertTrue(old.await())
        assertTrue(next.await())
        assertEquals(listOf(listOf("192.0.2.1"), listOf("203.0.113.1")), writes)
    }

    @Test
    fun `failed refresh reports failure and leaves later events runnable`() = runTest {
        val coordinator = DnsRefreshCoordinator()
        assertFalse(coordinator.refresh({ emptyList() }) { false })
        assertTrue(coordinator.refresh({ listOf("192.0.2.1") }) { it.single() == "192.0.2.1" })
    }

    @Test
    fun `cancelling an in flight refresh releases the next event`() = runTest {
        val coordinator = DnsRefreshCoordinator()
        val first = async { coordinator.refresh({ emptyList() }) { awaitCancellation() } }
        runCurrent()
        val next = async { coordinator.refresh({ listOf("192.0.2.1") }) { true } }
        runCurrent()
        assertFalse(next.isCompleted)
        first.cancel()
        assertTrue(next.await())
    }
}
