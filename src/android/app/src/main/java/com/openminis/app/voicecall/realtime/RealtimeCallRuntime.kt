package com.openminis.app.voicecall.realtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.ApprovalPrompt
import com.openminis.app.voicecall.ports.CallApprovals
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallTaskUpdate
import com.openminis.app.voicecall.ports.CallTasks
import com.openminis.app.voicecall.ports.NoCallApprovals
import com.openminis.app.voicecall.ports.NoCallTasks
import com.openminis.app.voicecall.ports.TaskReply
import com.openminis.app.voicecall.runtime.CallEndReason
import com.openminis.app.voicecall.runtime.CallEvent
import com.openminis.app.voicecall.runtime.CallLimits
import com.openminis.app.voicecall.runtime.CallNotice
import com.openminis.app.voicecall.runtime.CallRuntime
import com.openminis.app.voicecall.runtime.TaskCommand
import com.openminis.app.voicecall.runtime.parseApprovalPhrase
import com.openminis.app.voicecall.runtime.parseTaskCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 全双工实时模型驱动的通话（与 [com.openminis.app.voicecall.runtime.MinisCallRuntimeAdapter] 并列，二选一）。
 *
 * 结构对齐 Codex 语音模式：实时模型听和说，真正干活的是现有 Agent（[model]）。
 * - 工具模式（[delegateByTool]）：模型调用 `delegate_to_agent`，结果作为函数返回值交回；
 * - 条目模式：模型不自动回复，用户说完后我们把转写交给 Agent，结果以 `[BACKEND] ` 条目注入再让模型念
 *   （对应 Codex 的 `codex_responses_as_items`）。
 * 权限请求、口头任务指令、后台任务播报、空闲挂断、静音、打断都复用通话已有的端口和限值。
 *
 * 所有状态只在 [scope] 的协程里读写；传输回调和音频帧回调只做转发。
 */
class RealtimeCallRuntime(
    private val connection: RealtimeConnection,
    private val instructions: String,
    private val transport: RealtimeTransport,
    private val audio: RealtimeAudio,
    private val model: CallModelGateway,
    private val scope: CoroutineScope,
    private val approvals: CallApprovals = NoCallApprovals,
    private val tasks: CallTasks = NoCallTasks,
    private val onSwitchSession: (String) -> Unit = {},
    private val codec: RealtimeCodec = connection.profile.newCodec(),
    private val delegateByTool: Boolean = connection.profile.supportsTools,
) : CallRuntime {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CallEvent> = _events

    private var active = false
    private var muted = false
    private var ready = false
    private var sessionToken = 0
    private var delegationGen = 0
    private var modelBusy = false
    private var suppressAssistantText = false
    private var typedReply = false
    private var serverErrors = 0
    private var taskFailures = 0
    private var approvalMisses = 0
    private var switchAfterResponse: String? = null
    private var connectJob: Job? = null
    private var delegationJob: Job? = null
    private var idleJob: Job? = null
    private var approvalWatcher: Job? = null
    private var updatesWatcher: Job? = null

    /** 麦克风帧是否送出。采集线程读，所以用 volatile。 */
    @Volatile private var micOpen = false
    private val notes = ArrayDeque<Note>()

    private class Note(val text: String, val urgent: Boolean, val spoken: String = text)

    override fun start() {
        stop()
        active = true
        muted = false
        ready = false
        taskFailures = 0
        serverErrors = 0
        approvalMisses = 0
        _events.tryEmit(CallEvent.MuteChanged(false))
        state(BloubState.THINKING)
        val token = ++sessionToken
        val micOk = audio.start(codec.inputSampleRate, codec.outputSampleRate) { frame ->
            if (micOpen) transport.send(codec.audioIn(frame))
        }
        if (!micOk) {
            end(CallEndReason.PERMISSION_DENIED, null)
            return
        }
        approvalWatcher = scope.launch { approvals.pending.collect { if (token == sessionToken) onApprovalChanged(it) } }
        updatesWatcher = scope.launch { tasks.updates.collect { if (token == sessionToken) onBackgroundUpdate(it) } }
        connectJob = scope.launch {
            delay(RealtimeLimits.CONNECT_TIMEOUT_MS)
            if (token == sessionToken && !ready) end(CallEndReason.CONNECTION_FAILED, "timeout")
        }
        transport.connect(connection.url, connection.headers, object : RealtimeTransport.Listener {
            override fun onOpen() { post(token) { send(codec.open(sessionConfig())) } }
            override fun onMessage(text: String) { post(token) { codec.parse(text).forEach(::onEvent) } }
            override fun onClosed(reason: String?) { post(token) { end(CallEndReason.CONNECTION_FAILED, reason ?: "closed") } }
            override fun onFailure(message: String) { post(token) { end(CallEndReason.CONNECTION_FAILED, message) } }
        })
    }

    override fun stop() {
        val wasReady = active && ready
        active = false
        micOpen = false
        ready = false
        sessionToken++
        delegationGen++
        modelBusy = false
        suppressAssistantText = false
        typedReply = false
        switchAfterResponse = null
        notes.clear()
        connectJob?.cancel(); connectJob = null
        delegationJob?.cancel(); delegationJob = null
        idleJob?.cancel(); idleJob = null
        approvalWatcher?.cancel(); approvalWatcher = null
        updatesWatcher?.cancel(); updatesWatcher = null
        if (wasReady) send(codec.closeSession())
        transport.close()
        audio.stop()
        state(BloubState.IDLE)
    }

    override fun setMuted(muted: Boolean) {
        if (!active || this.muted == muted) return
        this.muted = muted
        _events.tryEmit(CallEvent.MuteChanged(muted))
        if (muted) {
            // 静音是用户主动暂停：不计空闲，也不再说话。
            micOpen = false
            idleJob?.cancel(); idleJob = null
            audio.flush()
            if (ready) send(codec.muteSignal(true))
        } else if (ready) {
            send(codec.muteSignal(false))
            micOpen = true
            if (delegationJob?.isActive != true && !modelBusy) {
                state(BloubState.LISTENING)
                armIdle()
            }
        }
    }

    /** 用户明确打断：停掉播放、取消模型回复，并取消该通话发起的 Agent 回合。 */
    override fun interrupt() {
        if (!active) return
        silenceModel()
        delegationGen++
        delegationJob?.cancel(); delegationJob = null
        model.cancel()
        backToListening()
    }

    /** 保留已接受的任务，只停掉当前的说话并回到聆听。 */
    override fun listenForFollowUp() {
        if (!active) return
        silenceModel()
        delegationGen++
        delegationJob?.cancel(); delegationJob = null
        if (muted) return
        micOpen = ready
        backToListening()
    }

    /** 键入的文字直接交给 Agent，结果仍由语音念出来（静音时也念，因为是用户主动发的）。 */
    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (!active || trimmed.isEmpty()) return
        approvals.pending.value?.let { handleApprovalReply(trimmed, it); return }
        silenceModel()
        model.cancel()
        typedReply = true
        _events.tryEmit(CallEvent.Transcript(fromUser = true, text = trimmed))
        delegate(trimmed, callId = null)
    }

    // ---- 事件 ----

    private fun onEvent(event: RealtimeEvent) {
        if (!active) return
        when (event) {
            RealtimeEvent.Ready -> onReady()
            is RealtimeEvent.AudioOut -> {
                serverErrors = 0
                if (muted && !typedReply) return
                modelBusy = true
                idleJob?.cancel(); idleJob = null
                state(BloubState.SPEAKING)
                audio.play(event.pcm)
            }
            RealtimeEvent.UserSpeechStarted -> {
                audio.flush()
                modelBusy = false
                idleJob?.cancel(); idleJob = null
                if (delegationJob?.isActive != true) state(BloubState.LISTENING)
            }
            is RealtimeEvent.UserTranscript -> onUserTranscript(event.text)
            is RealtimeEvent.AssistantText -> {
                if (event.final && event.text.isNotBlank() && !suppressAssistantText) {
                    _events.tryEmit(CallEvent.Transcript(fromUser = false, text = event.text.trim()))
                }
            }
            RealtimeEvent.ResponseDone -> onResponseDone()
            is RealtimeEvent.ToolCall -> onToolCall(event)
            is RealtimeEvent.Failure -> onServerError(event.message)
        }
    }

    private fun onReady() {
        if (ready) return
        ready = true
        connectJob?.cancel(); connectJob = null
        micOpen = !muted
        if (muted) send(codec.muteSignal(true))
        state(BloubState.LISTENING)
        armIdle()
        approvals.pending.value?.let { onApprovalChanged(it) }
    }

    private fun onResponseDone() {
        modelBusy = false
        suppressAssistantText = false
        typedReply = false
        val target = switchAfterResponse
        if (target != null) {
            switchAfterResponse = null
            onSwitchSession(target)
            return
        }
        if (delegationJob?.isActive == true) {
            state(BloubState.THINKING)
        } else {
            state(BloubState.LISTENING)
            if (!muted) armIdle()
        }
        flushNotes()
    }

    private fun onServerError(message: String) {
        if (!ready) {
            end(CallEndReason.CONNECTION_FAILED, message)
            return
        }
        // 会话建立后的单次错误（例如取消了一个已经结束的回复）不致命；连续出错才挂断。
        if (++serverErrors >= RealtimeLimits.MAX_SERVER_ERRORS) {
            end(CallEndReason.CONNECTION_FAILED, message)
        } else {
            _events.tryEmit(CallEvent.Notice(CallNotice.TASK_FAILED, message))
        }
    }

    private fun onUserTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        serverErrors = 0
        idleJob?.cancel(); idleJob = null
        _events.tryEmit(CallEvent.Transcript(fromUser = true, text = trimmed))
        approvals.pending.value?.let {
            handleApprovalReply(trimmed, it)
            return
        }
        val command = parseTaskCommand(trimmed)
        scope.launch {
            if (command != null && handleTaskCommand(command)) return@launch
            // 条目模式下模型不会自己回复，用户说完就交给 Agent。
            if (!delegateByTool) delegate(trimmed, callId = null)
        }
    }

    private fun onToolCall(call: RealtimeEvent.ToolCall) {
        if (call.name != DELEGATE_TOOL_NAME) {
            send(codec.toolResult(call.callId, call.name, "Unknown tool: ${call.name}"))
            modelBusy = true
            return
        }
        val request = runCatching { JSONObject(call.argsJson).optString("request") }.getOrDefault("").trim()
        if (request.isEmpty()) {
            send(codec.toolResult(call.callId, call.name, backendOutput("No request was provided. Ask the user what they need.")))
            modelBusy = true
            return
        }
        delegate(request, call.callId)
    }

    // ---- 交接给 Agent ----

    private fun delegate(request: String, callId: String?) {
        idleJob?.cancel(); idleJob = null
        delegationJob?.cancel()
        val gen = ++delegationGen
        state(BloubState.THINKING)
        delegationJob = scope.launch {
            // 长任务期间只汇报已核实的进度（对应 Codex 的 Commentary 阶段）。
            val progress = launch {
                delay(CallLimits.WORKING_LINE_DELAY_MS)
                var last: String? = null
                var count = 0
                while (count < CallLimits.MAX_PROGRESS_LINES) {
                    val line = model.progress()
                    if (line != null && line != last && gen == delegationGen && !modelBusy && !muted) {
                        last = line
                        count++
                        sendNote(Note("Progress: $line", urgent = true, spoken = line))
                    }
                    delay(CallLimits.PROGRESS_INTERVAL_MS)
                }
            }
            val outcome = try {
                model.ask(request.take(RealtimeLimits.DELEGATION_INPUT_MAX_CHARS))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                progress.cancel()
            }
            if (gen != delegationGen || !active) return@launch
            val reply = outcome.getOrNull()
            val output: String
            if (reply != null) {
                taskFailures = 0
                _events.tryEmit(CallEvent.Transcript(fromUser = false, text = reply))
                output = backendOutput(reply)
            } else {
                val detail = outcome.exceptionOrNull()?.message
                taskFailures++
                _events.tryEmit(CallEvent.Notice(CallNotice.TASK_FAILED, detail))
                if (taskFailures >= CallLimits.MAX_TASK_FAILURES) {
                    end(CallEndReason.TASK_FAILED, detail)
                    return@launch
                }
                output = backendOutput("The task failed${detail?.let { ": $it" }.orEmpty()}. Tell the user plainly.")
            }
            suppressAssistantText = true
            modelBusy = true
            send(if (callId != null) codec.toolResult(callId, DELEGATE_TOOL_NAME, output) else codec.backendItem(output))
        }
    }

    // ---- 口头任务指令 ----

    /** 已处理返回 true；任务端口没有回应时返回 false，让这句话照常交给模型。 */
    private suspend fun handleTaskCommand(command: TaskCommand): Boolean {
        val reply: TaskReply = when (command) {
            TaskCommand.ListTasks -> tasks.overview()
            is TaskCommand.Switch -> tasks.switchTo(command.target)
            TaskCommand.Back -> tasks.back()
        }
        if (reply.spoken.isBlank() || !active) return false
        silenceModel()
        _events.tryEmit(CallEvent.Transcript(fromUser = false, text = reply.spoken))
        suppressAssistantText = true
        switchAfterResponse = reply.switchToSessionId
        sendNote(Note(reply.spoken, urgent = true), force = true)
        return true
    }

    // ---- 权限请求 ----

    private fun onApprovalChanged(prompt: ApprovalPrompt?) {
        if (!active || !ready) return
        approvalMisses = 0
        if (prompt == null) return
        // 实时模型自己念出问题；用户的回答由转写解析，不交给 Agent。
        sendNote(Note("Permission needed from the user: ${prompt.question} Ask them aloud and wait for a yes or no.", urgent = true, spoken = prompt.question))
    }

    private fun handleApprovalReply(text: String, prompt: ApprovalPrompt) {
        val decision = parseApprovalPhrase(text)
        if (decision != null) {
            silenceModel()
            approvals.respond(decision)
            return
        }
        if (++approvalMisses < CallLimits.MAX_APPROVAL_MISSES) {
            sendNote(Note("The answer was not clear. Ask again: ${prompt.unclear}", urgent = true, spoken = prompt.unclear))
        }
    }

    // ---- 后台任务播报 ----

    private fun onBackgroundUpdate(update: CallTaskUpdate) {
        if (!active || tasks.isReported(update.key)) return
        _events.tryEmit(CallEvent.Transcript(fromUser = false, text = update.spoken))
        if (muted) return
        sendNote(Note(update.spoken, urgent = false))
    }

    // ---- 注入文本 ----

    /**
     * 把一段后端文本交给模型念。模型正在说话时先排队；非紧急的还要等 Agent 回合结束，
     * 以免插在函数调用和它的返回值之间。
     */
    private fun sendNote(note: Note, force: Boolean = false) {
        notes.addLast(note)
        flushNotes(force)
    }

    private fun flushNotes(force: Boolean = false) {
        if (!active || !ready || notes.isEmpty()) return
        if (modelBusy && !force) return
        val next = notes.firstOrNull { it.urgent || delegationJob?.isActive != true } ?: return
        notes.remove(next)
        idleJob?.cancel(); idleJob = null
        modelBusy = true
        send(codec.speak(next.spoken) ?: codec.backendItem(backendOutput(next.text)))
    }

    // ---- 工具 ----

    /** 停掉播放，并取消模型正在进行的回复（没有进行中的回复就不发取消，避免服务端报错）。 */
    private fun silenceModel() {
        audio.flush()
        if (modelBusy) send(codec.cancelResponse())
        modelBusy = false
        suppressAssistantText = false
    }

    private fun backToListening() {
        if (muted) return
        state(BloubState.LISTENING)
        armIdle()
    }

    private fun send(frames: List<String>) {
        frames.forEach { transport.send(it) }
    }

    private fun sessionConfig() = RealtimeSessionConfig(
        model = connection.model,
        instructions = instructions,
        voice = connection.voice,
        delegateByTool = delegateByTool,
    )

    private fun post(token: Int, block: () -> Unit) {
        scope.launch { if (token == sessionToken && active) block() }
    }

    private fun state(state: BloubState) {
        _events.tryEmit(CallEvent.BloubStateChanged(state))
    }

    /** 重新开始空闲计时：只在「等用户开口」的阶段运行。 */
    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(CallLimits.IDLE_REMINDER_MS)
            _events.tryEmit(CallEvent.Notice(CallNotice.IDLE_REMINDER))
            delay(CallLimits.IDLE_END_MS - CallLimits.IDLE_REMINDER_MS)
            end(CallEndReason.IDLE_TIMEOUT, null)
        }
    }

    private fun end(reason: CallEndReason, detail: String?) {
        if (!active) return
        stop()
        _events.tryEmit(CallEvent.Ended(reason, detail))
    }
}
