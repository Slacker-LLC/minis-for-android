package com.openminis.app.voicecall.realtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.ApprovalDecision
import com.openminis.app.voicecall.ports.ApprovalPrompt
import com.openminis.app.voicecall.ports.CallApprovals
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallTaskUpdate
import com.openminis.app.voicecall.ports.CallTasks
import com.openminis.app.voicecall.ports.TaskReply
import com.openminis.app.voicecall.runtime.CallEndReason
import com.openminis.app.voicecall.runtime.CallEvent
import com.openminis.app.voicecall.runtime.CallLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeCallRuntimeTest {
    private class FakeTransport : RealtimeTransport {
        var listener: RealtimeTransport.Listener? = null
        val sent = mutableListOf<String>()
        var closed = 0
        override fun connect(url: String, headers: Map<String, String>, listener: RealtimeTransport.Listener) { this.listener = listener }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() { closed++ }
        fun types() = sent.map { JSONObject(it).optString("type") }
        fun ofType(type: String) = sent.map(::JSONObject).filter { it.optString("type") == type }
        fun incoming(json: String) = listener!!.onMessage(json)
    }

    private class FakeAudio(var micOk: Boolean = true) : RealtimeAudio {
        var onFrame: ((ByteArray) -> Unit)? = null
        val played = mutableListOf<ByteArray>()
        var flushed = 0
        var stopped = 0
        override fun start(inputSampleRate: Int, outputSampleRate: Int, onFrame: (ByteArray) -> Unit): Boolean {
            this.onFrame = onFrame
            return micOk
        }
        override fun play(pcm: ByteArray) { played += pcm }
        override fun flush() { flushed++ }
        override fun stop() { stopped++ }
    }

    private class FakeModel(var result: suspend (String) -> Result<String> = { Result.success("已完成") }) : CallModelGateway {
        val asked = mutableListOf<String>()
        var cancelled = 0
        var progressLine: String? = null
        override suspend fun ask(text: String): Result<String> { asked += text; return result(text) }
        override fun cancel() { cancelled++ }
        override fun progress(): String? = progressLine
    }

    private class FakeApprovals : CallApprovals {
        override val pending = MutableStateFlow<ApprovalPrompt?>(null)
        val responses = mutableListOf<ApprovalDecision>()
        override fun respond(decision: ApprovalDecision) { responses += decision; pending.value = null }
    }

    private class FakeTasks(var reply: TaskReply = TaskReply("")) : CallTasks {
        override suspend fun overview() = reply
        override suspend fun switchTo(target: String) = reply
        override suspend fun back() = reply
        override val updates: Flow<CallTaskUpdate> = emptyFlow()
        override fun isReported(key: String) = false
    }

    private class H(
        tools: Boolean = true,
        micOk: Boolean = true,
        profile: RealtimeProfile = RealtimeProfiles.OPENAI,
        modelResult: suspend (String) -> Result<String> = { Result.success("已完成") },
        val tasks: FakeTasks = FakeTasks(),
        switched: MutableList<String> = mutableListOf(),
    ) {
        val scope = TestScope(UnconfinedTestDispatcher())
        val transport = FakeTransport()
        val audio = FakeAudio(micOk)
        val model = FakeModel(modelResult)
        val approvals = FakeApprovals()
        val events = mutableListOf<CallEvent>()
        val switchedTo = switched
        val runtime = RealtimeCallRuntime(
            connection = RealtimeConnection("wss://x/rt", emptyMap(), "m", "v", profile),
            instructions = "be brief",
            transport = transport, audio = audio, model = model, scope = scope,
            approvals = approvals, tasks = tasks,
            onSwitchSession = { switchedTo += it },
            delegateByTool = tools,
        )

        init { scope.launch { runtime.events.collect { events += it } } }

        fun begin() { runtime.start(); transport.listener!!.onOpen(); scope.runCurrent() }
        fun ready() { begin(); transport.incoming("""{"type":"session.updated"}"""); scope.runCurrent() }
        fun incoming(json: String) { transport.incoming(json); scope.runCurrent() }
        fun states() = events.filterIsInstance<CallEvent.BloubStateChanged>().map { it.state }
        fun ended() = events.filterIsInstance<CallEvent.Ended>().singleOrNull()
        fun transcripts() = events.filterIsInstance<CallEvent.Transcript>()
    }

    private fun toolCall(id: String, request: String) =
        """{"type":"response.function_call_arguments.done","call_id":"$id","name":"$DELEGATE_TOOL_NAME","arguments":${JSONObject.quote(JSONObject().put("request", request).toString())}}"""

    @Test
    fun `session config is sent on open and the mic stays closed until the server is ready`() {
        val h = H()
        h.begin()
        assertEquals(listOf("session.update"), h.transport.types())
        h.audio.onFrame!!(byteArrayOf(1, 2))
        assertEquals(1, h.transport.sent.size)
        h.incoming("""{"type":"session.updated"}""")
        h.audio.onFrame!!(byteArrayOf(1, 2))
        assertEquals("input_audio_buffer.append", h.transport.types().last())
        assertEquals(BloubState.LISTENING, h.states().last())
    }

    @Test
    fun `tool call is delegated to the agent and the answer goes back as the function output`() {
        val h = H()
        h.ready()
        h.incoming(toolCall("c1", "整理相册"))
        assertEquals(listOf("整理相册"), h.model.asked)
        val out = h.transport.ofType("conversation.item.create").single().getJSONObject("item")
        assertEquals("function_call_output", out.getString("type"))
        assertEquals("c1", out.getString("call_id"))
        assertTrue(out.getString("output").startsWith(BACKEND_PREFIX + "已完成"))
        assertEquals("response.create", h.transport.types().last())
        // 完整回复进聊天记录，模型随后念的结论不再重复进记录。
        assertEquals(listOf("整理相册", "已完成").drop(1), h.transcripts().filter { !it.fromUser }.map { it.text })
        h.incoming("""{"type":"response.output_audio_transcript.done","transcript":"弄好了"}""")
        assertEquals(listOf("已完成"), h.transcripts().filter { !it.fromUser }.map { it.text })
        h.incoming("""{"type":"response.done","response":{"output":[]}}""")
        h.incoming("""{"type":"response.output_audio_transcript.done","transcript":"还有别的吗"}""")
        assertEquals(listOf("已完成", "还有别的吗"), h.transcripts().filter { !it.fromUser }.map { it.text })
    }

    @Test
    fun `long agent output is bounded and prefixed before the model sees it`() {
        val h = H(modelResult = { Result.success("字".repeat(10_000)) })
        h.ready()
        h.incoming(toolCall("c1", "x"))
        val output = h.transport.ofType("conversation.item.create").single().getJSONObject("item").getString("output")
        assertTrue(output.length <= BACKEND_OUTPUT_MAX_CHARS)
        assertTrue(output.startsWith(BACKEND_PREFIX))
    }

    @Test
    fun `delegation request is capped at four kilobytes`() {
        val h = H()
        h.ready()
        h.incoming(toolCall("c1", "长".repeat(9_000)))
        assertEquals(RealtimeLimits.DELEGATION_INPUT_MAX_CHARS, h.model.asked.single().length)
    }

    @Test
    fun `an empty or unknown tool call never reaches the agent but is still answered`() {
        val h = H()
        h.ready()
        h.incoming(toolCall("c1", "  "))
        h.incoming("""{"type":"response.function_call_arguments.done","call_id":"c2","name":"rm_rf","arguments":"{}"}""")
        assertTrue(h.model.asked.isEmpty())
        assertEquals(2, h.transport.ofType("conversation.item.create").size)
    }

    @Test
    fun `items mode hands the user transcript to the agent and injects the answer as a backend item`() {
        val h = H(tools = false)
        h.ready()
        h.incoming("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"今天天气"}""")
        assertEquals(listOf("今天天气"), h.model.asked)
        val item = h.transport.ofType("conversation.item.create").single().getJSONObject("item")
        assertEquals("user", item.getString("role"))
        assertTrue(item.getJSONArray("content").getJSONObject(0).getString("text").startsWith(BACKEND_PREFIX))
        assertTrue(h.transcripts().first().fromUser)
    }

    @Test
    fun `tools mode does not hand the transcript over a second time`() {
        val h = H(tools = true)
        h.ready()
        h.incoming("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}""")
        assertTrue(h.model.asked.isEmpty())
    }

    @Test
    fun `user speech flushes queued audio so barge-in is immediate`() {
        val h = H()
        h.ready()
        h.incoming("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2))}"}""")
        assertEquals(1, h.audio.played.size)
        assertEquals(BloubState.SPEAKING, h.states().last())
        val before = h.audio.flushed
        h.incoming("""{"type":"input_audio_buffer.speech_started"}""")
        assertEquals(before + 1, h.audio.flushed)
        assertEquals(BloubState.LISTENING, h.states().last())
    }

    @Test
    fun `interrupt cancels the response only when the model is speaking and cancels the agent turn`() {
        val h = H()
        h.ready()
        h.runtime.interrupt()
        assertTrue(h.transport.ofType("response.cancel").isEmpty())
        h.incoming("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2))}"}""")
        h.runtime.interrupt()
        assertEquals(1, h.transport.ofType("response.cancel").size)
        assertEquals(2, h.model.cancelled)
        assertEquals(BloubState.LISTENING, h.states().last())
    }

    @Test
    fun `an interrupted delegation never sends its late answer`() {
        val gate = CompletableDeferred<Result<String>>()
        val h = H(modelResult = { gate.await() })
        h.ready()
        h.incoming(toolCall("c1", "慢任务"))
        assertEquals(BloubState.THINKING, h.states().last())
        h.runtime.interrupt()
        gate.complete(Result.success("晚到"))
        h.scope.runCurrent()
        assertTrue(h.transport.ofType("conversation.item.create").isEmpty())
        assertTrue(h.transcripts().none { it.text == "晚到" })
    }

    @Test
    fun `mute closes the mic and drops model audio, unmute reopens it`() {
        val h = H()
        h.ready()
        h.runtime.setMuted(true)
        val n = h.transport.sent.size
        h.audio.onFrame!!(byteArrayOf(1, 2))
        assertEquals(n, h.transport.sent.size)
        h.incoming("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2))}"}""")
        assertTrue(h.audio.played.isEmpty())
        h.runtime.setMuted(false)
        h.audio.onFrame!!(byteArrayOf(1, 2))
        assertEquals(n + 1, h.transport.sent.size)
    }

    @Test
    fun `typed text goes to the agent and is read aloud even when muted`() {
        val h = H()
        h.ready()
        h.runtime.setMuted(true)
        h.runtime.sendText("  查一下磁盘  ")
        assertEquals(listOf("查一下磁盘"), h.model.asked)
        h.incoming("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2))}"}""")
        assertEquals(1, h.audio.played.size)
    }

    // ---- 失败关闭 ----

    @Test
    fun `no microphone ends the call before connecting`() {
        val h = H(micOk = false)
        h.runtime.start()
        assertEquals(CallEndReason.PERMISSION_DENIED, h.ended()!!.reason)
        assertNull(h.transport.listener)
    }

    @Test
    fun `connection failure ends the call and later callbacks are ignored`() {
        val h = H()
        h.begin()
        h.transport.listener!!.onFailure("HTTP 401")
        h.scope.runCurrent()
        assertEquals(CallEndReason.CONNECTION_FAILED, h.ended()!!.reason)
        assertEquals("HTTP 401", h.ended()!!.detail)
        h.incoming("""{"type":"session.updated"}""")
        assertEquals(1, h.events.filterIsInstance<CallEvent.Ended>().size)
        assertTrue(h.transport.closed >= 1 && h.audio.stopped >= 1)
    }

    @Test
    fun `never becoming ready times out`() {
        val h = H()
        h.runtime.start()
        h.scope.advanceTimeBy(RealtimeLimits.CONNECT_TIMEOUT_MS + 1)
        h.scope.runCurrent()
        assertEquals("timeout", h.ended()!!.detail)
    }

    @Test
    fun `an error before ready is fatal but a single error afterwards is not`() {
        val early = H()
        early.begin()
        early.incoming("""{"type":"error","error":{"message":"bad key"}}""")
        assertEquals(CallEndReason.CONNECTION_FAILED, early.ended()!!.reason)

        val late = H()
        late.ready()
        late.incoming("""{"type":"error","error":{"message":"hiccup"}}""")
        assertNull(late.ended())
        late.incoming("""{"type":"error","error":{"message":"hiccup"}}""")
        late.incoming("""{"type":"error","error":{"message":"hiccup"}}""")
        assertEquals(CallEndReason.CONNECTION_FAILED, late.ended()!!.reason)
    }

    @Test
    fun `agent failure is reported to the model once, and repeated failure ends the call`() {
        val h = H(modelResult = { Result.failure(IllegalStateException("boom")) })
        h.ready()
        h.incoming(toolCall("c1", "a"))
        assertNull(h.ended())
        val output = h.transport.ofType("conversation.item.create").single().getJSONObject("item").getString("output")
        assertTrue(output.contains("failed") && output.contains("boom"))
        h.incoming(toolCall("c2", "b"))
        assertEquals(CallEndReason.TASK_FAILED, h.ended()!!.reason)
    }

    @Test
    fun `idle call ends after the idle limit and speaking pauses the timer`() {
        val h = H()
        h.ready()
        h.scope.advanceTimeBy(CallLimits.IDLE_END_MS - 1)
        h.scope.runCurrent()
        assertNull(h.ended())
        h.scope.advanceTimeBy(2)
        h.scope.runCurrent()
        assertEquals(CallEndReason.IDLE_TIMEOUT, h.ended()!!.reason)
    }

    @Test
    fun `stop closes transport and audio and ignores later messages`() {
        val h = H()
        h.ready()
        h.runtime.stop()
        assertTrue(h.transport.closed >= 1 && h.audio.stopped >= 1)
        val n = h.transport.sent.size
        h.incoming(toolCall("c1", "x"))
        assertTrue(h.model.asked.isEmpty())
        assertEquals(n, h.transport.sent.size)
    }

    // ---- 授权 / 任务 ----

    @Test
    fun `permission request is announced to the model and the spoken answer is not sent to the agent`() {
        val h = H(tools = false)
        h.ready()
        h.approvals.pending.value = ApprovalPrompt("要允许删除文件吗", "没听清，请说允许或拒绝")
        h.scope.runCurrent()
        val text = h.transport.ofType("conversation.item.create").last().getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("要允许删除文件吗"))
        h.incoming("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"允许"}""")
        assertEquals(listOf(ApprovalDecision.ALLOW_ONCE), h.approvals.responses)
        assertTrue(h.model.asked.isEmpty())
    }

    @Test
    fun `an unclear answer asks again a bounded number of times and never decides for the user`() {
        val h = H(tools = false)
        h.ready()
        h.approvals.pending.value = ApprovalPrompt("删除吗", "请说允许或拒绝")
        h.scope.runCurrent()
        repeat(CallLimits.MAX_APPROVAL_MISSES + 2) {
            h.incoming("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"嗯让我想想"}""")
            h.incoming("""{"type":"response.done","response":{"output":[]}}""")
        }
        assertTrue(h.approvals.responses.isEmpty())
        val reasks = h.transport.ofType("conversation.item.create").size
        assertEquals(1 + CallLimits.MAX_APPROVAL_MISSES - 1, reasks)
    }

    @Test
    fun `a spoken task command is answered locally and switching waits for the model to finish speaking`() {
        val switched = mutableListOf<String>()
        val h = H(tools = false, tasks = FakeTasks(TaskReply("好的，切换到整理相册", "s2")), switched = switched)
        h.ready()
        h.incoming("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"切换到整理相册任务"}""")
        assertTrue(h.model.asked.isEmpty())
        assertTrue(switched.isEmpty())
        h.incoming("""{"type":"response.done","response":{"output":[]}}""")
        assertEquals(listOf("s2"), switched)
    }

    @Test
    fun `a progress line is only sent when the agent reports a verified one`() {
        val gate = CompletableDeferred<Result<String>>()
        val h = H(modelResult = { gate.await() })
        h.ready()
        h.incoming(toolCall("c1", "长任务"))
        h.scope.advanceTimeBy(CallLimits.WORKING_LINE_DELAY_MS + 1)
        h.scope.runCurrent()
        assertTrue(h.transport.ofType("conversation.item.create").isEmpty())
        h.model.progressLine = "正在运行 shell"
        h.scope.advanceTimeBy(CallLimits.PROGRESS_INTERVAL_MS + 1)
        h.scope.runCurrent()
        val note = h.transport.ofType("conversation.item.create").single().getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(note.contains("正在运行 shell"))
        gate.complete(Result.success("好了"))
        h.scope.runCurrent()
    }

    @Test
    fun `gemini profile drives the same runtime`() {
        val h = H(profile = RealtimeProfiles.GEMINI)
        h.begin()
        assertNotNull(JSONObject(h.transport.sent.single()).optJSONObject("setup"))
        h.transport.incoming("""{"setupComplete":{}}""")
        h.scope.runCurrent()
        h.transport.incoming("""{"toolCall":{"functionCalls":[{"id":"f1","name":"$DELEGATE_TOOL_NAME","args":{"request":"查天气"}}]}}""")
        h.scope.runCurrent()
        assertEquals(listOf("查天气"), h.model.asked)
        val resp = JSONObject(h.transport.sent.last()).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("f1", resp.getString("id"))
        assertFalse(h.ended() != null)
    }

    @Test
    fun `doubao drives the runtime with mute keep alive, direct speech and a graceful close`() {
        val h = H(profile = RealtimeProfiles.DOUBAO)
        h.begin()
        assertEquals(listOf("session.create"), h.transport.types())
        h.incoming("""{"type":"session.created"}""")
        h.incoming("""{"type":"response.function_call_arguments.done","items":[{"call_id":"d1","name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"查天气\"}"}]}""")
        assertEquals(listOf("查天气"), h.model.asked)
        val item = h.transport.ofType("conversation.item.create").single().getJSONArray("items").getJSONObject(0)
        assertEquals("d1", item.getString("call_id"))
        assertTrue(h.transport.ofType("response.create").isEmpty())
        h.incoming("""{"type":"response.done"}""")
        h.runtime.setMuted(true)
        assertEquals("input_audio_mute.commit", h.transport.types().last())
        h.runtime.setMuted(false)
        assertEquals("input_audio_unmute.commit", h.transport.types().last())
        h.approvals.pending.value = ApprovalPrompt("要允许删除文件吗", "没听清")
        h.scope.runCurrent()
        assertEquals("要允许删除文件吗", h.transport.ofType("speech_text_buffer.commit").single().getString("text"))
        val closedBefore = h.transport.closed
        h.runtime.stop()
        assertEquals("session.close", h.transport.types().last())
        assertEquals(closedBefore + 1, h.transport.closed)
    }

    @Test
    fun `no close event is sent when the session never became ready`() {
        val h = H(profile = RealtimeProfiles.DOUBAO)
        h.begin()
        h.runtime.stop()
        assertTrue(h.transport.ofType("session.close").isEmpty())
    }
}
