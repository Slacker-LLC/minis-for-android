package io.github.slackerllc.minis.voicecall.runtime

import io.github.slackerllc.minis.voicecall.model.BloubState
import io.github.slackerllc.minis.voicecall.ports.CallModelGateway
import io.github.slackerllc.minis.voicecall.ports.CallSpeechInput
import io.github.slackerllc.minis.voicecall.ports.CallSpeechOutput
import io.github.slackerllc.minis.voicecall.ports.RecognitionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MinisCallRuntimeAdapter 半双工闭环（Fake 端口，纯 JVM）：
 * LISTENING → (final) → THINKING → SPEAKING → 播放完成 → LISTENING，
 * 以及打断/静音/失败时的状态与回调隔离（generation 防串台）。
 */
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
        // THINKING → SPEAKING → speak completes → startListening again
        assertEquals(listOf(BloubState.LISTENING, BloubState.THINKING, BloubState.SPEAKING, BloubState.LISTENING), states)
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
    fun `model failure goes error then listening`() {
        val (adapter, input, _) = harness({ Result.failure(RuntimeException("网络断了")) })
        val states = mutableListOf<BloubState>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect { if (it is CallEvent.BloubStateChanged) states += it.state } }
        adapter.start()
        input.fireFinal("你好")
        assertTrue("must show ERROR", states.contains(BloubState.ERROR))
        // 失败后 delay(900) 再回听：等待以便断言闭环
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1200) }
        assertTrue("must resume LISTENING", states.lastOrNull() == BloubState.LISTENING)
        job.cancel()
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

    private fun adapterEvent(adapter: MinisCallRuntimeAdapter) {
        CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect {} }
    }
}
