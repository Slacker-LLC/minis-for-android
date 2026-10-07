package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ModelsDevApi
import sh.calvin.reorderable.ReorderableColumn
import com.openminis.app.data.repository.hasAnyCredential
import com.openminis.app.data.repository.removeAgentLoopEntry
import com.openminis.app.data.repository.reorderAgentLoopEntries

/**
 * The models the agent may use: what `minis-model-use` can call and what the assistant can run a sub
 * agent on (`subagent.model`). Shows the list itself — remove, reorder, add — which used to be reachable
 * only through the add picker, so nothing already added could be seen or taken out again.
 */
@Composable
fun AgentModelsScreen(
    providerRepository: ProviderRepository,
    onAddModels: () -> Unit,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val ids = config.agentLoopModelEntryIds.toList()

    SettingsScaffold(
        title = stringResource(R.string.agent_loop_models_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.agent_models_selected_header),
            footer = stringResource(R.string.agent_models_footer),
        ) {
            if (ids.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.agent_models_empty),
                    showChevron = false,
                    showDivider = false,
                )
            } else {
                var localOrder by remember(ids) { mutableStateOf(ids) }
                ReorderableColumn(
                    list = localOrder,
                    onSettle = { fromIndex, toIndex ->
                        val reordered = localOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                        localOrder = reordered
                        providerRepository.reorderAgentLoopEntries(reordered)
                    },
                ) { index, id, isDragging ->
                    key(id) {
                        val entry = config.modelEntries.firstOrNull { it.id == id }
                        val instance = entry?.let { e -> config.instances.firstOrNull { it.id == e.providerInstanceId } }
                        val status = when {
                            entry == null || instance == null -> stringResource(R.string.sub_agent_model_missing)
                            !instance.isEnabled -> stringResource(R.string.model_slot_provider_disabled)
                            !providerRepository.hasAnyCredential(instance) -> stringResource(R.string.agent_models_no_credential)
                            !ModelsDevApi.enrichModel(entry.model).isTextOutput -> stringResource(R.string.agent_models_cli_only)
                            else -> null
                        }
                        Surface(
                            color = Color.Transparent,
                            shadowElevation = if (isDragging) 4.dp else 0.dp,
                            modifier = Modifier.longPressDraggableHandle(),
                        ) {
                            SettingsRow(
                                title = entry?.model?.displayName?.ifBlank { entry.model.id } ?: id,
                                subtitle = listOfNotNull(instance?.label, entry?.model?.id, status).joinToString(" · "),
                                showChevron = false,
                                showDivider = index < localOrder.lastIndex,
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.End) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = stringResource(R.string.model_slot_drag_to_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(onClick = { providerRepository.removeAgentLoopEntry(id) }) {
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

        SettingsSection(footer = stringResource(R.string.agent_loop_models_slots_footer)) {
            SettingsRow(
                title = stringResource(R.string.agent_loop_section_add_models_title),
                icon = Icons.Default.Add,
                onClick = onAddModels,
                showDivider = false,
            )
        }
    }
}
