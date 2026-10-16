package com.openminis.app.voicecall

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.voicecall.realtime.RealtimeCallSettings
import com.openminis.app.voicecall.realtime.RealtimeProfile
import com.openminis.app.voicecall.realtime.RealtimeProfiles
import com.openminis.app.voicecall.realtime.providerFits
import com.openminis.app.voicecall.realtime.resolveRealtime

/**
 * 通话引擎：经典流水线（默认）或全双工实时模型。
 * 实时模型直接用「管理提供商」里已有的提供商和 key，这里只选用哪个、用哪家协议，不再填 key。
 */
@Composable
internal fun RealtimeEngineSection(providerRepository: ProviderRepository) {
    val context = LocalContext.current
    remember { RealtimeCallSettings.init(context) }
    val selection by RealtimeCallSettings.state.collectAsState()
    val config by providerRepository.config.collectAsState()
    val profile = RealtimeProfiles.byId(selection.profileId) ?: RealtimeProfiles.OPENAI
    val candidates = config.instances.filter { providerFits(profile, it) }

    // 换协议后，当前选的提供商不适配就自动换成第一个适配的。
    LaunchedEffect(profile.id, candidates.map { it.id }) {
        if (candidates.isNotEmpty() && candidates.none { it.id == selection.instanceId }) {
            RealtimeCallSettings.update { it.copy(instanceId = candidates.first().id) }
        }
    }

    Text(
        stringResource(R.string.voice_call_engine),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !selection.enabled,
            onClick = { RealtimeCallSettings.update { it.copy(enabled = false) } },
            label = { Text(stringResource(R.string.voice_call_engine_classic)) },
        )
        FilterChip(
            selected = selection.enabled,
            onClick = { RealtimeCallSettings.update { it.copy(enabled = true) } },
            label = { Text(stringResource(R.string.voice_call_engine_realtime)) },
        )
    }
    if (!selection.enabled) return

    ChipRow(RealtimeProfiles.ALL, { it.id == profile.id }, { it.label }) { picked ->
        RealtimeCallSettings.update { it.copy(profileId = picked.id) }
    }
    if (candidates.isEmpty()) {
        Hint(stringResource(R.string.voice_call_rt_no_provider))
    } else {
        Text(stringResource(R.string.voice_call_rt_provider), style = MaterialTheme.typography.bodyMedium, color = ChatColors.secondaryText)
        ChipRow(candidates, { it.id == selection.instanceId }, { it.label }) { picked ->
            RealtimeCallSettings.update { it.copy(instanceId = picked.id) }
        }
    }
    Field(selection.model, stringResource(R.string.voice_call_rt_model), profile.defaultModel) { v -> RealtimeCallSettings.update { it.copy(model = v) } }
    Field(selection.voice, stringResource(R.string.voice_call_rt_voice), profile.defaultVoice.orEmpty()) { v -> RealtimeCallSettings.update { it.copy(voice = v) } }
    if (profile.needsWorkspace) {
        Field(selection.workspace, stringResource(R.string.voice_call_rt_workspace), "") { v -> RealtimeCallSettings.update { it.copy(workspace = v) } }
    }
    if (profile.customUrl) {
        Field(selection.customUrl, stringResource(R.string.voice_call_rt_url), "wss://…") { v -> RealtimeCallSettings.update { it.copy(customUrl = v) } }
    }
    val instance = candidates.firstOrNull { it.id == selection.instanceId }
    val complete = resolveRealtime(selection, instance, instance?.let { providerRepository.loadApiKey(it.id) }) != null
    if (!complete && candidates.isNotEmpty()) Hint(stringResource(R.string.voice_call_rt_incomplete))
    if (!profile.docsChecked) Hint(stringResource(R.string.voice_call_rt_unverified))
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: (T) -> Boolean, label: (T) -> String, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FilterChip(selected = selected(item), onClick = { onPick(item) }, label = { Text(label(item)) })
        }
    }
}

@Composable
private fun Field(value: String, label: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ChatColors.secondaryText, modifier = Modifier.padding(vertical = 4.dp))
}
