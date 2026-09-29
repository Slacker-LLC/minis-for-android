package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ProviderRepository
import sh.calvin.reorderable.ReorderableColumn

@Composable
fun ModelSlotDetailScreen(
    slot: ModelSlot,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val candidates = remember(config, slot) { slotCandidates(config, slot) }
    val selectedIds = config.slots.entries(slot)
    val selected = selectedIds.toSet()

    SettingsScaffold(
        title = modelSlotTitle(slot),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.model_slot_selected_models),
            footer = stringResource(R.string.model_slot_order_footer),
        ) {
            if (selectedIds.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.model_slot_empty),
                    showChevron = false,
                    showDivider = false,
                )
            } else {
                var localOrder by remember(selectedIds) { mutableStateOf(selectedIds) }
                ReorderableColumn(
                    list = localOrder,
                    onSettle = { fromIndex, toIndex ->
                        val reordered = localOrder.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        localOrder = reordered
                        providerRepository.reorderSlot(slot, reordered)
                    },
                ) { index, id, isDragging ->
                    key(id) {
                        val entry = candidates.firstOrNull { it.id == id }
                        val title = entry?.model?.displayName ?: id
                        val position = if (index == 0) {
                            stringResource(R.string.model_slot_primary_entry)
                        } else {
                            stringResource(R.string.model_slot_fallback_position, index)
                        }
                        val subtitle = if (entry == null) {
                            "$position · ${stringResource(R.string.model_slot_legacy_unresolved)}"
                        } else {
                            position
                        }
                        Surface(
                            color = Color.Transparent,
                            shadowElevation = if (isDragging) 4.dp else 0.dp,
                            modifier = Modifier.longPressDraggableHandle(),
                        ) {
                            SettingsRow(
                                title = title,
                                subtitle = subtitle,
                                onClick = { toggleSlotEntry(providerRepository, slot, id) },
                                showChevron = false,
                                showDivider = index < localOrder.lastIndex,
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.End) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.model_slot_drag_to_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(onClick = { toggleSlotEntry(providerRepository, slot, id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.common_remove),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.model_slot_available_models),
            footer = stringResource(R.string.model_slot_available_footer),
        ) {
            if (candidates.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.model_slot_no_candidates),
                    showChevron = false,
                    showDivider = false,
                )
            } else {
                candidates.forEachIndexed { index, entry ->
                    val isSelected = entry.id in selected
                    val system = SystemVoiceEntries.isSystemEntryId(entry.id)
                    val providerEnabled = system || providerRepository.isEntryProviderEnabled(entry.id)
                    SettingsRow(
                        title = entry.model.displayName.ifBlank { entry.model.id },
                        subtitle = if (system) {
                            stringResource(R.string.model_slot_system_entry)
                        } else {
                            val instance = config.instances.firstOrNull { it.id == entry.providerInstanceId }
                            listOfNotNull(instance?.label, if (!providerEnabled) stringResource(R.string.model_slot_provider_disabled) else null)
                                .joinToString(" · ")
                                .ifBlank { entry.model.id }
                        },
                        onClick = { toggleSlotEntry(providerRepository, slot, entry.id) },
                        showDivider = index < candidates.lastIndex,
                        titleColor = if (providerEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        trailing = {
                            Icon(
                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFF34C759)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        },
                    )
                }
            }
        }
    }
}

internal fun slotCandidates(config: ProviderConfig, slot: ModelSlot): List<ModelEntry> {
    val providers = config.instances.associateBy { it.id }
    val providerEntries = config.modelEntries.filter { entry ->
        if (entry.isHidden) return@filter false
        if (providers[entry.providerInstanceId] == null) return@filter false
        when (slot) {
            ModelSlot.main, ModelSlot.light -> entry.model.isTextOutput
            ModelSlot.vision -> entry.model.hasImageInput
            ModelSlot.voiceInput -> entry.model.hasAudioInput
            ModelSlot.voiceOutput -> entry.model.hasAudioOutput
        }
    }
    val systemEntries = when (slot) {
        ModelSlot.voiceInput -> listOf(SystemVoiceEntries.asrOnline, SystemVoiceEntries.asrOffline)
        ModelSlot.voiceOutput -> listOf(SystemVoiceEntries.tts)
        else -> emptyList()
    }
    return systemEntries + providerEntries
}

private fun toggleSlotEntry(repository: ProviderRepository, slot: ModelSlot, id: String) {
    val current = repository.config.value.slots.entries(slot)
    val updated = if (id in current) current.filterNot { it == id } else current + id
    repository.setSlotEntries(slot, updated)
}
