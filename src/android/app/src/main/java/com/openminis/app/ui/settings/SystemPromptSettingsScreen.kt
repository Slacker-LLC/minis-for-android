package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.prompt.CustomPromptStore
import com.openminis.app.prompt.PromptModuleStore
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-system-prompt-custom] The device owner's system prompt - one text box.
 *
 * Whatever is saved here is injected ahead of every other authored section of
 * the agent prompt (SOUL identity / personality, built-in modules, preset, bot
 * and session guidance) and carries an explicit precedence header, so text
 * written here outranks every other setting. Codex-style custom instructions
 * rather than a per-section editor.
 *
 * The shipped tool / style wording keeps its own advanced editor, reachable from
 * the row at the bottom of this screen.
 */
@Composable
fun SystemPromptSettingsScreen(onBack: () -> Unit, onModulesClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by CustomPromptStore.textFlow.collectAsState()
    val modules by PromptModuleStore.snapshotsFlow.collectAsState()
    // null = show what is stored; non-null = the user is editing.
    var draft by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Covers a cold process cache (warm() normally beats us to it at app start).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CustomPromptStore.refresh(context) } }

    val current = draft ?: saved
    val dirty = current.trim() != saved.trim()
    val customizedCount = modules.count { it.isCustomized || !it.isEnabled }

    SettingsScaffold(
        title = stringResource(R.string.settings_system_prompt),
        onBack = onBack,
        actions = {
            if (dirty) {
                MinisTextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { CustomPromptStore.save(context, current) }
                        draft = null
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.system_prompt_custom_section),
            footer = stringResource(R.string.system_prompt_custom_footer),
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .heightIn(min = 240.dp, max = 420.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.system_prompt_custom_hint)) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(
                    onClick = { confirmClear = true },
                    enabled = current.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.system_prompt_custom_clear))
                }
                Text(
                    text = when {
                        dirty -> stringResource(R.string.system_prompt_custom_status_unsaved)
                        saved.isNotEmpty() -> stringResource(R.string.system_prompt_custom_status_saved)
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsSection(header = stringResource(R.string.system_prompt_advanced_section)) {
            SettingsRow(
                title = stringResource(R.string.system_prompt_modules_entry),
                subtitle = if (customizedCount > 0) {
                    stringResource(R.string.system_prompt_modules_entry_subtitle_custom, customizedCount)
                } else {
                    stringResource(R.string.system_prompt_modules_entry_subtitle)
                },
                onClick = onModulesClick,
                showDivider = false,
            )
        }
    }

    if (confirmClear) {
        MinisAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.system_prompt_custom_clear_confirm_title),
            text = stringResource(R.string.system_prompt_custom_clear_confirm_body),
            confirmText = stringResource(R.string.system_prompt_custom_clear),
            isDestructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { CustomPromptStore.clear(context) }
                    draft = null
                }
            },
        )
    }
}
