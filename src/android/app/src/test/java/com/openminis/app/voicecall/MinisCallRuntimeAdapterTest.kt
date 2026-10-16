package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.ApprovalDecision
import com.openminis.app.voicecall.ports.ApprovalPrompt
import com.openminis.app.voicecall.ports.CallApprovals
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallTaskUpdate
import com.openminis.app.voicecall.ports.CallTasks
import com.openminis.app.voicecall.ports.TaskReply
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class FakeApprovals : CallApprovals {
        val flow = MutableStateFlow<ApprovalPrompt?>(null)
        override val pending: StateFlow<ApprovalPrompt?> = flow
        val responses = mutableListOf<ApprovalDecision>()
        override fun respond(decision: ApprovalDecision) {
            responses += decision
            flow.value = null
        }
    }

    private class Timed(modelResult: () -> Result<String> = { Result.success("收到") }) {
        val scope = TestScope(UnconfinedTestDispatcher())
        val input = FakeInput()
        val model = FakeModel(modelResult)
        val output = FakeOutput()
        val approvals = FakeApprovals()
        val adapter = MinisCallRuntimeAdapter(input, model, output, scope, approvals)
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

    @Test
    fun `typed text interrupts the current turn and is answered aloud`() {
        val t = Timed()
        t.adapter.start()
        t.adapter.sendText("  打字问一句  ")
        assertEquals("打字问一句", t.model.lastText)
        assertEquals(listOf("收到"), t.output.spoken)
        assertTrue("back to listening after the reply", t.states.last() == BloubState.LISTENING)
        // 播报/思考期间再打字：旧一轮被取消，新的一轮照常回答。
        val cancelledBefore = t.model.cancelled
        t.adapter.sendText("再问")
        assertEquals(cancelledBefore + 1, t.model.cancelled)
        assertEquals(2, t.model.asked)
    }

    @Test
    fun `typed text while muted is spoken but does not reopen the microphone`() {
        val t = Timed()
        t.adapter.start()
        t.adapter.setMuted(true)
        val started = t.input.started
        t.adapter.sendText("静音时打字")
        assertEquals(listOf("收到"), t.output.spoken)
        assertEquals("microphone stays closed", started, t.input.started)
    }

    @Test
    fun `blank typed text and typing after hang up are ignored`() {
        val t = Timed()
        t.adapter.start()
        t.adapter.sendText("   ")
        assertEquals(0, t.model.asked)
        t.adapter.stop()
        t.adapter.sendText("挂断后")
        assertEquals(0, t.model.asked)
    }

    private val askPrompt = ApprovalPrompt("要允许吗", "没听清")

    @Test
    fun `permission request is read aloud and a spoken allow answers it`() {
        val t = Timed()
        t.adapter.start()
        t.approvals.flow.value = askPrompt
        assertEquals(listOf("要允许吗"), t.output.spoken)
        t.input.fireFinal("允许")
        assertEquals(listOf(ApprovalDecision.ALLOW_ONCE), t.approvals.responses)
        assertEquals("the answer is not sent to the model", 0, t.model.asked)
        assertEquals("back to normal listening", BloubState.LISTENING, t.states.last())
    }

    @Test
    fun `unclear answers are asked again and never decide`() {
        val t = Timed()
        t.adapter.start()
        t.approvals.flow.value = askPrompt
        t.input.fireFinal("今天天气怎么样")
        assertTrue(t.approvals.responses.isEmpty())
        assertEquals(listOf("要允许吗", "没听清"), t.output.spoken)
        assertEquals(0, t.model.asked)
        t.input.fireFinal("不要")
        assertEquals(listOf(ApprovalDecision.DENY), t.approvals.responses)
    }

    @Test
    fun `gives up asking after repeated misses but leaves the request pending`() {
        val t = Timed()
        t.adapter.start()
        t.approvals.flow.value = askPrompt
        repeat(CallLimits.MAX_APPROVAL_MISSES) { t.input.fireFinal("嗯嗯嗯") }
        assertTrue(t.approvals.responses.isEmpty())
        assertEquals("question plus two re-asks", CallLimits.MAX_APPROVAL_MISSES, t.output.spoken.size)
        assertTrue("dialog still waits for a tap", t.approvals.flow.value != null)
    }

    @Test
    fun `typed answer and screen answer both settle the request`() {
        val t = Timed()
        t.adapter.start()
        t.approvals.flow.value = askPrompt
        t.adapter.sendText("本次会话都允许")
        assertEquals(listOf(ApprovalDecision.ALLOW_SESSION), t.approvals.responses)
        assertEquals(0, t.model.asked)
        // 屏幕上点掉的情况：pending 变空，通话恢复聆听，不会再响应旧回调。
        t.approvals.flow.value = askPrompt
        val started = t.input.started
        t.approvals.flow.value = null
        assertTrue(t.input.started > started)
    }

    @Test
    fun `hanging up drops a pending spoken approval`() {
        val t = Timed()
        t.adapter.start()
        t.approvals.flow.value = askPrompt
        t.adapter.stop()
        t.input.fireFinal("允许")
        assertTrue(t.approvals.responses.isEmpty())
    }

    @Test
    fun `slow task gets one spoken working line then the real answer`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val input = FakeInput()
        val output = FakeOutput()
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val model = object : CallModelGateway {
            override suspend fun ask(text: String): Result<String> = Result.success(gate.await())
            override fun cancel() = Unit
        }
        val adapter = MinisCallRuntimeAdapter(input, model, output, scope, workingLine = "稍等")
        adapter.start()
        input.fireFinal("做个很久的任务")
        scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS - 1); scope.runCurrent()
        assertTrue(output.spoken.isEmpty())
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(listOf("稍等"), output.spoken)
        scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS * 3); scope.runCurrent()
        assertEquals("only once per turn", listOf("稍等"), output.spoken)
        gate.complete("做完了")
        scope.runCurrent()
        assertEquals(listOf("稍等", "做完了"), output.spoken)
    }

    @Test
    fun `fast answers never get a working line`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val input = FakeInput()
        val output = FakeOutput()
        val adapter = MinisCallRuntimeAdapter(input, FakeModel(), output, scope, workingLine = "稍等")
        adapter.start()
        input.fireFinal("你好")
        scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS * 2); scope.runCurrent()
        assertEquals(listOf("收到"), output.spoken)
    }

    // ---- 进度播报 / 任务指令 / 后台播报 ----

    private class FakeTasks(
        val overview: TaskReply = TaskReply("现在有两个任务"),
        val switch: TaskReply = TaskReply("好的，切换到整理相册", "s-2"),
        val back: TaskReply = TaskReply("回到上一个", "s-1"),
    ) : CallTasks {
        val flow = MutableSharedFlow<CallTaskUpdate>(extraBufferCapacity = 8)
        val reported = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        override suspend fun overview(): TaskReply { calls += "overview"; return overview }
        override suspend fun switchTo(target: String): TaskReply { calls += "switch:$target"; return switch }
        override suspend fun back(): TaskReply { calls += "back"; return back }
        override val updates = flow
        override fun isReported(key: String) = key in reported
    }

    private class Rig(
        val scope: TestScope = TestScope(UnconfinedTestDispatcher()),
        val input: FakeInput = FakeInput(),
        val output: FakeOutput = FakeOutput(),
        val tasks: FakeTasks = FakeTasks(),
        val model: FakeModel = FakeModel(),
        workingLine: String = "",
        val switched: MutableList<String> = mutableListOf(),
        approvals: CallApprovals = com.openminis.app.voicecall.ports.NoCallApprovals,
    ) {
        val transcript = mutableListOf<String>()
        val adapter = MinisCallRuntimeAdapter(input, model, output, scope, approvals, workingLine, tasks, { switched += it })
        init {
            scope.launch { adapter.events.collect { if (it is CallEvent.Transcript) transcript += (if (it.fromUser) "我:" else "AI:") + it.text } }
        }
    }

    @Test
    fun `verified progress is spoken when it changes and never repeated`() {
        val steps = ArrayDeque(listOf("正在读取文件", "正在读取文件", "正在写入结果"))
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val output = FakeOutput()
        val model = object : CallModelGateway {
            override suspend fun ask(text: String): Result<String> = Result.success(gate.await())
            override fun cancel() = Unit
            override fun progress(): String? = steps.removeFirstOrNull()
        }
        val input = FakeInput()
        val adapter = MinisCallRuntimeAdapter(input, model, output, scope)
        adapter.start()
        input.fireFinal("做个很久的任务")
        scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS + 1); scope.runCurrent()
        assertEquals(listOf("正在读取文件"), output.spoken)
        scope.advanceTimeBy(CallLimits.PROGRESS_INTERVAL_MS); scope.runCurrent()
        assertEquals("same state is not repeated", listOf("正在读取文件"), output.spoken)
        scope.advanceTimeBy(CallLimits.PROGRESS_INTERVAL_MS); scope.runCurrent()
        assertEquals(listOf("正在读取文件", "正在写入结果"), output.spoken)
        gate.complete("完成")
        scope.runCurrent()
        assertEquals("正在读取文件", output.spoken.first())
        assertEquals("完成", output.spoken.last())
    }

    @Test
    fun `no verified progress and no working line means silence`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val output = FakeOutput()
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val model = object : CallModelGateway {
            override suspend fun ask(text: String): Result<String> = Result.success(gate.await())
            override fun cancel() = Unit
        }
        val input = FakeInput()
        MinisCallRuntimeAdapter(input, model, output, scope).also { it.start() }
        input.fireFinal("慢任务")
        scope.advanceTimeBy(CallLimits.PROGRESS_INTERVAL_MS * 3); scope.runCurrent()
        assertTrue(output.spoken.isEmpty())
    }

    @Test
    fun `progress stops after the per turn cap`() {
        var n = 0
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val output = FakeOutput()
        val model = object : CallModelGateway {
            override suspend fun ask(text: String): Result<String> = Result.success(gate.await())
            override fun cancel() = Unit
            override fun progress(): String? = "第${++n}步"
        }
        val input = FakeInput()
        MinisCallRuntimeAdapter(input, model, output, scope).also { it.start() }
        input.fireFinal("慢任务")
        scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS + CallLimits.PROGRESS_INTERVAL_MS * 20); scope.runCurrent()
        assertEquals(CallLimits.MAX_PROGRESS_LINES, output.spoken.size)
    }

    @Test
    fun `asking for the task list is answered aloud without bothering the model`() {
        val r = Rig()
        r.adapter.start()
        r.input.fireFinal("现在有哪些任务")
        assertEquals(listOf("现在有两个任务"), r.output.spoken)
        assertEquals(0, r.model.asked)
        assertEquals(listOf("overview"), r.tasks.calls)
        assertEquals(listOf("我:现在有哪些任务", "AI:现在有两个任务"), r.transcript)
        assertEquals("listening resumes after a status question", 2, r.input.started)
        assertTrue(r.switched.isEmpty())
    }

    @Test
    fun `switching speaks the confirmation then hands over the session`() {
        val r = Rig()
        r.adapter.start()
        r.input.fireFinal("切换到整理相册任务")
        assertEquals(listOf("好的，切换到整理相册"), r.output.spoken)
        assertEquals(listOf("switch:整理相册"), r.tasks.calls)
        assertEquals(listOf("s-2"), r.switched)
        assertEquals(0, r.model.asked)
        assertEquals("does not reopen the mic after handing over", 1, r.input.started)
    }

    @Test
    fun `going back asks the task port for the previous task`() {
        val r = Rig()
        r.adapter.start()
        r.input.fireFinal("回到上一个任务")
        assertEquals(listOf("back"), r.tasks.calls)
        assertEquals(listOf("s-1"), r.switched)
    }

    @Test
    fun `a switch that finds nothing stays in the call and keeps listening`() {
        val r = Rig(tasks = FakeTasks(switch = TaskReply("没找到这个任务")))
        r.adapter.start()
        r.input.fireFinal("切换到不存在的任务")
        assertEquals(listOf("没找到这个任务"), r.output.spoken)
        assertTrue(r.switched.isEmpty())
        assertEquals(2, r.input.started)
    }

    @Test
    fun `a blank task answer falls through to the model`() {
        val r = Rig(tasks = FakeTasks(overview = TaskReply("")))
        r.adapter.start()
        r.input.fireFinal("现在有哪些任务")
        assertEquals(1, r.model.asked)
        assertEquals(listOf("收到"), r.output.spoken)
    }

    @Test
    fun `ordinary sentences never reach the task port`() {
        val r = Rig()
        r.adapter.start()
        r.input.fireFinal("帮我新建一个整理照片的任务")
        assertTrue(r.tasks.calls.isEmpty())
        assertEquals(1, r.model.asked)
    }

    @Test
    fun `a finished background task interrupts the wait and is spoken then listening resumes`() {
        val r = Rig()
        r.adapter.start()
        r.tasks.flow.tryEmit(CallTaskUpdate("t1:COMPLETED", "任务“整理相册”：已完成。"))
        assertEquals(listOf("任务“整理相册”：已完成。"), r.output.spoken)
        assertEquals(listOf("AI:任务“整理相册”：已完成。"), r.transcript)
        assertEquals(2, r.input.started)
        assertEquals(0, r.model.asked)
    }

    @Test
    fun `a background result waits until the current answer is done`() {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val output = FakeOutput()
        val tasks = FakeTasks()
        val model = object : CallModelGateway {
            override suspend fun ask(text: String): Result<String> = Result.success(gate.await())
            override fun cancel() = Unit
        }
        val input = FakeInput()
        MinisCallRuntimeAdapter(input, model, output, scope, tasks = tasks).also { it.start() }
        input.fireFinal("问个问题")
        tasks.flow.tryEmit(CallTaskUpdate("t1:COMPLETED", "后台完成"))
        assertTrue("not spoken over the pending answer", output.spoken.isEmpty())
        gate.complete("回答")
        scope.runCurrent()
        assertEquals(listOf("回答", "后台完成"), output.spoken)
    }

    @Test
    fun `a result already given in an answer is not announced again`() {
        val r = Rig()
        r.adapter.start()
        r.tasks.reported += "t1:COMPLETED"
        r.tasks.flow.tryEmit(CallTaskUpdate("t1:COMPLETED", "重复"))
        assertTrue(r.output.spoken.isEmpty())
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `muted calls log background results but stay silent`() {
        val r = Rig()
        r.adapter.start()
        r.adapter.setMuted(true)
        r.tasks.flow.tryEmit(CallTaskUpdate("t1:FAILED", "任务失败"))
        assertTrue(r.output.spoken.isEmpty())
        assertEquals(listOf("AI:任务失败"), r.transcript)
    }

    @Test
    fun `background results after hanging up are ignored`() {
        val r = Rig()
        r.adapter.start()
        r.adapter.stop()
        r.tasks.flow.tryEmit(CallTaskUpdate("t1:COMPLETED", "太晚了"))
        assertTrue(r.output.spoken.isEmpty())
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `a pending permission question is never talked over by a background result`() {
        val approvals = FakeApprovals()
        val r = Rig(approvals = approvals)
        r.adapter.start()
        approvals.flow.value = ApprovalPrompt("要允许吗", "没听清")
        r.tasks.flow.tryEmit(CallTaskUpdate("t1:COMPLETED", "后台完成"))
        assertEquals(listOf("要允许吗"), r.output.spoken)
    }

    private fun adapterEvent(adapter: MinisCallRuntimeAdapter) {
        CoroutineScope(Dispatchers.Unconfined).launch { adapter.events.collect {} }
    }
}
