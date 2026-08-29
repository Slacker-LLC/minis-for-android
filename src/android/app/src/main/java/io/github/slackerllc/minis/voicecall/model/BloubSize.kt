package io.github.slackerllc.minis.voicecall.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bloub 在不同页面的尺寸档位。页面只决定放在哪、多大，角色本身完全复用。 */
enum class BloubSize(val dp: Dp) {
    /** 语音通话：主视觉。 */
    LARGE(196.dp),

    /** 屏幕共享：中型 AI Presence。 */
    MEDIUM(132.dp),

    /** 视频通话：右上小窗，不遮挡摄像头主体。 */
    SMALL(56.dp),

    /** 最小化悬浮泡里的通话入口。 */
    FLOATING(48.dp)
}
