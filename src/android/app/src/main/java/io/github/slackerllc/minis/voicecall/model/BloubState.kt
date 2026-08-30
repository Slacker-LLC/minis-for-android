package io.github.slackerllc.minis.voicecall.model

/** Bloub 当前所处的 AI 语义状态。与页面模式(Mode)是两个独立维度。 */
enum class BloubState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    MUTED,
    CONFUSED,
    SUCCESS,
    ERROR
}
