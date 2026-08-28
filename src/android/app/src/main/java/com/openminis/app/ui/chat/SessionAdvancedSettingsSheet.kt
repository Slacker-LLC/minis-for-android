package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.AgentTools
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * First-class editor for GH#32 session-local agent configuration.
 *
 * Storage stays sparse: a disabled "Custom" switch writes null for that field,
 * which means the next request inherits the then-current global/model default.
 * This sheet intentionally edits only parameters that are already enforced by
 * the Android runtime. topP/topK remain preserved in the JSON payload until the
 * provider API can actually apply them, so opening/saving this UI cannot erase
 * values written by minis-config or a future client.
 */
@Composable
fun SessionAdvancedSettingsSheet(
    sessionId: String,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toolDefinitions = remember {
        AgentTools.makeAgentTools()
            .distinctBy { it.name }
            .sortedBy { it.name }
    }
    val allToolIds = remember(toolDefinitions) { toolDefinitions.map { it.name }.toSet() }

    var isLoading by remember(sessionId) { mutableStateOf(true) }
    var isSaving by remember(sessionId) { mutableStateOf(false) }
    var errorText by remember(sessionId) { mutableStateOf<String?>(null) }
    var loadedOverrides by remember(sessionId) { mutableStateOf(SessionOverrides()) }

    var customInstructions by remember(sessionId) { mutableStateOf(false) }
    var instructionsText by remember(sessionId) { mutableStateOf("") }
    var customTemperature by remember(sessionId) { mutableStateOf(false) }
    var temperatureText by remember(sessionId) { mutableStateOf("") }
    var customMaxTokens by remember(sessionId) { mutableStateOf(false) }
    var maxTokensText by remember(sessionId) { mutableStateOf("") }
    var customTools by remember(sessionId) { mutableStateOf(false) }
    var selectedToolIds by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }

    val missingSessionMessage = stringResource(R.string.session_advanced_missing_session)
    val saveFailedMessage = stringResource(R.string.session_advanced_save_failed)
    val resetFailedMessage = stringResource(R.string.session_advanced_reset_failed)
    val invalidPromptMessage = stringResource(R.string.session_advanced_invalid_prompt)
    val invalidTemperatureMessage = stringResource(R.string.session_advanced_invalid_temperature)
    val invalidMaxTokensMessage = stringResource(R.string.session_advanced_invalid_max_tokens)

    LaunchedEffect(sessionId) {
        val session = runCatching { chatRepository.dao.getSession(sessionId) }.getOrNull()
        if (session == null) {
            isLoading = false
            errorText = missingSessionMessage
            return@LaunchedEffect
        }

        val overrides = SessionOverrides.fromJson(session.sessionOverrides)
        loadedOverrides = overrides
        customInstructions = overrides.systemPrompt != null
        instructionsText = overrides.systemPrompt.orEmpty()
        customTemperature = overrides.temperature != null
        temperatureText = overrides.temperature?.toString().orEmpty()
        customMaxTokens = overrides.maxTokens != null
        maxTokensText = overrides.maxTokens?.toString().orEmpty()
        customTools = overrides.enabledTools != null
        selectedToolIds = overrides.enabledTools ?: allToolIds
        isLoading = false
    }

    fun buildOverridesOrShowError(): SessionOverrides? {
        errorText = null

        val prompt = if (customInstructions) {
            instructionsText.trim().takeIf { it.isNotEmpty() }
                ?: run {
                    errorText = invalidPromptMessage
                    return null
                }
        } else {
            null
        }

        val temperature = if (customTemperature) {
            val value = temperatureText.trim().toDoubleOrNull()
            if (value == null || !value.isFinite() || value !in 0.0..2.0) {
                errorText = invalidTemperatureMessage
                return null
            }
            value
        } else {
            null
        }

        val maxTokens = if (customMaxTokens) {
            val value = maxTokensText.trim().toIntOrNull()
            if (value == null || value <= 0) {
                errorText = invalidMaxTokensMessage
                return null
            }
            value
        } else {
            null
        }

        return SessionOverrides(
            systemPrompt = prompt,
            temperature = temperature,
            // Preserve provider parameters that are not yet runtime-editable on
            // Android. Saving the working subset must not silently clear them.
            topP = loadedOverrides.topP,
            topK = loadedOverrides.topK,
            maxTokens = maxTokens,
            enabledTools = if (customTools) selectedToolIds else null,
        )
    }

    StandardChatSheet(
        title = stringResource(R.string.session_advanced_settings_title),
        onDismiss = onDismiss,
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.session_advanced_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            return@StandardChatSheet
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.session_advanced_instructions_section),
                    footer = stringResource(R.string.session_advanced_settings_inherit_help),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_system_prompt),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customInstructions,
                        onCheckedChange = { customInstructions = it; errorText = null },
                        showDivider = customInstructions,
                    )
                    if (customInstructions) {
                        OutlinedTextField(
                            value = instructionsText,
                            onValueChange = { instructionsText = it; errorText = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            placeholder = { Text(stringResource(R.string.session_advanced_system_prompt_hint)) },
                            minLines = 4,
                            maxLines = 10,
                        )
                    }
                }
            }

            item {
                SettingsSection(header = stringResource(R.string.session_advanced_model_section)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_temperature),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customTemperature,
                        onCheckedChange = { customTemperature = it; errorText = null },
                        showDivider = true,
                    )
                    if (customTemperature) {
                        OutlinedTextField(
                            value = temperatureText,
                            onValueChange = { temperatureText = it; errorText = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            label = { Text(stringResource(R.string.session_advanced_temperature)) },
                            placeholder = { Text(stringResource(R.string.session_advanced_temperature_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }

                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_max_tokens),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customMaxTokens,
                        onCheckedChange = { customMaxTokens = it; errorText = null },
                        showDivider = customMaxTokens,
                    )
                    if (customMaxTokens) {
                        OutlinedTextField(
                            value = maxTokensText,
                            onValueChange = { maxTokensText = it; errorText = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            label = { Text(stringResource(R.string.session_advanced_max_tokens)) },
                            placeholder = { Text(stringResource(R.string.session_advanced_max_tokens_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }

            item {
                SettingsSection(
                    header = stringResource(R.string.session_advanced_tools_section),
                    footer = stringResource(R.string.session_advanced_tools_help),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_tools),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customTools,
                        onCheckedChange = { enabled ->
                            customTools = enabled
                            if (enabled) selectedToolIds = allToolIds
                            errorText = null
                        },
                        showDivider = customTools && toolDefinitions.isNotEmpty(),
                    )
                    if (customTools) {
                        toolDefinitions.forEachIndexed { index, tool ->
                            SettingsSwitchRow(
                                title = tool.name,
                                checked = tool.name in selectedToolIds,
                                onCheckedChange = { enabled ->
                                    selectedToolIds = if (enabled) {
                                        selectedToolIds + tool.name
                                    } else {
                                        selectedToolIds - tool.name
                                    }
                                },
                                showDivider = index < toolDefinitions.lastIndex,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.padding(bottom = 8.dp)) }

            errorText?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !isSaving,
                        onClick = {
                            isSaving = true
                            errorText = null
                            scope.launch {
                                val result = runCatching {
                                    chatRepository.dao.updateSessionOverrides(sessionId, null)
                                }
                                isSaving = false
                                if (result.isSuccess) {
                                    onDismiss()
                                } else {
                                    errorText = resetFailedMessage
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.session_advanced_reset_all))
                    }

                    Button(
                        enabled = !isSaving && errorText != missingSessionMessage,
                        onClick = save@{
                            val overrides = buildOverridesOrShowError() ?: return@save
                            isSaving = true
                            scope.launch {
                                val result = runCatching {
                                    chatRepository.dao.updateSessionOverrides(
                                        sessionId,
                                        overrides.toJsonOrNull(),
                                    )
                                }
                                isSaving = false
                                if (result.isSuccess) {
                                    onDismiss()
                                } else {
                                    errorText = saveFailedMessage
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.session_advanced_save))
                    }
                }
            }
        }
    }
}
