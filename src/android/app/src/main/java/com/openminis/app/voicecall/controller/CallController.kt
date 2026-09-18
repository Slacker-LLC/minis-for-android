package com.openminis.app.voicecall.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openminis.app.voicecall.bloub.BloubStateMapper
import com.openminis.app.voicecall.model.BloubState
import com.openminis.app.voicecall.model.CallAiState
import com.openminis.app.voicecall.model.CallMode
import com.openminis.app.voicecall.model.CallUiState
import com.openminis.app.voicecall.runtime.CallEvent
import com.openminis.app.voicecall.runtime.CallRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 绑定 [CallRuntime] 事件源并维护 [CallUiState]。 */
class CallController(
    private val runtime: CallRuntime,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf(CallUiState())
        private set

    private var preMute: BloubState = BloubState.IDLE

    init {
        scope.launch {
            runtime.events.collect { onEvent(it) }
        }
    }

    fun startCall(mode: CallMode) {
        state = CallUiState(
            active = true,
            mode = mode,
            minimized = false,
            muted = false,
            elapsedSeconds = 0L,
            ai = CallAiState(
                BloubState.LISTENING,
                BloubStateMapper.subtitle(BloubState.LISTENING)
            ),
            model = state.model
        )
        preMute = BloubState.LISTENING
        runtime.start()
    }

    fun endCall() {
        runtime.stop()
        state = CallUiState()
        preMute = BloubState.IDLE
    }

    fun switchMode(mode: CallMode) {
        if (state.active) state = state.copy(mode = mode)
    }

    fun minimize() {
        if (state.active) state = state.copy(minimized = true)
    }

    fun restore() {
        state = state.copy(minimized = false)
    }

    /** 用户动作：静音/取消静音转发给 Runtime（Runtime 回发 MuteChanged 再更新 UI）。 */
    fun setMuted(muted: Boolean) {
        runtime.setMuted(muted)
    }

    /** 用户动作：打断当前轮，Runtime 回到聆听。 */
    fun interrupt() {
        runtime.interrupt()
    }

    fun setBloubState(s: BloubState) {
        state = state.copy(ai = CallAiState(s, BloubStateMapper.subtitle(s)))
    }

    fun setSubtitle(text: String) {
        state = state.copy(ai = state.ai.copy(subtitle = text))
    }

    fun sendText(text: String) {
        runtime.sendText(text)
    }

    fun tick() {
        if (state.active) state = state.copy(elapsedSeconds = state.elapsedSeconds + 1)
    }

    /** Runtime 事件：只更新 UI，不再回写 Runtime。 */
    private fun onEvent(event: CallEvent) {
        when (event) {
            is CallEvent.BloubStateChanged -> setBloubState(event.state)
            is CallEvent.MuteChanged -> applyMuted(event.muted)
            is CallEvent.SubtitleChanged -> setSubtitle(event.text)
        }
    }

    private fun applyMuted(muted: Boolean) {
        if (muted) preMute = state.ai.bloubState
        state = state.copy(muted = muted)
    }
}
