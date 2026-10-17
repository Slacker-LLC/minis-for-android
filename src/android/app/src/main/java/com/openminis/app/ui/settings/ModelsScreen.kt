package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.UnifiedModelPickerSheet
import com.openminis.app.data.repository.setFallbackTrigger
import com.openminis.app.data.repository.setSlotEntries
import com.openminis.app.data.repository.syncImageSlotToAgentList

@Composable
fun ModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSlotClick: (ModelSlot) -> Unit,
    onAgentLoopModelsClick: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    var pickerSlot by remember { mutableStateOf<ModelSlot?>(null) }
    val pruneNotices by providerRepository.pruneNotices.collectAsState()
    // An Image-slot model has to be on the Agent models list too; repair configs from before
    // the slot did that on its own.
    LaunchedEffect(Unit) { providerRepository.syncImageSlotToAgentList() }
    SettingsScaffold(
        title = stringResource(R.string.settings_models_title),
        onBack = onBack, backLabel = stringResource(R.string.settings_cat_models),
    ) {
        if (pruneNotices.isNotEmpty()) {
            val agentListName = stringResource(R.string.agent_loop_models_title)
            val slotNames = ModelSlot.values().associateWith { modelSlotTitle(it) }
            SettingsSection(footer = null) {
                pruneNotices.forEach { notice ->
                    val places = (notice.slots.map { slotNames.getValue(it) } +
                        listOfNotNull(agentListName.takeIf { notice.agentList })).joinToString(", ")
                    SettingsRow(
                        title = stringResource(R.string.model_prune_notice, notice.modelName, places),
                        icon = Icons.Outlined.Info,
                        iconColor = MaterialTheme.colorScheme.error,
                        showChevron = false,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.model_prune_notice_dismiss),
                    onClick = { providerRepository.dismissPruneNotices() },
                    showChevron = false,
                    showDivider = false,
                )
            }
        }
        SettingsSection(
            header = stringResource(R.string.model_slots_header),
            footer = stringResource(R.string.model_slots_footer),
        ) {
            val slotPurposes = mapOf(
                ModelSlot.main to R.string.model_slot_main_desc,
                ModelSlot.light to R.string.model_slot_light_desc,
                ModelSlot.vision to R.string.model_slot_vision_desc,
                ModelSlot.voiceInput to R.string.model_slot_voice_input_desc,
                ModelSlot.voiceOutput to R.string.model_slot_voice_output_desc,
                ModelSlot.image to R.string.model_slot_image_desc,
            )
            val slots = listOf(
                ModelSlot.main to (R.string.model_slot_main to Icons.Outlined.AutoAwesome),
                ModelSlot.light to (R.string.model_slot_light to Icons.Outlined.LightMode),
                ModelSlot.vision to (R.string.model_slot_vision to Icons.Outlined.Image),
                ModelSlot.voiceInput to (R.string.model_slot_voice_input to Icons.Outlined.GraphicEq),
                ModelSlot.voiceOutput to (R.string.model_slot_voice_output to Icons.Outlined.GraphicEq),
                ModelSlot.image to (R.string.model_slot_image to Icons.Outlined.Brush),
            )
            slots.forEachIndexed { index, (slot, display) ->
                val ids = config.slots.entries(slot)
                val primaryId = ids.firstOrNull()
                val primaryName = primaryId?.let { id ->
                    config.modelEntries.firstOrNull { it.id == id }?.model?.displayName
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(id)?.model?.displayName
                        ?: id
                }
                val subtitle = when {
                    primaryId == null -> stringResource(R.string.model_slot_empty)
                    ids.size == 1 -> primaryName.orEmpty()
                    else -> "${primaryName.orEmpty()} · ${pluralStringResource(R.plurals.model_slot_backups_count, ids.size - 1, ids.size - 1)}"
                }
                SettingsRow(
                    title = stringResource(display.first),
                    subtitle = subtitle + "\n" + stringResource(slotPurposes.getValue(slot)),
                    icon = display.second,
                    onClick = { pickerSlot = slot },
                    showDivider = index < slots.lastIndex,
                    trailing = {
                        IconButton(onClick = { onSlotClick(slot) }) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = stringResource(R.string.model_slot_manage_backups),
                            )
                        }
                    },
                )
            }
        }

        if (config.slots.main.size > 1) {
            SettingsSection(
                header = stringResource(R.string.model_slot_main_options),
            ) {
                val always = config.fallbackTrigger == FallbackStrategy.always
                SettingsRow(
                    title = stringResource(R.string.model_slot_fallback_title),
                    subtitle = stringResource(
                        if (always) R.string.model_slot_fallback_always
                        else R.string.model_slot_fallback_default,
                    ),
                    icon = Icons.Outlined.Tune,
                    onClick = {
                        providerRepository.setFallbackTrigger(
                            if (always) FallbackStrategy.default else FallbackStrategy.always,
                        )
                    },
                    showDivider = false,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.agent_loop_models_title),
            footer = stringResource(R.string.agent_loop_models_slots_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.agent_loop_models_title),
                subtitle = stringResource(R.string.agent_loop_models_slots_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                onClick = onAgentLoopModelsClick,
                showDivider = false,
            )
        }

        val selectedSlot = pickerSlot
        if (selectedSlot != null) {
            UnifiedModelPickerSheet(
                providerRepository = providerRepository,
                title = stringResource(R.string.model_slot_select_primary, modelSlotTitle(selectedSlot)),
                modalityFilter = selectedSlot.pickerModalityFilter(),
                selectedId = config.slots.entries(selectedSlot).firstOrNull(),
                showSlotFollow = false,
                allowDeviceVoiceSelection = false,
                onSelect = { entryId ->
                    if (entryId != null) {
                        val current = providerRepository.config.value.slots.entries(selectedSlot)
                        providerRepository.setSlotEntries(
                            selectedSlot,
                            listOf(entryId) + current.filterNot { it == entryId },
                        )
                    }
                },
                onDismiss = { pickerSlot = null },
            )
        }
    }
}

private fun ModelSlot.pickerModalityFilter(): PickerModalityFilter = when (this) {
    ModelSlot.main, ModelSlot.light -> PickerModalityFilter.TEXT_OUTPUT
    ModelSlot.vision -> PickerModalityFilter.IMAGE_INPUT
    ModelSlot.voiceInput -> PickerModalityFilter.AUDIO_INPUT
    ModelSlot.voiceOutput -> PickerModalityFilter.AUDIO_OUTPUT
    ModelSlot.image -> PickerModalityFilter.IMAGE_OUTPUT
}

@Composable
fun modelSlotTitle(slot: ModelSlot): String = stringResource(
    when (slot) {
        ModelSlot.main -> R.string.model_slot_main
        ModelSlot.light -> R.string.model_slot_light
        ModelSlot.vision -> R.string.model_slot_vision
        ModelSlot.voiceInput -> R.string.model_slot_voice_input
        ModelSlot.voiceOutput -> R.string.model_slot_voice_output
        ModelSlot.image -> R.string.model_slot_image
    },
)
