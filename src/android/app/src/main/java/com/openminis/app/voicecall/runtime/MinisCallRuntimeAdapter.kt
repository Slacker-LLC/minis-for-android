package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.ApprovalPrompt
import com.openminis.app.voicecall.ports.CallApprovals
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallTaskUpdate
import com.openminis.app.voicecall.ports.CallTasks
import com.openminis.app.voicecall.ports.NoCallApprovals
import com.openminis.app.voicecall.ports.NoCallTasks
import com.openminis.app.voicecall.ports.TaskReply
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 通话的容错与空闲边界。常量集中在这里，测试用虚拟时钟直接断言这些数值。
 *
 * - 识别出错：瞬时错误（网络/引擎）最多自动重试 [MAX_RECOGNITION_RETRIES] 次，仍失败就挂断；
 *   缺麦克风权限不重试，直接挂断。
 * - 任务失败：连续 [MAX_TASK_FAILURES] 次才挂断，第一次只提示并继续听。
 * - 进度：提问后 [WORKING_LINE_DELAY_MS] 还没有结果，念一句；之后每 [PROGRESS_INTERVAL_MS] 看一次
 *   已核实的进度（正在跑的工具），有新内容才念，每轮最多 [MAX_PROGRESS_LINES] 句。没有可核实的进度时
 *   只念一次「我在处理」，不重复。
 * - 口头授权：权限请求出现后念出来并听回答，连续 [MAX_APPROVAL_MISSES] 次没听懂就不再追问（屏幕对话框仍可点）。
 * - 空闲：持续没人说话 [IDLE_REMINDER_MS] 后提醒一次，[IDLE_END_MS] 后自动挂断。
 */
object CallLimits {
    const val MAX_RECOGNITION_RETRIES = 2
    const val MAX_TASK_FAILURES = 2
    const val IDLE_REMINDER_MS = 15_000L
    const val IDLE_END_MS = 300_000L
    const val RETRY_DELAY_MS = 400L
    const val ERROR_PAUSE_MS = 1_500L
    const val MAX_APPROVAL_MISSES = 3
    const val WORKING_LINE_DELAY_MS = 4_000L
    const val PROGRESS_INTERVAL_MS = 30_000L
    const val MAX_PROGRESS_LINES = 5
}

/** ASR → 完整 Agent → TTS。每次聆听独立代数，重复/晚到回调不能再提交任务。 */
class MinisCallRuntimeAdapter(
    private val input: CallSpeechInput,
    private val model: CallModelGateway,
    private val output: CallSpeechOutput,
    private val scope: CoroutineScope,
    private val approvals: CallApprovals = NoCallApprovals,
    /** 任务较久、又没有可核实进度时念的过渡语；空串表示不念。 */
    private val workingLine: String = "",
    /** 口头查询/切换任务，以及后台任务状态播报。 */
    private val tasks: CallTasks = NoCallTasks,
    /** 口头切换任务成功：念完确认后由宿主结束通话并跳到该会话。 */
    private val onSwitchSession: (String) -> Unit = {},
) : CallRuntime {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<CallEvent> = _events
    private var generation = 0
    private var active = false
    private var muted = false
    private var listening = false
    private var responseJob: Job? = null
    private var idleJob: Job? = null
    private var recognitionRetries = 0
    private var taskFailures = 0
    private var approvalJob: Job? = null
    private var approvalWatcher: Job? = null
    private var approvalToken = 0
    private var approvalListening = false
    private var approvalMisses = 0
    private var updatesWatcher: Job? = null
    private var updateJob: Job? = null
    private val pendingUpdates = mutableListOf<CallTaskUpdate>()

    override fun start() {
        stop()
        active = true
        muted = false
        recognitionRetries = 0
        taskFailures = 0
        _events.tryEmit(CallEvent.MuteChanged(false))
        pendingUpdates.clear()
        approvalWatcher = scope.launch { approvals.pending.collect { onApprovalChanged(it) } }
        updatesWatcher = scope.launch { tasks.updates.collect { onBackgroundUpdate(it) } }
        armIdle()
        startListening()
    }

    /** 挂断只结束音频和结果等待；已接受的任务留在原会话执行。 */
    override fun stop() {
        active = false
        generation++
        listening = false
        idleJob?.cancel()
        idleJob = null
        approvalWatcher?.cancel()
        approvalWatcher = null
        updatesWatcher?.cancel()
        updatesWatcher = null
        pendingUpdates.clear()
        approvalJob?.cancel()
        approvalJob = null
        approvalToken++
        input.cancel()
        output.stop()
        cancelResponse()
        state(BloubState.IDLE)
    }

    override fun setMuted(muted: Boolean) {
        if (!active || this.muted == muted) return
        this.muted = muted
        _events.tryEmit(CallEvent.MuteChanged(muted))
        if (muted) {
            // 静音是用户主动暂停：不计空闲，也不会因为没人说话被挂断。
            idleJob?.cancel()
            idleJob = null
            if (listening) {
                generation++
                listening = false
                input.cancel()
            }
            output.stop()
            updateJob?.cancel()
            updateJob = null
            cancelApprovalListening()
        } else if (responseJob?.isActive != true) {
            armIdle()
            startListening()
        }
    }

    /** 用户明确打断：只取消该通话当前发起的回合。 */
    override fun interrupt() {
        if (!active) return
        generation++
        listening = false
        input.cancel()
        output.stop()
        model.cancel()
        cancelResponse()
        if (!muted) {
            armIdle()
            startListening()
        }
    }

    /**
     * 键入的文字和语音识别结果走同一条路径，且随时可发：它会打断正在进行的一轮，
     * 回答照样用语音播报。静音只关麦克风，所以静音时发文字也会朗读回答，但回答后不重新开麦。
     */
    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (!active || trimmed.isEmpty()) return
        approvals.pending.value?.let { handleApprovalReply(trimmed, it); return }
        generation++
        listening = false
        input.cancel()
        output.stop()
        model.cancel()
        cancelResponse()
        acceptText(trimmed, generation, typed = true)
    }

    override fun listenForFollowUp() {
        if (!active) return
        generation++
        listening = false
        input.cancel()
        output.stop()
        cancelResponse()
        if (!muted) {
            armIdle()
            startListening()
        }
    }

    /** 取消进行中的一轮（含正在念的后台播报）；不动队列里没念的播报。 */
    private fun cancelResponse() {
        responseJob?.cancel()
        responseJob = null
        updateJob?.cancel()
        updateJob = null
    }

    private fun state(state: BloubState) {
        _events.tryEmit(CallEvent.BloubStateChanged(state))
    }

    private fun startListening() {
        if (!active || muted) return
        // 有后台任务的结果还没念：先念完再开麦，不在用户开口的时候插话。
        if (pendingUpdates.isNotEmpty()) {
            announce()
            return
        }
        val gen = ++generation
        listening = true
        state(BloubState.LISTENING)
        input.start(
            onPartial = { _ -> },
            onFinal = { text -> scope.launch {
                if (valid(gen) && listening) {
                    if (text.isBlank()) retryListening(gen) else acceptText(text, gen)
                }
            } },
            onError = { error, message -> scope.launch {
                if (!valid(gen) || !listening) return@launch
                listening = false
                input.cancel()
                when {
                    error == RecognitionError.NO_MATCH -> retryListening(gen)
                    error == RecognitionError.PERMISSION_DENIED -> end(CallEndReason.PERMISSION_DENIED, message)
                    recognitionRetries < CallLimits.MAX_RECOGNITION_RETRIES -> {
                        recognitionRetries++
                        _events.tryEmit(CallEvent.Notice(CallNotice.RECOGNITION_RETRY, message))
                        state(BloubState.CONFUSED)
                        retryListening(gen)
                    }
                    else -> end(CallEndReason.RECOGNITION_FAILED, message ?: error.name)
                }
            } },
        )
    }

    private fun valid(gen: Int) = active && gen == generation

    private suspend fun retryListening(gen: Int) {
        listening = false
        input.cancel()
        delay(CallLimits.RETRY_DELAY_MS)
        if (valid(gen) && !muted) startListening()
    }

    private fun acceptText(text: String, gen: Int, typed: Boolean = false) {
        listening = false
        input.cancel()
        // 有权限请求在等：这句话是对它的回答，不交给模型。
        approvals.pending.value?.let {
            handleApprovalReply(text, it)
            return
        }
        // 用户确实说了话：重置识别失败计数和空闲计时。
        recognitionRetries = 0
        idleJob?.cancel()
        idleJob = null
        val command = parseTaskCommand(text)
        responseJob = scope.launch {
            state(BloubState.THINKING)
            _events.tryEmit(CallEvent.Transcript(fromUser = true, text = text))
            try {
                if (command != null && handleTaskCommand(command, gen, typed)) return@launch
                // 长任务期间的口头汇报：只念已核实的进度；答案一到，正在念的那句念完就让位。
                var speakingDone: CompletableDeferred<Unit>? = null
                var answered = false
                val progress = launch {
                    delay(CallLimits.WORKING_LINE_DELAY_MS)
                    var last: String? = null
                    var count = 0
                    while (count < CallLimits.MAX_PROGRESS_LINES) {
                        val line = model.progress() ?: workingLine.takeIf { it.isNotBlank() }
                        if (line != null && line != last && valid(gen) && !muted && approvals.pending.value == null) {
                            last = line
                            count++
                            val done = CompletableDeferred<Unit>()
                            speakingDone = done
                            try {
                                state(BloubState.SPEAKING)
                                output.speak(line)
                            } finally {
                                speakingDone = null
                                done.complete(Unit)
                            }
                            if (valid(gen) && !answered) state(BloubState.THINKING)
                        }
                        delay(CallLimits.PROGRESS_INTERVAL_MS)
                    }
                }
                val result = model.ask(text)
                answered = true
                // 正念到一半的进度句让它念完，不要和回答抢播放器。
                speakingDone?.await()
                progress.cancel()
                if (!valid(gen)) return@launch
                val reply = result.getOrThrow()
                taskFailures = 0
                _events.tryEmit(CallEvent.Transcript(fromUser = false, text = reply))
                if (!muted || typed) {
                    state(BloubState.SPEAKING)
                    output.speak(reply)
                }
                if (valid(gen) && !muted) {
                    armIdle()
                    startListening()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (valid(gen)) onTaskFailed(gen, error.message)
            }
        }
    }

    /**
     * 口头任务指令。返回 true 表示已经处理（念了回答/切走了）；任务端口没有回应时返回 false，
     * 让这句话照常交给模型。
     */
    private suspend fun handleTaskCommand(command: TaskCommand, gen: Int, typed: Boolean): Boolean {
        val reply: TaskReply = when (command) {
            TaskCommand.ListTasks -> tasks.overview()
            is TaskCommand.Switch -> tasks.switchTo(command.target)
            TaskCommand.Back -> tasks.back()
        }
        if (reply.spoken.isBlank()) return false
        if (!valid(gen)) return true
        taskFailures = 0
        _events.tryEmit(CallEvent.Transcript(fromUser = false, text = reply.spoken))
        if (!muted || typed) {
            state(BloubState.SPEAKING)
            output.speak(reply.spoken)
        }
        if (!valid(gen)) return true
        val target = reply.switchToSessionId
        if (target != null) onSwitchSession(target)
        else if (!muted) {
            armIdle()
            startListening()
        }
        return true
    }

    /**
     * 通话期间别处/后台任务的状态变化。静音只记到聊天记录不出声；正在回答、听授权或念别的播报时先排队，
     * 等回到「等用户开口」时再念；此刻正在听用户说话的，直接收声念出来。
     */
    private fun onBackgroundUpdate(update: CallTaskUpdate) {
        if (!active || tasks.isReported(update.key)) return
        if (muted) {
            _events.tryEmit(CallEvent.Transcript(fromUser = false, text = update.spoken))
            return
        }
        pendingUpdates += update
        val busy = responseJob?.isActive == true || approvalListening || approvals.pending.value != null
        if (busy || updateJob?.isActive == true) return
        if (listening) {
            generation++
            listening = false
            input.cancel()
        }
        announce()
    }

    /** 把排队的播报念完，再回到聆听。念的过程中新到的也一并念。 */
    private fun announce() {
        if (updateJob?.isActive == true) return
        val gen = ++generation
        listening = false
        idleJob?.cancel()
        idleJob = null
        updateJob = scope.launch {
            while (valid(gen)) {
                val update = pendingUpdates.removeFirstOrNull() ?: break
                if (tasks.isReported(update.key)) continue
                _events.tryEmit(CallEvent.Transcript(fromUser = false, text = update.spoken))
                state(BloubState.SPEAKING)
                output.speak(update.spoken)
            }
            if (valid(gen) && !muted) {
                armIdle()
                startListening()
            }
        }
    }

    private suspend fun onTaskFailed(gen: Int, detail: String?) {
        taskFailures++
        state(BloubState.ERROR)
        if (taskFailures >= CallLimits.MAX_TASK_FAILURES) {
            end(CallEndReason.TASK_FAILED, detail)
            return
        }
        _events.tryEmit(CallEvent.Notice(CallNotice.TASK_FAILED, detail))
        // 让错误表情停一下，再回到聆听，不把用户晾在错误页。
        delay(CallLimits.ERROR_PAUSE_MS)
        if (valid(gen) && !muted) {
            armIdle()
            startListening()
        }
    }

    /** 权限请求出现/消失。出现：念出问题再听回答；消失（屏幕上点了或已回答）：恢复原来的流程。 */
    private fun onApprovalChanged(prompt: ApprovalPrompt?) {
        if (!active) return
        approvalToken++
        approvalJob?.cancel()
        approvalJob = null
        if (prompt == null) {
            if (approvalListening) {
                approvalListening = false
                input.cancel()
                if (responseJob?.isActive == true) state(BloubState.THINKING)
                else if (!muted) {
                    armIdle()
                    startListening()
                }
            }
            return
        }
        approvalMisses = 0
        if (muted) return
        // 此时若在聆听普通一轮，不会有进行中的回合；挂起的回合由 responseJob 持有，代数不能动。
        if (listening) {
            generation++
            listening = false
            input.cancel()
        }
        idleJob?.cancel()
        idleJob = null
        if (updateJob?.isActive == true) {
            updateJob?.cancel()
            updateJob = null
            output.stop()
        }
        approvalListening = true
        askApproval(prompt.question)
    }

    private fun askApproval(line: String) {
        val token = approvalToken
        approvalJob = scope.launch {
            state(BloubState.SPEAKING)
            output.speak(line)
            if (token == approvalToken && active && !muted) listenForApproval(token)
        }
    }

    private fun listenForApproval(token: Int) {
        state(BloubState.LISTENING)
        input.start(
            onPartial = { _ -> },
            onFinal = { text -> scope.launch { if (token == approvalToken && active) approvals.pending.value?.let { handleApprovalReply(text, it) } } },
            onError = { _, _ -> scope.launch { if (token == approvalToken && active) approvals.pending.value?.let { missedApproval(it) } } },
        )
    }

    private fun handleApprovalReply(text: String, prompt: ApprovalPrompt) {
        val decision = parseApprovalPhrase(text)
        if (decision != null) {
            // pending 变 null 后由 onApprovalChanged 恢复流程。
            approvalToken++
            input.cancel()
            approvals.respond(decision)
        } else missedApproval(prompt)
    }

    private fun missedApproval(prompt: ApprovalPrompt) {
        input.cancel()
        if (++approvalMisses >= CallLimits.MAX_APPROVAL_MISSES) {
            // 不再追问；对话框还在屏幕上，用户可以点。
            cancelApprovalListening()
            if (responseJob?.isActive != true && !muted) {
                armIdle()
                startListening()
            }
            return
        }
        approvalToken++
        askApproval(prompt.unclear)
    }

    private fun cancelApprovalListening() {
        approvalToken++
        approvalJob?.cancel()
        approvalJob = null
        if (approvalListening) {
            approvalListening = false
            input.cancel()
            if (responseJob?.isActive == true) state(BloubState.THINKING)
        }
    }

    /** 重新开始空闲计时：只在「等用户开口」的阶段运行，思考/播报/静音期间不计。 */
    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(CallLimits.IDLE_REMINDER_MS)
            _events.tryEmit(CallEvent.Notice(CallNotice.IDLE_REMINDER))
            delay(CallLimits.IDLE_END_MS - CallLimits.IDLE_REMINDER_MS)
            end(CallEndReason.IDLE_TIMEOUT, null)
        }
    }

    /** 自动挂断：停音频后通知 UI 展示结束页。 */
    private fun end(reason: CallEndReason, detail: String?) {
        if (!active) return
        stop()
        _events.tryEmit(CallEvent.Ended(reason, detail))
    }
}
