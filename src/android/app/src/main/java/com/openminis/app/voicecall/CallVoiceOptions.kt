package com.openminis.app.voicecall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.resolveVoiceInputChoice
import com.openminis.app.data.repository.resolveVoiceOutputChoice
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.SpeechLanguagePickerSheet
import com.openminis.app.ui.chat.voice.VoiceInputPickerSheet
import com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet
import com.openminis.app.ui.theme.ChatColors

/**
 * 通话页的语音选项：识别引擎、识别语言、朗读声音三个入口。它们写的就是通话运行时读的配置
 * （`voiceInputOverrideEntryId` / `SpeechRecognitionManager.locale` / `voiceOutputOverrideEntryId`），
 * 所以改动在下一次聆听或朗读生效；原先聊天页听写面板上的同一组选项就是并入到这里的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallVoiceOptions(providers: ProviderRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }
    val config by providers.config.collectAsState()
    val locale by SpeechRecognitionManager.locale.collectAsState()
    val systemVoice by VoiceOutputState.systemVoiceLabel.collectAsState()
    val input = remember(config) { providers.resolveVoiceInputChoice() }
    val output = remember(config) { providers.resolveVoiceOutputChoice() }
    val inputName = input.entry?.second?.model?.displayName ?: stringResource(
        if (input.systemPreferOffline == true) R.string.voice_system_recognition_offline
        else R.string.voice_system_recognition_online,
    )
    val outputName = output.entry?.second?.model?.displayName
        ?: systemVoice
        ?: stringResource(R.string.voice_call_opt_system_voice)
    var picker by remember { mutableStateOf<CallVoiceOptionPicker?>(null) }

    FlowRow(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OptionChip(stringResource(R.string.voice_call_opt_input, inputName)) { picker = CallVoiceOptionPicker.INPUT }
        OptionChip(stringResource(R.string.voice_call_opt_language, locale.getDisplayName(locale))) {
            picker = CallVoiceOptionPicker.LANGUAGE
        }
        OptionChip(stringResource(R.string.voice_call_opt_output, outputName)) { picker = CallVoiceOptionPicker.OUTPUT }
    }

    when (picker) {
        CallVoiceOptionPicker.INPUT -> VoiceInputPickerSheet(providers) { picker = null }
        CallVoiceOptionPicker.LANGUAGE -> SpeechLanguagePickerSheet { picker = null }
        CallVoiceOptionPicker.OUTPUT -> VoiceOutputPickerSheet(providers) { picker = null }
        null -> Unit
    }
}

internal enum class CallVoiceOptionPicker { INPUT, LANGUAGE, OUTPUT }

@Composable
private fun OptionChip(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChatColors.secondaryText.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Default.UnfoldMore,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = ChatColors.secondaryText.copy(alpha = 0.6f),
        )
    }
}
