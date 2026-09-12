package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeOffloadResourceTest {
    @Test
    fun `connection limiter rejects above capacity and detects over-release`() {
        val limiter = NativeOffloadConnectionLimiter(2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())
        limiter.release()
        assertTrue(limiter.tryAcquire())
        limiter.release()
        limiter.release()
        assertEquals(0, limiter.activeCount())
        try {
            limiter.release()
            fail("over-release was accepted")
        } catch (_: IllegalStateException) {
            // fail closed when worker ownership accounting is corrupted
        }
    }

    @Test
    fun `request budget rejects cumulative input above the cap`() {
        val budget = NativeOffloadServer.NativeOffloadRequestBudget(8)
        budget.consume(8)
        try {
            budget.consume(1)
            fail("oversized cumulative request was accepted")
        } catch (_: IllegalStateException) {
            // bounded before another allocation/read is attempted
        }
    }

    @Test
    fun `reply output is bounded in bytes and includes a truncation marker`() {
        val output = NativeOffloadServer.boundOutputForTest("x".repeat(5 * 1024 * 1024))
        assertTrue(output.size <= 4 * 1024 * 1024)
        assertTrue(output.toString(Charsets.UTF_8).contains("output truncated"))
    }
}
