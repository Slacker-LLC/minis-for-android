package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.model.BloubState
import kotlinx.coroutines.flow.SharedFlow

/** 通话中给用户一闪而过的提示（toast）；文案由 UI 层按种类选取。 */
enum class CallNotice { RECOGNITION_RETRY, TASK_FAILED, IDLE_REMINDER }

/** 通话被 runtime 自动结束的原因；UI 据此在结束页给出说明。 */
enum class CallEndReason { PERMISSION_DENIED, RECOGNITION_FAILED, TASK_FAILED, IDLE_TIMEOUT, CONNECTION_FAILED }

/** CallRuntime 抛给 CallController 的事件。 */
sealed interface CallEvent {
    data class BloubStateChanged(val state: BloubState) : CallEvent
    data class MuteChanged(val muted: Boolean) : CallEvent

    /** 一条通话记录：用户说的话（识别后）或助手回答，供「聊天记录」面板展示。 */
    data class Transcript(val fromUser: Boolean, val text: String) : CallEvent

    data class Notice(val kind: CallNotice, val detail: String? = null) : CallEvent

    /** runtime 已自行停掉音频并结束通话；已接受的 Agent 任务继续留在原会话。 */
    data class Ended(val reason: CallEndReason, val detail: String? = null) : CallEvent
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
