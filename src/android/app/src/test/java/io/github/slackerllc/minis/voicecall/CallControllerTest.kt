package io.github.slackerllc.minis.voicecall.controller

import io.github.slackerllc.minis.voicecall.model.BloubState
import io.github.slackerllc.minis.voicecall.model.CallMode
import io.github.slackerllc.minis.voicecall.runtime.CallEvent
import io.github.slackerllc.minis.voicecall.runtime.CallRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通话状态机单元测试（纯 JVM，无 Android 依赖）。
 * 覆盖评审要求的核心 Flow：进入/切模式/静音/最小化/恢复/计时/挂断。
 * Unconfined scope：runtime 事件同步回流，断言无需等待。
 */
class CallControllerTest {

    private class FakeRuntime : CallRuntime {
        val sentTexts = mutableListOf<String>()
        var started = 0
        var stopped = 0
        val mutedEvents = mutableListOf<Boolean>()
        val interrupts = mutableListOf<Unit>()
        private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<CallEvent> = _events
        override fun start() { started++ }
        override fun stop() { stopped++; _events.tryEmit(CallEvent.BloubStateChanged(BloubState.IDLE)) }
        override fun setMuted(muted: Boolean) {
            mutedEvents += muted
            _events.tryEmit(CallEvent.MuteChanged(muted))
        }
        override fun interrupt() { interrupts += Unit }
        override fun sendText(text: String) { sentTexts += text }
    }

    private fun controller(runtime: CallRuntime): CallController =
        CallController(runtime, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `startCall enters call in listening, zero elapsed`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.VOICE)
        assertTrue(c.state.active)
        assertEquals(CallMode.VOICE, c.state.mode)
        assertEquals(BloubState.LISTENING, c.state.ai.bloubState)
        assertEquals(0L, c.state.elapsedSeconds)
        assertFalse(c.state.minimized)
        assertFalse(c.state.muted)
    }

    @Test
    fun `startCall and endCall drive the runtime`() {
        val runtime = FakeRuntime()
        val c = controller(runtime)
        c.startCall(CallMode.VOICE)
        assertEquals(1, runtime.started)
        c.endCall()
        assertEquals(1, runtime.stopped)
        assertFalse(c.state.active)
    }

    @Test
    fun `switchMode keeps ai state, muted and elapsed`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.VOICE)
        c.setBloubState(BloubState.THINKING)
        c.setMuted(true)
        c.tick()

        c.switchMode(CallMode.VIDEO)

        assertEquals(CallMode.VIDEO, c.state.mode)
        assertEquals(BloubState.THINKING, c.state.ai.bloubState)
        assertTrue(c.state.muted)
        assertEquals(1L, c.state.elapsedSeconds)
    }

    @Test
    fun `mute overrides display but keeps real ai state`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.VOICE)
        c.setBloubState(BloubState.SPEAKING)

        c.setMuted(true)
        assertEquals(BloubState.MUTED, c.state.effectiveBloubState)
        assertEquals(BloubState.SPEAKING, c.state.ai.bloubState)

        c.setMuted(false)
        assertEquals(BloubState.SPEAKING, c.state.effectiveBloubState)
    }

    @Test
    fun `minimize does not end session and restore returns original mode`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.SHARE)

        c.minimize()
        assertTrue(c.state.active)
        assertTrue(c.state.minimized)
        assertEquals(CallMode.SHARE, c.state.mode)

        c.restore()
        assertFalse(c.state.minimized)
        assertEquals(CallMode.SHARE, c.state.mode)
    }

    @Test
    fun `tick only increments while active`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.VOICE)
        c.tick(); c.tick()
        assertEquals(2L, c.state.elapsedSeconds)

        c.endCall()
        c.tick()
        assertEquals(0L, c.state.elapsedSeconds)
    }

    @Test
    fun `endCall fully resets session and new call does not inherit old state`() {
        val c = controller(FakeRuntime())
        c.startCall(CallMode.VIDEO)
        c.setBloubState(BloubState.SUCCESS)
        c.setMuted(true)
        c.tick()

        c.endCall()
        assertFalse(c.state.active)
        assertNull(c.state.mode)
        assertEquals(BloubState.IDLE, c.state.ai.bloubState)
        assertEquals(0L, c.state.elapsedSeconds)
        assertFalse(c.state.muted)

        // 新通话不能继承旧状态
        c.startCall(CallMode.VOICE)
        assertFalse(c.state.muted)
        assertEquals(BloubState.LISTENING, c.state.ai.bloubState)
        assertEquals(CallMode.VOICE, c.state.mode)
    }

    @Test
    fun `sendText forwards to runtime`() {
        val runtime = FakeRuntime()
        val c = controller(runtime)
        c.sendText("你好")
        assertEquals(listOf("你好"), runtime.sentTexts)
    }
}
