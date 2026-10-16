package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Language
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
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.SpeechLanguagePickerSheet
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
    val locale by SpeechRecognitionManager.locale.collectAsState()
    var picker by remember { mutableStateOf<VoicePicker?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.voice_call_settings_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_cat_chat),
        largeTitle = true,
    ) {
        SettingsSection {
            SettingsRow(
                icon = Icons.Outlined.Mic,
                title = stringResource(R.string.voice_input_picker_title),
                onClick = { picker = VoicePicker.INPUT },
            )
            SettingsRow(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.voice_call_opt_language, locale.getDisplayName(locale)),
                onClick = { picker = VoicePicker.LANGUAGE },
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
        VoicePicker.LANGUAGE -> SpeechLanguagePickerSheet { picker = null }
        VoicePicker.OUTPUT -> VoiceOutputPickerSheet(providerRepository) { picker = null }
        null -> Unit
    }
}

private enum class VoicePicker { INPUT, LANGUAGE, OUTPUT }
