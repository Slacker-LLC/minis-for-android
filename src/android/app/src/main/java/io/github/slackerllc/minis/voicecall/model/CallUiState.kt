package io.github.slackerllc.minis.voicecall.model

/** AI 本体状态（Bloub 当前语义 + 当前字幕）。 */
data class CallAiState(
    val bloubState: BloubState = BloubState.IDLE,
    val subtitle: String = ""
)

/** 当前通话所展示的模型信息。UI 只负责显示，不负责决定。 */
data class CallUiModel(
    val providerName: String = "DeepSeek",
    val modelName: String = "deepseek-v4",
    val statusText: String = "实时语音"
)

/**
 * 通话会话的完整状态。与页面路由解耦：最小化时 active 仍为 true（会话未结束）。
 * [active]  会话是否在进行（含最小化）。
 * [mode]    当前通话方式；null 表示不在通话中。
 * [minimized] 是否最小化为聊天页 + 悬浮泡。
 * [elapsedSeconds] 通话计时（最小化/切换模式时持续累计）。
 * [ai]      AI 本体状态（BloubState + subtitle），跨模式/最小化保持。
 */
data class CallUiState(
    val active: Boolean = false,
    val mode: CallMode? = null,
    val minimized: Boolean = false,
    val muted: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val ai: CallAiState = CallAiState(),
    val model: CallUiModel = CallUiModel()
) {
    /**
     * 实际展示给 Bloub 的状态：静音只是显示层覆盖，不改写真实 AI 状态。
     * 取消静音后恢复 [ai].[bloubState]，满足「静音/取消静音不丢真实状态」。
     */
    val effectiveBloubState: BloubState
        get() = if (muted) BloubState.MUTED else ai.bloubState
}
