package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lollipop.grokbot.GrokBot
import com.lollipop.grokbot.GrokBotConfig
import com.lollipop.grokbot.GrokColor
import com.lollipop.grokbot.GrokMode
import com.lollipop.grokbot.GrokScheme
import com.lollipop.grokbot.GrokShape
import com.lollipop.grokbot.toColor
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.voicecall.grok.VoiceBotAvatar
import com.openminis.app.voicecall.grok.VoiceBotLook
import com.openminis.app.voicecall.grok.VoiceBotPrefs
import com.openminis.app.voicecall.grok.rememberVoiceBotLook
import com.openminis.app.voicecall.model.BloubState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.voice.VoiceInputPickerSheet
import com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet
import com.openminis.app.voicecall.RealtimeEngineSection

/**
 * 设置 › 对话与输入 › 语音通话。
 *
 * 通话页上原来的「语音设置」面板挪到这里：通话页只负责通话本身，
 * 识别 / 朗读模型、听筒、语速和通话引擎都在这一页改，聊天里的语音设置是同一份。
 */
@Composable
fun VoiceCallSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val speed by VoiceOutputState.speed.collectAsState()
    val earpiece by VoiceOutputState.callEarpiece.collectAsState()
    var picker by remember { mutableStateOf<VoicePicker?>(null) }

    val look = rememberVoiceBotLook()
    SettingsScaffold(
        title = stringResource(R.string.voice_call_settings_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_cat_chat),
        largeTitle = true,
    ) {
        BotAppearanceSection(look)
        SettingsSection {
            SettingsRow(
                icon = Icons.Outlined.Mic,
                title = stringResource(R.string.voice_input_picker_title),
                onClick = { picker = VoicePicker.INPUT },
            )
            SettingsRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = stringResource(R.string.tts_capsule_picker_title),
                onClick = { picker = VoicePicker.OUTPUT },
                showDivider = false,
            )
        }
        SettingsSection {
            SettingsSwitchRow(
                icon = Icons.Outlined.Hearing,
                title = stringResource(R.string.voice_call_settings_earpiece),
                checked = earpiece,
                onCheckedChange = { VoiceOutputState.setCallEarpiece(it) },
            )
            SettingsRow(
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.voice_call_settings_speed),
                showDivider = false,
            )
            SettingsCardBlock {
                SettingsSegmented(
                    options = VoiceOutputState.SPEED_STEPS.toList().map {
                        "${it.toBigDecimal().stripTrailingZeros().toPlainString()}x"
                    },
                    selectedIndex = VoiceOutputState.SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - speed) < 0.001f }.coerceAtLeast(0),
                    onSelect = { VoiceOutputState.setSpeed(VoiceOutputState.SPEED_STEPS[it]) },
                )
            }
        }
        SettingsSection(header = stringResource(R.string.voice_call_engine)) {
            SettingsCardBlock {
                Column(Modifier.padding(bottom = 4.dp)) { RealtimeEngineSection(providerRepository) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (picker) {
        VoicePicker.INPUT -> VoiceInputPickerSheet(providerRepository) { picker = null }
        VoicePicker.OUTPUT -> VoiceOutputPickerSheet(providerRepository) { picker = null }
        null -> Unit
    }
}

private enum class VoicePicker { INPUT, OUTPUT }

private val PREVIEW_STATES = listOf(
    BloubState.IDLE, BloubState.LISTENING, BloubState.THINKING, BloubState.SPEAKING, BloubState.SUCCESS,
)

/** 角色外观：预览（点一下轮换状态）、跟随主题色、颜色、形状、事件反应。 */
@Composable
private fun BotAppearanceSection(look: VoiceBotLook) {
    val context = LocalContext.current
    var previewIndex by remember { mutableStateOf(0) }
    SettingsSection(header = stringResource(R.string.voice_bot_section), footer = stringResource(R.string.voice_bot_preview_hint)) {
        SettingsCardBlock {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                VoiceBotAvatar(
                    PREVIEW_STATES[previewIndex],
                    Modifier.size(132.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { previewIndex = (previewIndex + 1) % PREVIEW_STATES.size },
                    look,
                )
            }
        }
        SettingsSwitchRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.voice_bot_follow),
            subtitle = stringResource(R.string.voice_bot_follow_sub),
            checked = look.followAccent,
            onCheckedChange = { VoiceBotPrefs.write(context, look.copy(followAccent = it)) },
                    )
        if (!look.followAccent) {
            SettingsRow(title = stringResource(R.string.voice_bot_color), showDivider = false)
            SettingsCardBlock {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(GrokColor.entries.toList()) { c ->
                        val selected = c == look.color
                        Box(
                            Modifier.size(36.dp)
                                .clip(CircleShape)
                                .background(c.toColor(if (ChatColors.isDark) GrokScheme.DARK else GrokScheme.LIGHT))
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .semantics { contentDescription = c.id }
                                .clickable { VoiceBotPrefs.write(context, look.copy(color = c)) },
                        )
                    }
                }
            }
        }
        SettingsRow(title = stringResource(R.string.voice_bot_shape), showDivider = false)
        SettingsCardBlock {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(GrokShape.entries.toList()) { shape ->
                    val selected = shape == look.shape
                    Box(
                        Modifier.size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else ChatColors.separator,
                                RoundedCornerShape(14.dp),
                            )
                            .semantics { contentDescription = shape.label }
                            .clickable { VoiceBotPrefs.write(context, look.copy(shape = shape)) }
                            .padding(6.dp),
                    ) {
                        GrokBot(
                            Modifier.fillMaxSize(),
                            GrokBotConfig(
                                shape = shape,
                                color = look.color,
                                scheme = if (ChatColors.isDark) GrokScheme.DARK else GrokScheme.LIGHT,
                                mode = GrokMode.HOLD,
                                paused = true,
                                followPointer = false,
                                flatInk = if (look.followAccent) MaterialTheme.colorScheme.primary else null,
                            ),
                        )
                    }
                }
            }
        }
        SettingsSwitchRow(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.voice_bot_react),
            subtitle = stringResource(R.string.voice_bot_react_sub),
            checked = look.react,
            onCheckedChange = { VoiceBotPrefs.write(context, look.copy(react = it)) },
            showDivider = false,
        )
    }
}
