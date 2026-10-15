package com.openminis.app.voicecall.model

import com.openminis.app.voicecall.runtime.CallEndReason
import com.openminis.app.voicecall.runtime.CallNotice

/** AI 本体状态（Bloub 当前语义）。通话页不再显示字幕，文字全部进聊天记录。 */
data class CallAiState(
    val bloubState: BloubState = BloubState.IDLE
)

/** 当前通话所展示的模型信息。UI 只负责显示，不负责决定。 */
data class CallUiModel(
    val providerName: String = "",
    val modelName: String = "",
    val statusText: String = "语音对话"
)

/** 聊天记录里的一条：[fromUser] 为 true 是用户说的，否则是助手回答。 */
data class CallLine(val fromUser: Boolean, val text: String)

/** 一条待展示的 toast。[seq] 递增，让相同内容连续出现时也会重新触发。 */
data class CallToast(val seq: Long, val kind: CallNotice, val detail: String?)

/** 通话被自动结束时留给结束页的说明。 */
data class CallEnded(val reason: CallEndReason, val detail: String?)

/**
 * 通话会话的完整状态。与页面路由解耦：最小化时 active 仍为 true（会话未结束）。
 * [active]  会话是否在进行（含最小化）。
 * [mode]    当前通话方式；null 表示不在通话中。
 * [minimized] 是否最小化为聊天页 + 悬浮泡。
 * [elapsedSeconds] 通话计时（最小化/切换模式时持续累计）。
 * [ai]      AI 本体状态，跨模式/最小化保持。
 * [transcript] 本次通话的聊天记录。
 * [toast]   待展示的瞬时提示；展示后由 UI 调 clearToast。
 * [ended]   runtime 自动挂断的原因；非 null 时 UI 展示结束页。
 */
data class CallUiState(
    val active: Boolean = false,
    val mode: CallMode? = null,
    val minimized: Boolean = false,
    val muted: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val ai: CallAiState = CallAiState(),
    val model: CallUiModel = CallUiModel(),
    val transcript: List<CallLine> = emptyList(),
    val toast: CallToast? = null,
    val ended: CallEnded? = null
) {
    /**
     * 实际展示给 Bloub 的状态：静音只是显示层覆盖，不改写真实 AI 状态。
     * 取消静音后恢复 [ai].[bloubState]，满足「静音/取消静音不丢真实状态」。
     */
    val effectiveBloubState: BloubState
        get() = if (muted) BloubState.MUTED else ai.bloubState
}
