package com.openminis.app.voicecall.grok

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.lollipop.grokbot.GrokColor
import com.lollipop.grokbot.GrokShape
import com.openminis.app.ui.settings.getAppearancePrefs

/**
 * 通话角色的外观设置，和其它外观项一样存在 appearance prefs 里。
 *
 * 默认「跟随主题色」：角色身体用应用当前强调色单色绘制，和其余界面同色；
 * 关掉后才用 [color] 的官方渐变。
 */
internal data class VoiceBotLook(
    val shape: GrokShape = GrokShape.BLOB,
    val color: GrokColor = GrokColor.GREEN,
    val followAccent: Boolean = true,
    val react: Boolean = true,
)

internal object VoiceBotPrefs {
    const val KEY_SHAPE = "voice_bot_shape"
    const val KEY_COLOR = "voice_bot_color"
    const val KEY_FOLLOW_ACCENT = "voice_bot_follow_accent"
    const val KEY_REACT = "voice_bot_react"

    fun read(prefs: SharedPreferences): VoiceBotLook = VoiceBotLook(
        shape = prefs.getString(KEY_SHAPE, null)?.let { n -> GrokShape.entries.firstOrNull { it.name == n } }
            ?: GrokShape.BLOB,
        color = prefs.getString(KEY_COLOR, null)?.let { n -> GrokColor.entries.firstOrNull { it.name == n } }
            ?: GrokColor.GREEN,
        followAccent = prefs.getBoolean(KEY_FOLLOW_ACCENT, true),
        react = prefs.getBoolean(KEY_REACT, true),
    )

    fun write(context: Context, look: VoiceBotLook) {
        getAppearancePrefs(context).edit()
            .putString(KEY_SHAPE, look.shape.name)
            .putString(KEY_COLOR, look.color.name)
            .putBoolean(KEY_FOLLOW_ACCENT, look.followAccent)
            .putBoolean(KEY_REACT, look.react)
            .apply()
    }
}

/** 订阅外观设置：设置页里改完，通话页（以及设置页自己的预览）立即跟着变。 */
@Composable
internal fun rememberVoiceBotLook(): VoiceBotLook {
    val context = LocalContext.current
    val prefs = remember(context) { getAppearancePrefs(context) }
    var look by remember(prefs) { mutableStateOf(VoiceBotPrefs.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ -> look = VoiceBotPrefs.read(sp) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return look
}
