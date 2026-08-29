package io.github.slackerllc.minis.voicecall.bloub

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.slackerllc.minis.voicecall.model.BloubSize
import io.github.slackerllc.minis.voicecall.model.BloubState

/**
 * Bloub 表情球入口。按 [state] 渲染对应表情；[size] 只决定外框尺寸。
 * 角色本身完全复用，页面只决定放哪、多大。
 */
@Composable
fun BloubAvatar(state: BloubState, size: BloubSize, modifier: Modifier = Modifier) {
    BloubRenderer(state = state, modifier = modifier.size(size.dp))
}
