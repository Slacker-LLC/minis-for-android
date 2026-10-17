package com.openminis.app.voicecall

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.voicecall.grok.VoiceBotAvatar
import com.openminis.app.voicecall.model.BloubState

/** 通话页上的操作入口；对象本身稳定，字段由 [VoiceConversationHost] 每次组合刷新。 */
internal class VoiceCallActions {
    var toggleMute: () -> Unit = {}
    var end: () -> Unit = {}
    var retry: () -> Unit = {}
    var tapOrb: () -> Unit = {}
    var sendText: (String) -> Unit = {}
    var toggleOrb: () -> Unit = {}
}

/**
 * 通话中聊天页要变的那一点点：球和状态字、输入框右边的静音钮与结束钮、顶栏右侧的设置入口。
 * 消息列表和输入框还是聊天页自己的，通话里的消息就写在这个会话里。
 */
@Immutable
internal data class VoiceCallUi(
    /** 通话已接通（麦克风在工作）；false 时是「准备中」或入口失败。 */
    val active: Boolean,
    val muted: Boolean,
    val characterState: BloubState,
    val statusLine: String,
    val statusIsError: Boolean,
    /** 入口失败（缺麦克风权限等）：状态行给出「重试」和「离开」。 */
    val failed: Boolean,
    /** true 是默认的大球版面；false 是消息列表加小球状态行。 */
    val orbMode: Boolean,
    val actions: VoiceCallActions,
)

/** 大球和小球之间的共享元素动画用的作用域；聊天页外没有就是 null，球照常显示。 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalCallSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.callOrbShared(visibility: AnimatedVisibilityScope?): Modifier {
    val shared = LocalCallSharedScope.current
    if (shared == null || visibility == null) return this
    return with(shared) { this@callOrbShared.sharedElement(rememberSharedContentState("call-orb"), visibility) }
}

/** 聊天页读这个判断自己是否处在通话里；null 就是普通聊天。 */
internal val LocalVoiceCall = compositionLocalOf<VoiceCallUi?> { null }

/** 输入框上方居中的一行：小球加状态字。听的时候点球是「现在发送」，想/说/出错时是「打断并重新说」。 */
@Composable
internal fun VoiceCallStatusRow(call: VoiceCallUi, modifier: Modifier = Modifier, visibility: AnimatedVisibilityScope? = null) {
    val orbLabel = stringResource(
        if (call.characterState == BloubState.LISTENING) R.string.voice_call_submit else R.string.voice_call_interrupt,
    )
    var dragged by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .pointerInput(call.actions) {
                // 上滑：小球变大球。
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -swipeThreshold) call.actions.toggleOrb() },
                    onDragCancel = { dragged = 0f },
                ) { _, delta -> dragged += delta }
            }
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        VoiceBotAvatar(
            call.characterState,
            Modifier
                .callOrbShared(visibility)
                .size(44.dp)
                .semantics { contentDescription = call.statusLine }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = call.active && call.characterState != BloubState.MUTED,
                    onClickLabel = orbLabel,
                    onClick = call.actions.tapOrb,
                ),
        )
        Text(
            call.statusLine,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (call.statusIsError) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
        )
        if (call.failed) {
            TextButton(onClick = call.actions.retry) { Text(stringResource(R.string.voice_call_retry)) }
            TextButton(onClick = call.actions.end) { Text(stringResource(R.string.voice_call_leave_to_chat)) }
        }
    }
}

/** 默认的大球版面，占满消息区：点球听时发送、其余打断；下滑切到消息列表。 */
@Composable
internal fun VoiceCallOrbPane(call: VoiceCallUi, modifier: Modifier = Modifier, visibility: AnimatedVisibilityScope? = null) {
    val orbLabel = stringResource(
        if (call.characterState == BloubState.LISTENING) R.string.voice_call_submit else R.string.voice_call_interrupt,
    )
    var dragged by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(call.actions) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > swipeThreshold) call.actions.toggleOrb() },
                    onDragCancel = { dragged = 0f },
                ) { _, delta -> dragged += delta }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VoiceBotAvatar(
                call.characterState,
                Modifier
                    .callOrbShared(visibility)
                    .size(240.dp)
                    .semantics { contentDescription = call.statusLine }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = call.active && call.characterState != BloubState.MUTED,
                        onClickLabel = orbLabel,
                        onClick = call.actions.tapOrb,
                    ),
            )
            Text(
                call.statusLine,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = if (call.statusIsError) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
            )
            if (call.failed) {
                Row {
                    TextButton(onClick = call.actions.retry) { Text(stringResource(R.string.voice_call_retry)) }
                    TextButton(onClick = call.actions.end) { Text(stringResource(R.string.voice_call_leave_to_chat)) }
                }
            }
        }
    }
}
