package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.prompt.PromptGate
import com.openminis.app.prompt.PromptModuleStore
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-system-prompt-modules] Advanced editor for the built-in system prompt modules.
 *
 * Reached from Settings -> System prompt ("Built-in prompt modules"), which is
 * the normal place to author prompt text: the device owner's own prompt lives in
 * that free-text box (SystemPromptSettingsScreen). This screen exists for the
 * rare case where one of the shipped sections (tool list, /var/minis paths,
 * minis:// rules, style rules, Android tooling, environment notes) has to be
 * reworded or switched off.
 *
 * Lists every module from `PromptModuleRegistry` in prompt order, grouped by
 * section. Saving writes a user override; reset deletes it so the module returns
 * to the wording shipped in `assets/prompts/`. The SOUL.md identity layer is
 * edited in Settings -> Soul, and the per-turn runtime fragments (skills / MCP /
 * memory / runtime context) are derived from live state - neither is part of
 * this list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptModulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Live store state: every write path (this screen, `minis-config`) refreshes
    // the store, which republishes snapshotsFlow — so a change made by the agent
    // while this screen is open lands here without leaving and re-entering.
    val snapshots by PromptModuleStore.snapshotsFlow.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    // Covers a cold process cache (warm() normally beats us to it at app start).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PromptModuleStore.refresh(context) } }

    val loaded = snapshots.ifEmpty { null }
    SettingsScaffold(
        title = stringResource(R.string.system_prompt_modules_title),
        onBack = onBack,
        actions = {
            if (loaded?.any { it.isCustomized || !it.isEnabled } == true) {
                MinisTextButton(onClick = { confirmResetAll = true }) {
                    Text(stringResource(R.string.system_prompt_reset_all))
                }
            }
        },
    ) {
        Text(
            text = stringResource(R.string.system_prompt_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        loaded.orEmpty().groupBy { it.module.group }.forEach { (group, modules) ->
            SettingsSection(header = group) {
                modules.forEachIndexed { index, snapshot ->
                    SettingsRow(
                        title = snapshot.module.title,
                        subtitle = moduleSubtitle(snapshot),
                        onClick = { editingId = snapshot.module.id },
                        showDivider = index < modules.lastIndex,
                        trailing = {
                            Switch(
                                checked = snapshot.isEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            PromptModuleStore.setEnabled(context, snapshot.module.id, enabled)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    val editing = loaded?.firstOrNull { it.module.id == editingId }
    if (editing != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModuleEditorSheet(
            snapshot = editing,
            sheetState = sheetState,
            onDismiss = { editingId = null },
            onSave = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        PromptModuleStore.saveOverride(context, editing.module.id, text)
                    }
                    editingId = null
                }
            },
            onReset = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        PromptModuleStore.resetOverride(context, editing.module.id)
                    }
                }
            },
        )
    }

    if (confirmResetAll) {
        MinisAlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = stringResource(R.string.system_prompt_reset_all_confirm_title),
            text = stringResource(R.string.system_prompt_reset_all_confirm_body),
            confirmText = stringResource(R.string.system_prompt_reset_all),
            isDestructive = true,
            onConfirm = {
                confirmResetAll = false
                scope.launch {
                    withContext(Dispatchers.IO) { PromptModuleStore.resetAll(context) }
                }
            },
        )
    }
}

@Composable
private fun moduleSubtitle(snapshot: PromptModuleStore.ModuleSnapshot): String {
    val parts = buildList {
        add(snapshot.module.id)
        add(
            stringResource(
                if (snapshot.isCustomized) R.string.system_prompt_badge_custom
                else R.string.system_prompt_badge_default
            )
        )
        when (snapshot.module.gate) {
            PromptGate.MEMORY_ON -> add(stringResource(R.string.system_prompt_gate_memory_on))
            PromptGate.MEMORY_OFF -> add(stringResource(R.string.system_prompt_gate_memory_off))
            PromptGate.ALWAYS -> Unit
        }
        if (!snapshot.isEnabled) add(stringResource(R.string.system_prompt_disabled_note))
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleEditorSheet(
    snapshot: PromptModuleStore.ModuleSnapshot,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember(snapshot.module.id, snapshot.text) { mutableStateOf(snapshot.text) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(snapshot.module.title, style = MaterialTheme.typography.titleMedium)
            Text(
                snapshot.module.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                snapshot.module.id,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text(stringResource(R.string.system_prompt_editor_hint)) },
            )
            Text(
                stringResource(R.string.system_prompt_char_count, text.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MinisTextButton(onClick = onReset) {
                    Text(stringResource(R.string.system_prompt_reset_module))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    MinisTextButton(onClick = { onSave(text) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
