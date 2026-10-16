package com.openminis.app.voicecall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.voice.VoiceInputPickerSheet
import com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet
import com.openminis.app.ui.theme.ChatColors

/**
 * 通话内的语音设置：识别引擎、朗读声音、语速。
 * 都直接复用聊天里的同一份设置，通话里改了聊天里也跟着变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallSettingsSheet(
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    var picker by remember { mutableStateOf<Picker?>(null) }
    val speed by VoiceOutputState.speed.collectAsState()
    val earpiece by VoiceOutputState.callEarpiece.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.voice_call_settings), style = MaterialTheme.typography.titleMedium)
            SettingsRow(stringResource(R.string.voice_input_picker_title)) { picker = Picker.INPUT }
            SettingsRow(stringResource(R.string.tts_capsule_picker_title)) { picker = Picker.OUTPUT }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.voice_call_settings_earpiece),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ChatColors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = earpiece, onCheckedChange = { VoiceOutputState.setCallEarpiece(it) })
            }
            Text(
                stringResource(R.string.voice_call_settings_speed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                VoiceOutputState.SPEED_STEPS.forEach { step ->
                    FilterChip(
                        selected = speed == step,
                        onClick = { VoiceOutputState.setSpeed(step) },
                        label = { Text("${step.toBigDecimal().stripTrailingZeros().toPlainString()}x") },
                    )
                }
            }
        }
    }
    when (picker) {
        Picker.INPUT -> VoiceInputPickerSheet(providerRepository) { picker = null }
        Picker.OUTPUT -> VoiceOutputPickerSheet(providerRepository) { picker = null }
        null -> Unit
    }
}

private enum class Picker { INPUT, OUTPUT }

@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ChatColors.primaryText)
    }
}
