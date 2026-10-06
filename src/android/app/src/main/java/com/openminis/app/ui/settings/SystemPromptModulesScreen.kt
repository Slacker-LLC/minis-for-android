package com.openminis.app.ui.settings

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
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
import com.openminis.app.ui.components.MinisModalBottomSheet

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
        backLabel = stringResource(R.string.settings_system_prompt),
        actions = {
            if (loaded?.any { it.isCustomized || !it.isEnabled } == true) {
                MinisTextButton(onClick = { confirmResetAll = true }) {
                    Text(stringResource(R.string.system_prompt_reset_all))
                }
            }
        },
    ) {
        loaded.orEmpty().groupBy { it.module.group }.forEach { (group, modules) ->
            SettingsSection(header = group) {
                modules.forEachIndexed { index, snapshot ->
                    SettingsRow(
                        title = snapshot.module.title,
                        subtitle = moduleSubtitle(snapshot),
                        onClick = { editingId = snapshot.module.id },
                        showDivider = index < modules.lastIndex,
                        trailing = {
                            MinisSwitch(
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
    // Keyed on the module only: a change to the stored text while the sheet is open (an Agent write)
    // must not replace what the user has typed and not yet saved.
    var text by remember(snapshot.module.id) { mutableStateOf(snapshot.text) }

    MinisModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = settingsSheetColor()) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Cancel / title / Save, the board's sheet header.
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                MinisTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text(stringResource(android.R.string.cancel), fontSize = 17.sp)
                }
                Text(
                    snapshot.module.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp),
                )
                MinisTextButton(onClick = { onSave(text) }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(stringResource(R.string.save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                snapshot.module.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            SettingsSection {
                SettingsTextArea(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.system_prompt_editor_hint),
                    monospace = true,
                    minHeight = 220.dp,
                    maxHeight = 420.dp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onReset) {
                    Text(stringResource(R.string.system_prompt_reset_module), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    stringResource(R.string.system_prompt_char_count, text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
