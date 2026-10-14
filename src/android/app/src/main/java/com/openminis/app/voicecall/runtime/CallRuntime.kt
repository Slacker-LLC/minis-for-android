package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import kotlinx.coroutines.flow.SharedFlow

/** CallRuntime 抛给 CallController 的事件。 */
sealed interface CallEvent {
    data class BloubStateChanged(val state: BloubState) : CallEvent
    data class SubtitleChanged(val text: String) : CallEvent
    data class MuteChanged(val muted: Boolean) : CallEvent
}

/**
 * 通话事件源接口。生产实现为
 * [com.openminis.app.voicecall.runtime.MinisCallRuntimeAdapter]，
 * UI / CallController / 状态模型都不需要改动。
 */
interface CallRuntime {
    val events: SharedFlow<CallEvent>
    fun start()
    fun stop()

    /** 静音：停止录音与播放；取消静音后自动回到聆听。 */
    fun setMuted(muted: Boolean)

    /** 打断当前轮（TTS/模型任务），取消后立即回到聆听。 */
    fun interrupt()

    /** 保留任务并聆听补充；未实现此能力的 runtime 默认仅暂停音频。 */
    fun listenForFollowUp() { setMuted(true) }

    /** 用户发送文字消息（与语音识别最终文本使用同一路径）。 */
    fun sendText(text: String)
}
