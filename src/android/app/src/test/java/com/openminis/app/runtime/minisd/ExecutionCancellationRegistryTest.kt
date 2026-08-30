package com.openminis.app.runtime.minisd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCancellationRegistryTest {

    @Test
    fun `global cancellation captures and marks every active session`() {
        val registry = ExecutionCancellationRegistry()
        registry.register("session-a", "exec-a")
        registry.register("session-b", "exec-b")

        val targets = registry.requestAllSessionCancellations()
            .associate { it.sessionId to it.executionId }

        assertEquals(mapOf("session-a" to "exec-a", "session-b" to "exec-b"), targets)
        assertTrue(registry.isCancellationRequested("exec-a"))
        assertTrue(registry.isCancellationRequested("exec-b"))
    }

    @Test
    fun `unregister only removes the matching execution and clears its cancellation`() {
        val registry = ExecutionCancellationRegistry()
        registry.register("session", "exec-old")
        registry.requestSessionCancellation("session")
        registry.register("session", "exec-new")

        registry.unregister("session", "exec-old")

        assertFalse(registry.isCancellationRequested("exec-old"))
        assertEquals("exec-new", registry.requestSessionCancellation("session")?.executionId)
    }

    @Test
    fun `missing session has no cancellation target`() {
        val registry = ExecutionCancellationRegistry()

        assertNull(registry.requestSessionCancellation("missing"))
    }
}
