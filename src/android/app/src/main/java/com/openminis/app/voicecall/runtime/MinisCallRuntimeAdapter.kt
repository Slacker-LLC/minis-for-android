package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CancellationException
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
 * - 空闲：持续没人说话 [IDLE_REMINDER_MS] 后提醒一次，[IDLE_END_MS] 后自动挂断。
 */
object CallLimits {
    const val MAX_RECOGNITION_RETRIES = 2
    const val MAX_TASK_FAILURES = 2
    const val IDLE_REMINDER_MS = 15_000L
    const val IDLE_END_MS = 120_000L
    const val RETRY_DELAY_MS = 400L
    const val ERROR_PAUSE_MS = 1_500L
}

/** ASR → 完整 Agent → TTS。每次聆听独立代数，重复/晚到回调不能再提交任务。 */
class MinisCallRuntimeAdapter(
    private val input: CallSpeechInput,
    private val model: CallModelGateway,
    private val output: CallSpeechOutput,
    private val scope: CoroutineScope,
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

    override fun start() {
        stop()
        active = true
        muted = false
        recognitionRetries = 0
        taskFailures = 0
        _events.tryEmit(CallEvent.MuteChanged(false))
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
        input.cancel()
        output.stop()
        responseJob?.cancel()
        responseJob = null
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
        responseJob?.cancel()
        responseJob = null
        if (!muted) {
            armIdle()
            startListening()
        }
    }

    override fun sendText(text: String) {
        if (!active || muted || !listening || text.isBlank()) return
        acceptText(text, generation)
    }

    override fun listenForFollowUp() {
        if (!active) return
        generation++
        listening = false
        input.cancel()
        output.stop()
        responseJob?.cancel()
        responseJob = null
        if (!muted) {
            armIdle()
            startListening()
        }
    }

    private fun state(state: BloubState) {
        _events.tryEmit(CallEvent.BloubStateChanged(state))
    }

    private fun startListening() {
        if (!active || muted) return
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

    private fun acceptText(text: String, gen: Int) {
        listening = false
        input.cancel()
        // 用户确实说了话：重置识别失败计数和空闲计时。
        recognitionRetries = 0
        idleJob?.cancel()
        idleJob = null
        responseJob = scope.launch {
            state(BloubState.THINKING)
            _events.tryEmit(CallEvent.Transcript(fromUser = true, text = text))
            try {
                val result = model.ask(text)
                if (!valid(gen)) return@launch
                val reply = result.getOrThrow()
                taskFailures = 0
                _events.tryEmit(CallEvent.Transcript(fromUser = false, text = reply))
                if (!muted) {
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
