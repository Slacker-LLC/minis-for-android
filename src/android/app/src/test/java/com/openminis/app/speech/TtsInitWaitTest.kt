package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsInitWaitTest {
    /** A clock that only moves when the waiter sleeps. */
    private class FakeClock { var t = 0L; fun now() = t; fun sleep(ms: Long) { t += ms } }

    @Test
    fun `an engine that is ready after two and a half seconds is waited for`() {
        val c = FakeClock()
        val outcome = awaitTtsInit(4_000, { c.t >= 2_500 }, { false }, c::now, c::sleep)
        assertEquals(TtsInit.READY, outcome)
        assertTrue("it did not give up at the old 2s budget", c.t >= 2_500)
    }

    @Test
    fun `a reported init error ends the wait at once as failed`() {
        val c = FakeClock()
        assertEquals(TtsInit.FAILED, awaitTtsInit(4_000, { false }, { c.t >= 100 }, c::now, c::sleep))
        assertTrue(c.t < 200)
    }

    @Test
    fun `no callback within the budget is not-ready, not failed`() {
        val c = FakeClock()
        assertEquals(TtsInit.NOT_READY, awaitTtsInit(4_000, { false }, { false }, c::now, c::sleep))
        assertTrue(c.t >= 4_000)
    }

    @Test
    fun `an engine that is already up returns immediately`() {
        val c = FakeClock()
        assertEquals(TtsInit.READY, awaitTtsInit(4_000, { true }, { false }, c::now, c::sleep))
        assertEquals(0L, c.t)
    }

    @Test
    fun `an interrupt stops the wait without claiming the engine is missing`() {
        val outcome = awaitTtsInit(4_000, { false }, { false }, { 0L }, { throw InterruptedException() })
        assertEquals(TtsInit.NOT_READY, outcome)
        Thread.interrupted() // clear the flag the function restored
    }
}
