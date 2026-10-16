package com.openminis.app.voicecall.grok

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lollipop.grokbot.GrokBot
import com.lollipop.grokbot.GrokBotConfig
import com.lollipop.grokbot.GrokMode
import com.lollipop.grokbot.GrokMood
import com.lollipop.grokbot.rememberGrokBotState
import com.lollipop.grokbot.GrokScheme
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.voicecall.model.BloubState

/**
 * 语音通话角色。角色引擎来自 Mr-XiaoLiang/GrokBot（`com.lollipop.grokbot`，
 * MIT，来源与许可见 PROVENANCE.md / THIRD_PARTY_LICENSES.md），本文件只做两件事：
 * 把通话语义状态映射成官方表情，并让外观（形状 / 颜色 / 跟随主题色 / 事件反应）
 * 跟随设置与应用主题。
 *
 * `mode = HOLD` 表示表情由本页面锁定、不跑上游的演示轮播；配置热更新不会重建引擎，
 * 切状态时动画连续。角色是自绘矢量，没有图片资源，尺寸任意。
 */
@Composable
internal fun VoiceBotAvatar(
    state: BloubState,
    modifier: Modifier = Modifier,
    look: VoiceBotLook = rememberVoiceBotLook(),
) {
    val context = LocalContext.current
    // 上游在引擎创建时读一次；系统关闭动画时角色只保留最后一帧，眼神仍跟随触点。
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    val config = GrokBotConfig(
        shape = look.shape,
        color = look.color,
        scheme = if (ChatColors.isDark) GrokScheme.DARK else GrokScheme.LIGHT,
        mood = voiceBotMood(state),
        mode = GrokMode.HOLD,
        reduceMotion = reduceMotion,
        // 跟随主题色：身体用应用强调色单色绘制，和其它界面一致。
        flatInk = if (look.followAccent) MaterialTheme.colorScheme.primary else null,
    )
    val bot = rememberGrokBotState(config)
    // 事件反应：成功撒彩屑、出错抖一下、开始听时轻跳一下。首次进入页面不触发。
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state, look.react) {
        if (look.react && state != previous) {
            when (state) {
                BloubState.SUCCESS -> bot.burst()
                BloubState.ERROR -> bot.bounce()
                BloubState.LISTENING -> bot.bounce()
                else -> Unit
            }
        }
        previous = state
    }
    GrokBot(modifier = modifier, config = config, state = bot)
}

/**
 * 语义状态 → 表情。同名表情直接用；没有同名的取行为最接近的一个，
 * 只在这一个地方对应，页面不关心表情名。
 */
internal fun voiceBotMood(state: BloubState): GrokMood = when (state) {
    BloubState.IDLE -> GrokMood.IDLE
    BloubState.LISTENING -> GrokMood.LISTENING
    BloubState.THINKING -> GrokMood.THINKING
    // 「dictating」的 overlay 是 wave：播报时角色向外发波形。
    BloubState.SPEAKING -> GrokMood.DICTATING
    // 「drowsy」是打盹：音频已暂停，角色还在，和「说完就睡」区分开。
    BloubState.MUTED -> GrokMood.DROWSY
    BloubState.CONFUSED -> GrokMood.CONFUSED
    BloubState.SUCCESS -> GrokMood.CELEBRATE
    // 「alerting」的 overlay 是警示符号，识别/任务失败时一眼可见。
    BloubState.ERROR -> GrokMood.ALERTING
}

/** 状态文案。运行中的字幕缺失时用它兜底；读屏也朗读同一句。 */
@StringRes
internal fun voiceCallStatusRes(state: BloubState): Int = when (state) {
    BloubState.IDLE -> R.string.voice_call_state_idle
    BloubState.LISTENING -> R.string.voice_call_state_listening
    BloubState.THINKING -> R.string.voice_call_state_thinking
    BloubState.SPEAKING -> R.string.voice_call_state_speaking
    BloubState.MUTED -> R.string.voice_call_paused
    BloubState.CONFUSED -> R.string.voice_call_state_confused
    BloubState.SUCCESS -> R.string.voice_call_state_success
    BloubState.ERROR -> R.string.voice_call_state_error
}
