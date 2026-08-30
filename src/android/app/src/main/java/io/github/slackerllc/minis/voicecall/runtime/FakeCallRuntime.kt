package io.github.slackerllc.minis.voicecall.runtime

import io.github.slackerllc.minis.voicecall.model.BloubState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 演示用假 CallRuntime：手动触发状态 + sendText 模拟一轮 AI 对话。 */
class FakeCallRuntime : CallRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallEvent> = _events

    override fun start() {}
    override fun stop() {}

    override fun setMuted(muted: Boolean) {
        _events.tryEmit(CallEvent.MuteChanged(muted))
    }

    override fun interrupt() {
        _events.tryEmit(CallEvent.BloubStateChanged(BloubState.LISTENING))
    }

    fun emitBloubState(state: BloubState) {
        _events.tryEmit(CallEvent.BloubStateChanged(state))
    }

    fun emitSubtitle(text: String) {
        _events.tryEmit(CallEvent.SubtitleChanged(text))
    }

    override fun sendText(text: String) {
        scope.launch {
            _events.emit(CallEvent.BloubStateChanged(BloubState.LISTENING))
            delay(500)
            _events.emit(CallEvent.BloubStateChanged(BloubState.THINKING))
            delay(800)
            _events.emit(CallEvent.BloubStateChanged(BloubState.SPEAKING))
            delay(1400)
            _events.emit(CallEvent.BloubStateChanged(BloubState.LISTENING))
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
