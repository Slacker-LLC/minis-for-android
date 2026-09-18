package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigWriteQueueTest {

    private fun config(label: String): ProviderConfig =
        ProviderConfig(agentLoopGroupIds = mutableListOf(label))

    @Test
    fun `writes are processed in submission order`() = runTest {
        val writes = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val queue = ProviderConfigWriteQueue(
            scope = this,
            persist = { writes += it.agentLoopGroupIds.single() },
            onFailure = { failures += it },
        )

        queue.enqueue(config("first"))
        queue.enqueue(config("second"))
        queue.enqueue(config("third"))
        queue.awaitIdle()

        assertEquals(listOf("first", "second", "third"), writes)
        assertTrue(failures.isEmpty())
        queue.close()
    }

    @Test
    fun `one failed write does not prevent later snapshots`() = runTest {
        val writes = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val queue = ProviderConfigWriteQueue(
            scope = this,
            persist = {
                val label = it.agentLoopGroupIds.single()
                if (label == "bad") error("synthetic failure")
                writes += label
            },
            onFailure = { failures += it.message.orEmpty() },
        )

        queue.enqueue(config("before"))
        queue.enqueue(config("bad"))
        queue.enqueue(config("after"))
        queue.awaitIdle()

        assertEquals(listOf("before", "after"), writes)
        assertEquals(listOf("synthetic failure"), failures)
        assertTrue(queue.enqueue(config("last")))
        queue.awaitIdle()
        assertEquals(listOf("before", "after", "last"), writes)
        queue.close()
    }
}
