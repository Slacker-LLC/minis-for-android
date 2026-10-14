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
 * 运行时可见文案由 UI 层注入，避免 runtime 里写死某一种语言。
 * 默认值只覆盖不接 UI 的调用方（例如 androidTest 的脚本化通话）。
 */
class CallRuntimeTexts(
    val listening: String = "Listening…",
    val recognitionFailed: (String) -> String = {
        "Recognition failed: $it. Check the speech service and retry."
    },
    /** [detail] is null when the failure carried no message of its own. */
    val taskIncomplete: (String?) -> String = { detail ->
        "Task not finished" + if (detail.isNullOrBlank()) "" else ": $detail"
    },
)

/** ASR → 完整 Agent → TTS。每次聆听独立代数，重复/晚到回调不能再提交任务。 */
class MinisCallRuntimeAdapter(
    private val input: CallSpeechInput,
    private val model: CallModelGateway,
    private val output: CallSpeechOutput,
    private val scope: CoroutineScope,
    private val texts: CallRuntimeTexts = CallRuntimeTexts(),
) : CallRuntime {
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallEvent> = _events
    private var generation = 0
    private var active = false
    private var muted = false
    private var listening = false
    private var responseJob: Job? = null

    override fun start() {
        stop()
        active = true
        muted = false
        _events.tryEmit(CallEvent.MuteChanged(false))
        startListening()
    }

    /** 挂断只结束音频和结果等待；已接受的任务留在原会话执行。 */
    override fun stop() {
        active = false
        generation++
        listening = false
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
            if (listening) {
                generation++
                listening = false
                input.cancel()
            }
            output.stop()
        } else if (responseJob?.isActive != true) {
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
        if (!muted) startListening()
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
        if (!muted) startListening()
    }

    private fun state(state: BloubState) {
        _events.tryEmit(CallEvent.BloubStateChanged(state))
    }

    private fun subtitle(text: String) {
        _events.tryEmit(CallEvent.SubtitleChanged(text))
    }

    private fun startListening() {
        if (!active || muted) return
        val gen = ++generation
        listening = true
        state(BloubState.LISTENING)
        subtitle(texts.listening)
        input.start(
            onPartial = { partial -> scope.launch {
                if (valid(gen) && listening) subtitle(partial)
            } },
            onFinal = { text -> scope.launch {
                if (valid(gen) && listening) {
                    if (text.isBlank()) retryListening(gen) else acceptText(text, gen)
                }
            } },
            onError = { error, message -> scope.launch {
                if (!valid(gen) || !listening) return@launch
                listening = false
                input.cancel()
                if (error == RecognitionError.NO_MATCH) {
                    retryListening(gen)
                } else {
                    // 配置/权限/网络错误留在界面，显式重试，避免持续耗电/请求。
                    state(BloubState.ERROR)
                    subtitle(texts.recognitionFailed(message ?: error.name))
                }
            } },
        )
    }

    private fun valid(gen: Int) = active && gen == generation

    private suspend fun retryListening(gen: Int) {
        listening = false
        input.cancel()
        delay(400)
        if (valid(gen) && !muted) startListening()
    }

    private fun acceptText(text: String, gen: Int) {
        listening = false
        input.cancel()
        responseJob = scope.launch {
            state(BloubState.THINKING)
            subtitle(text)
            try {
                val result = model.ask(text)
                if (!valid(gen)) return@launch
                val reply = result.getOrThrow()
                subtitle(reply)
                if (!muted) {
                    state(BloubState.SPEAKING)
                    output.speak(reply)
                }
                if (valid(gen) && !muted) startListening()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (valid(gen)) {
                    state(BloubState.ERROR)
                    subtitle(texts.taskIncomplete(error.message))
                }
            }
        }
    }
}
