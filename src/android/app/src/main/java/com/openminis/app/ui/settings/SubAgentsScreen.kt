package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.subagents.SubAgentStore
import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ModelPickerSheet
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.data.repository.allVisibleEntries

/**
 * Settings › Agent › Sub Agents: the master switch and the roster the assistant can delegate to.
 *
 * Adapted from OpenMinis 1.14's Sub Agents screen. The model pin is a model entry (the same picker
 * and `{"type":"entry"}` binding a Bot uses) because this app has fixed model slots, not groups.
 * Everything saved goes through [SubAgentStore], which normalizes the roster (built-in kept, names
 * unique, fields clamped), so a bad entry can never reach the prompt or the tool schema.
 */
@Composable
fun SubAgentsScreen(providerRepository: ProviderRepository, onBack: () -> Unit) {
    val roster by SubAgentStore.roster.collectAsState()
    val enabled by SubAgentStore.enabled.collectAsState()
    val config by providerRepository.config.collectAsState()
    val context = LocalContext.current
    // null = the list; non-null = the editor (a blank draft when adding).
    var editing by remember { mutableStateOf<SubAgentDefinition?>(null) }
    var isNew by remember { mutableStateOf(false) }

    fun label(def: SubAgentDefinition): String =
        if (def.isBuiltIn) context.getString(R.string.sub_agent_builtin_name) else def.name

    fun description(def: SubAgentDefinition): String =
        if (def.isBuiltIn) context.getString(R.string.sub_agent_builtin_description) else def.description

    val draft = editing
    if (draft != null) {
        SubAgentEditor(
            original = draft,
            isNew = isNew,
            roster = roster,
            providerRepository = providerRepository,
            onCancel = { editing = null },
            onSave = { saved ->
                val next = if (isNew) roster + saved else roster.map { if (it.id == saved.id) saved else it }
                SubAgentStore.save(next)
                editing = null
            },
            onDelete = {
                SubAgentStore.save(roster.filterNot { it.id == draft.id })
                editing = null
            },
        )
        return
    }

    SettingsScaffold(
        title = stringResource(R.string.sub_agents_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_cat_agent),
        actions = {
            IconButton(
                onClick = {
                    isNew = true
                    editing = SubAgentDefinition(name = "", description = "", sortOrder = roster.size)
                },
                enabled = roster.size < SubAgentLimits.MAX_COUNT,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sub_agent_add), tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        SettingsSection(footer = stringResource(R.string.sub_agents_enable_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.sub_agents_enable),
                checked = enabled,
                onCheckedChange = { SubAgentStore.setEnabled(it) },
                showDivider = false,
            )
        }
        SettingsSection(
            header = stringResource(R.string.sub_agents_section_agents),
            footer = stringResource(R.string.sub_agents_roster_footer, roster.size, SubAgentLimits.MAX_COUNT),
        ) {
            roster.forEachIndexed { index, def ->
                val pinnedName = def.pinnedEntryId?.let { id ->
                    config.modelEntries.firstOrNull { it.id == id }?.model?.displayName
                        ?: stringResource(R.string.sub_agent_model_missing)
                }
                SettingsRow(
                    title = label(def),
                    subtitle = description(def),
                    onClick = {
                        isNew = false
                        editing = def
                    },
                    trailing = {
                        Text(
                            pinnedName ?: stringResource(R.string.sub_agent_model_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    },
                    minHeight = 72.dp,
                    showDivider = index < roster.lastIndex,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SubAgentEditor(
    original: SubAgentDefinition,
    isNew: Boolean,
    roster: List<SubAgentDefinition>,
    providerRepository: ProviderRepository,
    onCancel: () -> Unit,
    onSave: (SubAgentDefinition) -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var description by rememberSaveable(original.id) { mutableStateOf(original.description) }
    var instructions by rememberSaveable(original.id) { mutableStateOf(original.instructions) }
    var binding by rememberSaveable(original.id) { mutableStateOf(original.modelBinding) }
    var thinking by rememberSaveable(original.id) { mutableStateOf(original.thinkingLevelOverride) }
    var picker by remember { mutableStateOf(false) }
    var nonTextError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var thinkingMenu by remember { mutableStateOf(false) }
    val config by providerRepository.config.collectAsState()
    val context = LocalContext.current

    val pinnedId = (ModelBinding.parse(binding) as? ModelBinding.Entry)?.entryId
    val pinned = config.modelEntries.firstOrNull { it.id == pinnedId }

    // The name is the key the model addresses an agent by, so it must be unique (case- and accent-insensitive).
    val nameTaken = !original.isBuiltIn && name.isNotBlank() &&
        roster.any { it.id != original.id && SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(name) }
    val canSave = (original.isBuiltIn || (name.isNotBlank() && !nameTaken)) &&
        (original.isBuiltIn || description.isNotBlank())

    SettingsScaffold(
        title = stringResource(if (isNew) R.string.sub_agent_add else R.string.sub_agent_edit),
        navigation = { MinisTextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
        actions = {
            MinisTextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        original.copy(
                            name = if (original.isBuiltIn) original.name else name.trim(),
                            description = if (original.isBuiltIn) original.description else description.trim(),
                            instructions = instructions.trim(),
                            modelBinding = binding,
                            thinkingLevelOverride = thinking,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        if (!original.isBuiltIn) {
            SettingsSection(
                header = stringResource(R.string.sub_agent_name),
                footer = if (nameTaken) stringResource(R.string.sub_agent_name_taken) else stringResource(R.string.sub_agent_name_footer),
            ) {
                SettingsInlineTextRow(
                    title = stringResource(R.string.sub_agent_name),
                    value = name,
                    onValueChange = { name = it.take(SubAgentLimits.NAME_MAX_LENGTH) },
                    placeholder = stringResource(R.string.sub_agent_name),
                    showDivider = false,
                )
            }
            SettingsSection(
                header = stringResource(R.string.sub_agent_description),
                footer = stringResource(R.string.sub_agent_description_footer),
            ) {
                SettingsTextArea(
                    value = description,
                    onValueChange = { description = it.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH) },
                    placeholder = stringResource(R.string.sub_agent_description_hint),
                    minHeight = 96.dp,
                )
            }
        }
        SettingsSection(
            header = stringResource(R.string.sub_agent_instructions),
            footer = stringResource(R.string.sub_agent_instructions_footer),
        ) {
            SettingsTextArea(
                value = instructions,
                onValueChange = { instructions = it.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH) },
                placeholder = stringResource(R.string.sub_agent_instructions_hint),
                minHeight = 168.dp,
            )
        }
        SettingsSection(header = stringResource(R.string.sub_agent_model), footer = stringResource(R.string.sub_agent_model_footer)) {
            SettingsRow(
                title = when {
                    binding == null -> stringResource(R.string.sub_agent_model_auto)
                    else -> pinned?.model?.displayName ?: stringResource(R.string.sub_agent_model_missing)
                },
                onClick = { picker = true },
                showDivider = binding != null,
            )
            if (binding != null) {
                SettingsRow(
                    title = stringResource(R.string.sub_agent_model_auto),
                    onClick = { binding = null },
                    showDivider = false,
                )
            }
        }
        SettingsSection(footer = stringResource(R.string.sub_agent_thinking_footer)) {
            Box {
                SettingsValueRow(
                    title = stringResource(R.string.sub_agent_thinking),
                    value = thinking?.displayName ?: stringResource(R.string.sub_agent_thinking_default),
                    onClick = { thinkingMenu = true },
                    showDivider = false,
                )
                DropdownMenu(expanded = thinkingMenu, onDismissRequest = { thinkingMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sub_agent_thinking_default)) },
                        onClick = { thinking = null; thinkingMenu = false },
                    )
                    ThinkingLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.displayName) },
                            onClick = { thinking = level; thinkingMenu = false },
                        )
                    }
                }
            }
        }
        if (!original.isBuiltIn && !isNew) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.sub_agent_delete),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmDelete = true },
                    showChevron = false,
                    showDivider = false,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (picker) ModelPickerSheet(
        activeEntryId = pinned?.id,
        config = config,
        providerRepository = providerRepository,
        onSelectEntry = { id ->
            if (providerRepository.allVisibleEntries().firstOrNull { it.id == id }?.model?.isTextOutput == true) {
                binding = ModelBinding.encodeEntry(id)
                picker = false
            } else {
                nonTextError = true
            }
        },
        onDismiss = { picker = false },
    )
    if (nonTextError) MinisAlertDialog(
        onDismissRequest = { nonTextError = false },
        title = stringResource(R.string.sub_agent_model),
        text = stringResource(R.string.bots_text_model_required),
        confirmText = stringResource(android.R.string.ok),
        onConfirm = { nonTextError = false },
    )
    if (confirmDelete) MinisAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = stringResource(R.string.sub_agent_delete),
        text = context.getString(R.string.sub_agent_delete_body, original.name),
        confirmText = stringResource(R.string.sub_agent_delete),
        isDestructive = true,
        onConfirm = { confirmDelete = false; onDelete() },
    )
}
