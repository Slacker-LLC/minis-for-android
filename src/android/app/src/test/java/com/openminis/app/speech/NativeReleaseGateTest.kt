package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReleaseGateTest {
    private val released = mutableListOf<String>()
    private val gate = NativeReleaseGate<String> { released += it }

    @Test
    fun `release from inside the native call waits until the call returns`() {
        gate.enter()
        gate.release("vad") // 模拟回调里的 stop()
        assertTrue("原生调用还在栈上，不能释放", released.isEmpty())
        gate.exit()
        assertEquals(listOf("vad"), released)
    }

    @Test
    fun `release outside a native call is immediate`() {
        gate.release("vad")
        assertEquals(listOf("vad"), released)
    }

    @Test
    fun `release from another thread is immediate even while a call is in flight`() {
        gate.enter()
        Thread { gate.release("vad") }.also { it.start(); it.join() }
        assertEquals(listOf("vad"), released)
        gate.exit()
        assertEquals("exit 不应重复释放", listOf("vad"), released)
    }

    @Test
    fun `exit without a deferred release does nothing and the gate is reusable`() {
        gate.enter(); gate.exit()
        assertTrue(released.isEmpty())
        gate.enter(); gate.release("a"); gate.exit()
        gate.enter(); gate.release("b"); gate.exit()
        assertEquals(listOf("a", "b"), released)
    }

    @Test
    fun `a throwing release is swallowed and does not leave a stale deferral`() {
        val g = NativeReleaseGate<String> { error("boom") }
        g.enter(); g.release("x"); g.exit()
        g.enter(); g.exit() // 第二次 exit 不能再次触发 doRelease
    }
}
