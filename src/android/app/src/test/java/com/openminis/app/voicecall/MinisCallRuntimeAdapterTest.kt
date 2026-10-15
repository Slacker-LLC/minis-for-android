package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MinisCallRuntimeAdapter 半双工闭环（Fake 端口，纯 JVM）：
 * LISTENING → (final) → THINKING → SPEAKING → 播放完成 → LISTENING，
 * 以及打断/静音/失败时的状态与回调隔离（generation 防串台）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MinisCallRuntimeAdapterTest {

    private class FakeInput : CallSpeechInput {
        var started = 0
        var stopped = 0
        var cancelled = 0
        private val finals = mutableListOf<(String) -> Unit>()
        private val errors = mutableListOf<((RecognitionError, String?) -> Unit)>()
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (RecognitionError, String?) -> Unit) {
            started++
            finals += onFinal
            errors += onError
        }
        override fun stop() { stopped++ }
        override fun cancel() { cancelled++ }
        fun firePartial(text: String) {}
        /** 触发最新一轮 final。 */
        fun fireFinal(text: String) { finals.lastOrNull()?.invoke(text) }
        /** 触发指定代数（0 起）的旧回调，模拟迟到结果。 */
        fun fireFinalGeneration(generationIndex: Int, text: String) { finals.getOrNull(generationIndex)?.invoke(text) }
        fun fireError(error: RecognitionError, message: String?) { errors.lastOrNull()?.invoke(error, message) }
    }

    private class FakeModel(
        var result: () -> Result<String> = { Result.success("收到") },
    ) : CallModelGateway {
        var asked = 0
        var cancelled = 0
        var lastText: String? = null
        override suspend fun ask(text: String): Result<String> {
            asked++; lastText = text
            return result()
        }
        override fun cancel() { cancelled++ }
    }

    private class FakeOutput : CallSpeechOutput {
        val spoken = mutableListOf<String>()
        var stopped = 0
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() { stopped++ }
    }

    private fun harness(modelResult: () -> Result<String> = { Result.success("收到") }): Triple<MinisCallRuntimeAdapter, FakeInput, FakeModel> {
        val input = FakeInput()
        val model = FakeModel(modelResult)
        val output = FakeOutput()
        val adapter = MinisCallRuntimeAdapter(input, model, output, CoroutineScope(Dispatchers.Unconfined))
        return Triple(adapter, input, model)
    }

    @Test
    fun `full round trip listening thinking speaking listening`() {
        val (adapter, input, _) = harness()
        val states = mutableListOf<BloubState>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect { if (it is CallEvent.BloubStateChanged) states += it.state } }
        adapter.start()
        assertEquals(1, input.started)
        input.fireFinal("今天天气如何")
        // start() resets through IDLE first, then THINKING → SPEAKING → startListening again
        assertEquals(
            listOf(
                BloubState.IDLE,
                BloubState.LISTENING,
                BloubState.THINKING,
                BloubState.SPEAKING,
                BloubState.LISTENING,
            ),
            states,
        )
        job.cancel()
    }

    @Test
    fun `interrupt invalidates late callbacks`() {
        val (adapter, input, _) = harness()
        val states = mutableListOf<BloubState>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect { if (it is CallEvent.BloubStateChanged) states += it.state } }
        adapter.start()
        adapter.interrupt()
        // 旧一轮（第 0 代）的 final 迟到：必须被 generation 丢弃，不得产生 THINKING
        input.fireFinalGeneration(0, "迟到的结果")
        // 两个 LISTENING：start 的一个 + interrupt 回听的一个；晚到 final 不再产生 THINKING
        assertTrue(states.filter { it == BloubState.LISTENING }.size >= 2)
        assertTrue(states.none { it == BloubState.THINKING })
        job.cancel()
    }

    @Test
    fun `mute stops io and unmute resumes listening`() {
        val (adapter, input, _) = harness()
        val output = FakeOutput()
        adapterEvent(adapter)
        adapter.start()
        adapter.setMuted(true)
        // 静音: cancel input; 取消后 startListening 不产生新的 start
        val startsAfterMute = input.started
        adapter.setMuted(false)
        assertTrue(input.started > startsAfterMute)
    }

    @Test
    fun `stop cancels everything and does not resurrect state`() {
        val (adapter, input, model) = harness()
        adapter.start()
        input.fireFinal("你好")
        val before = model.asked
        adapter.stop()
        input.fireFinal("又来了")
        assertEquals(before, model.asked)
    }

    // ---- 容错与空闲：虚拟时钟下断言上限，避免出错后无声地一直耗电/请求 ----

    private class Timed(modelResult: () -> Result<String> = { Result.success("收到") }) {
        val scope = TestScope(UnconfinedTestDispatcher())
        val input = FakeInput()
        val model = FakeModel(modelResult)
        val output = FakeOutput()
        val adapter = MinisCallRuntimeAdapter(input, model, output, scope)
        val events = mutableListOf<CallEvent>()
        init { scope.launch { adapter.events.collect { events += it } } }
        val states get() = events.filterIsInstance<CallEvent.BloubStateChanged>().map { it.state }
        val notices get() = events.filterIsInstance<CallEvent.Notice>().map { it.kind }
        val ended get() = events.filterIsInstance<CallEvent.Ended>()
        fun pass(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    @Test
    fun `transient recognition errors retry twice then end the call`() {
        val t = Timed()
        t.adapter.start()
        repeat(2) {
            t.input.fireError(RecognitionError.NETWORK, "offline")
            t.pass(CallLimits.RETRY_DELAY_MS + 1)
        }
        assertEquals("two automatic retries", 3, t.input.started)
        assertEquals(listOf(CallNotice.RECOGNITION_RETRY, CallNotice.RECOGNITION_RETRY), t.notices)
        assertTrue(t.states.contains(BloubState.CONFUSED))
        assertTrue("still in the call", t.ended.isEmpty())
        t.input.fireError(RecognitionError.NETWORK, "offline")
        t.pass(CallLimits.RETRY_DELAY_MS + 1)
        assertEquals(CallEndReason.RECOGNITION_FAILED, t.ended.single().reason)
        assertEquals("offline", t.ended.single().detail)
        assertEquals("no fourth attempt after hanging up", 3, t.input.started)
    }

    @Test
    fun `missing microphone permission ends immediately without retrying`() {
        val t = Timed()
        t.adapter.start()
        t.input.fireError(RecognitionError.PERMISSION_DENIED, null)
        t.pass(CallLimits.RETRY_DELAY_MS + 1)
        assertEquals(CallEndReason.PERMISSION_DENIED, t.ended.single().reason)
        assertEquals(1, t.input.started)
        assertTrue(t.notices.isEmpty())
    }

    @Test
    fun `silence results never count as failures`() {
        val t = Timed()
        t.adapter.start()
        repeat(10) {
            t.input.fireError(RecognitionError.NO_MATCH, null)
            t.pass(CallLimits.RETRY_DELAY_MS + 1)
        }
        assertTrue(t.ended.isEmpty())
        assertTrue(t.notices.isEmpty())
        assertEquals(11, t.input.started)
    }

    @Test
    fun `a heard sentence resets the recognition retry budget`() {
        val t = Timed()
        t.adapter.start()
        repeat(2) {
            t.input.fireError(RecognitionError.ENGINE, "x")
            t.pass(CallLimits.RETRY_DELAY_MS + 1)
        }
        t.input.fireFinal("你好")
        t.pass(1)
        repeat(2) {
            t.input.fireError(RecognitionError.ENGINE, "x")
            t.pass(CallLimits.RETRY_DELAY_MS + 1)
        }
        assertTrue("budget restored by the successful turn", t.ended.isEmpty())
    }

    @Test
    fun `first task failure keeps the call and second consecutive one ends it`() {
        val t = Timed({ Result.failure(RuntimeException("没有可用模型")) })
        t.adapter.start()
        t.input.fireFinal("你好")
        t.pass(1)
        assertEquals(BloubState.ERROR, t.states.last())
        assertEquals(listOf(CallNotice.TASK_FAILED), t.notices)
        assertTrue(t.ended.isEmpty())
        t.pass(CallLimits.ERROR_PAUSE_MS + 1)
        assertEquals("back to listening after the pause", BloubState.LISTENING, t.states.last())
        t.input.fireFinal("再试一次")
        t.pass(1)
        assertEquals(CallEndReason.TASK_FAILED, t.ended.single().reason)
        assertEquals("没有可用模型", t.ended.single().detail)
        val asked = t.model.asked
        t.input.fireFinal("挂断后迟到")
        t.pass(1)
        assertEquals("no request after the call ended", asked, t.model.asked)
    }

    @Test
    fun `a successful turn between failures keeps the call alive`() {
        var fail = true
        val t = Timed({ if (fail) Result.failure(RuntimeException("x")) else Result.success("好的") })
        t.adapter.start()
        t.input.fireFinal("一")
        t.pass(CallLimits.ERROR_PAUSE_MS + 1)
        fail = false
        t.input.fireFinal("二")
        t.pass(1)
        fail = true
        t.input.fireFinal("三")
        t.pass(1)
        assertTrue("failures were not consecutive", t.ended.isEmpty())
    }

    @Test
    fun `idle reminds once then hangs up`() {
        val t = Timed()
        t.adapter.start()
        t.pass(CallLimits.IDLE_REMINDER_MS - 1)
        assertTrue(t.notices.isEmpty())
        t.pass(2)
        assertEquals(listOf(CallNotice.IDLE_REMINDER), t.notices)
        assertTrue(t.ended.isEmpty())
        t.pass(CallLimits.IDLE_END_MS)
        assertEquals(CallEndReason.IDLE_TIMEOUT, t.ended.single().reason)
        assertEquals(listOf(CallNotice.IDLE_REMINDER), t.notices)
    }

    @Test
    fun `idle timer restarts after the user speaks and pauses while muted`() {
        val t = Timed()
        t.adapter.start()
        t.pass(CallLimits.IDLE_REMINDER_MS + 1)
        t.input.fireFinal("你好")
        t.pass(1)
        // 回答播完回到聆听后重新计时：再过 1 个提醒周期才有第二次提醒。
        t.pass(CallLimits.IDLE_REMINDER_MS - 5)
        assertEquals(1, t.notices.size)
        t.pass(10)
        assertEquals(2, t.notices.size)
        t.adapter.setMuted(true)
        t.pass(CallLimits.IDLE_END_MS * 2)
        assertTrue("muted time is not idle time", t.ended.isEmpty())
        assertFalse(t.states.isEmpty())
    }

    private fun adapterEvent(adapter: MinisCallRuntimeAdapter) {
        CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect {} }
    }
}
