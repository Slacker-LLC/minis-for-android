package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.ports.CallModelGateway
import com.openminis.app.voicecall.ports.CallSpeechInput
import com.openminis.app.voicecall.ports.CallSpeechOutput
import com.openminis.app.voicecall.ports.RecognitionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 生产通话 Runtime：把三个端口（ASR / 模型 / TTS）编排成
 * LISTENING → THINKING → SPEAKING → LISTENING 半双工循环。
 *
 * 阶段 A 只依赖端口接口，测试用 Fake 端口补齐闭环；阶段 B/C 注入
 * SpeechRecognitionManager / PetChatEngine / ReadAloudPlayer 的生产实现。
 * 所有回调都带 generation 校验：挂断/打断后，晚到的 ASR/模型/TTS 回调
 * 不得再修改状态（文档「生命周期：旧回调不能修改新通话的 UI 状态」）。
 */
class MinisCallRuntimeAdapter(
    private val input: CallSpeechInput,
    private val model: CallModelGateway,
    private val output: CallSpeechOutput,
    private val scope: CoroutineScope,
) : CallRuntime {

    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallEvent> = _events

    /** 代数：start/stop/interrupt 递增，旧回调以此失效。 */
    private var generation = 0
    private var muted = false

    override fun start() {
        muted = false
        startListening()
    }

    override fun stop() {
        generation++
        input.cancel()
        model.cancel()
        output.stop()
        _events.tryEmit(CallEvent.BloubStateChanged(BloubState.IDLE))
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        _events.tryEmit(CallEvent.MuteChanged(muted))
        if (muted) {
            generation++
            input.cancel()
            output.stop()
        } else {
            startListening()
        }
    }

    override fun interrupt() {
        generation++
        input.cancel()
        model.cancel()
        output.stop()
        startListening()
    }

    /** 文字入口：跳过 ASR，直接跑一轮模型+TTS（调试/演示）。 */
    override fun sendText(text: String) {
        val gen = generation
        scope.launch {
            if (gen != generation) return@launch
            _events.emit(CallEvent.BloubStateChanged(BloubState.THINKING))
            _events.emit(CallEvent.SubtitleChanged(text))
            respond(text, gen)
        }
    }

    private fun startListening() {
        val gen = generation
        _events.tryEmit(CallEvent.BloubStateChanged(BloubState.LISTENING))
        _events.tryEmit(CallEvent.SubtitleChanged("我在听…"))
        input.start(
            onPartial = { partial ->
                if (gen == generation) _events.tryEmit(CallEvent.SubtitleChanged(partial))
            },
            onFinal = { finalText ->
                if (gen == generation && !muted) onFinalText(finalText, gen)
            },
            onError = { error, message ->
                if (gen == generation) onError(error, message, gen)
            },
        )
    }

    private fun onFinalText(text: String, gen: Int) {
        scope.launch {
            if (gen != generation) return@launch
            _events.emit(CallEvent.BloubStateChanged(BloubState.THINKING))
            _events.emit(CallEvent.SubtitleChanged(text))
            respond(text, gen)
        }
    }

    private suspend fun respond(text: String, gen: Int) {
        val result = model.ask(text)
        if (gen != generation) return
        result.fold(
            onSuccess = { reply ->
                _events.emit(CallEvent.BloubStateChanged(BloubState.SPEAKING))
                _events.emit(CallEvent.SubtitleChanged(reply))
                output.speak(reply)
                if (gen == generation && !muted) startListening()
            },
            onFailure = { e ->
                _events.emit(CallEvent.BloubStateChanged(BloubState.ERROR))
                _events.emit(CallEvent.SubtitleChanged("出错了：${e.message ?: "未知错误"}"))
                delay(900)
                if (gen == generation) startListening()
            },
        )
    }

    private fun onError(error: RecognitionError, message: String?, gen: Int) {
        scope.launch {
            when (error) {
                RecognitionError.NO_MATCH -> {
                    _events.emit(CallEvent.BloubStateChanged(BloubState.CONFUSED))
                    _events.emit(CallEvent.SubtitleChanged("没听清，请再说一遍"))
                    delay(900)
                    if (gen == generation && !muted) startListening()
                }
                else -> {
                    _events.emit(CallEvent.BloubStateChanged(BloubState.ERROR))
                    _events.emit(CallEvent.SubtitleChanged("识别失败：${message ?: error.name}"))
                    delay(900)
                    if (gen == generation && !muted) startListening()
                }
            }
        }
    }
}
