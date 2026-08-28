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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.tools.AgentTools
import com.openminis.app.ui.settings.SettingsChoiceRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.ui.settings.SettingsValueRow
import kotlinx.coroutines.launch

/**
 * First-class editor for GH#32 session-local agent configuration.
 *
 * Storage stays sparse: a disabled "Custom" switch writes null for that field,
 * which means the next request inherits the then-current global/model default.
 * Memory and thinking deliberately reuse ChatViewModel's existing per-session
 * state/persistence paths instead of being duplicated inside SessionOverrides.
 */
@Composable
fun SessionAdvancedSettingsSheet(
    sessionId: String,
    chatRepository: ChatRepository,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val memoryEnabled by viewModel.memoryEnabled.collectAsState()
    val thinkingLevel by viewModel.thinkingLevel.collectAsState()
    val toolDefinitions = remember {
        AgentTools.makeAgentTools()
            .distinctBy { it.name }
            .sortedBy { it.name }
    }
    val allToolIds = remember(toolDefinitions) { toolDefinitions.map { it.name }.toSet() }

    var persistedSessionId by remember(sessionId) { mutableStateOf<String?>(null) }
    var isLoading by remember(sessionId) { mutableStateOf(true) }
    var isSaving by remember(sessionId) { mutableStateOf(false) }
    var errorText by remember(sessionId) { mutableStateOf<String?>(null) }

    var customInstructions by remember(sessionId) { mutableStateOf(false) }
    var instructionsText by remember(sessionId) { mutableStateOf("") }
    var customTemperature by remember(sessionId) { mutableStateOf(false) }
    var temperatureText by remember(sessionId) { mutableStateOf("") }
    var customTopP by remember(sessionId) { mutableStateOf(false) }
    var topPText by remember(sessionId) { mutableStateOf("") }
    var customTopK by remember(sessionId) { mutableStateOf(false) }
    var topKText by remember(sessionId) { mutableStateOf("") }
    var customMaxTokens by remember(sessionId) { mutableStateOf(false) }
    var maxTokensText by remember(sessionId) { mutableStateOf("") }
    var customTools by remember(sessionId) { mutableStateOf(false) }
    var selectedToolIds by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }

    val missingSessionMessage = stringResource(R.string.session_advanced_missing_session)
    val saveFailedMessage = stringResource(R.string.session_advanced_save_failed)
    val resetFailedMessage = stringResource(R.string.session_advanced_reset_failed)
    val invalidPromptMessage = stringResource(R.string.session_advanced_invalid_prompt)
    val invalidTemperatureMessage = stringResource(R.string.session_advanced_invalid_temperature)
    val invalidTopPMessage = stringResource(R.string.session_advanced_invalid_top_p)
    val invalidTopKMessage = stringResource(R.string.session_advanced_invalid_top_k)
    val invalidMaxTokensMessage = stringResource(R.string.session_advanced_invalid_max_tokens)

    LaunchedEffect(sessionId) {
        // A brand-new chat uses a synthetic __new__ id until first send. Opening
        // settings is itself a durable per-session action, so materialize the row
        // first and then edit the real id instead of reporting a false "missing".
        val sid = runCatching { viewModel.ensureSessionForSettings() }.getOrNull()
        val session = sid?.let { realId ->
            runCatching { chatRepository.dao.getSession(realId) }.getOrNull()
        }
        if (sid == null || session == null) {
            isLoading = false
            errorText = missingSessionMessage
            return@LaunchedEffect
        }

        persistedSessionId = sid
        val overrides = SessionOverrides.fromJson(session.sessionOverrides)
        customInstructions = overrides.systemPrompt != null
        instructionsText = overrides.systemPrompt.orEmpty()
        customTemperature = overrides.temperature != null
        temperatureText = overrides.temperature?.toString().orEmpty()
        customTopP = overrides.topP != null
        topPText = overrides.topP?.toString().orEmpty()
        customTopK = overrides.topK != null
        topKText = overrides.topK?.toString().orEmpty()
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

        val topP = if (customTopP) {
            val value = topPText.trim().toDoubleOrNull()
            if (value == null || !value.isFinite() || value !in 0.0..1.0) {
                errorText = invalidTopPMessage
                return null
            }
            value
        } else {
            null
        }

        val topK = if (customTopK) {
            val value = topKText.trim().toIntOrNull()
            if (value == null || value <= 0) {
                errorText = invalidTopKMessage
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
            topP = topP,
            topK = topK,
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
                    header = stringResource(R.string.session_advanced_runtime_section),
                    footer = stringResource(R.string.session_advanced_runtime_help),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_memory),
                        subtitle = stringResource(R.string.session_advanced_memory_help),
                        checked = memoryEnabled,
                        onCheckedChange = viewModel::setMemoryEnabledForSession,
                        showDivider = true,
                    )

                    if (viewModel.currentModelSupportsReasoning) {
                        val levels = listOf(ThinkingLevel.OFF) + viewModel.availableThinkingLevels
                        levels.distinct().forEachIndexed { index, level ->
                            SettingsChoiceRow(
                                title = if (index == 0) {
                                    "${stringResource(R.string.session_advanced_thinking)} · ${level.displayName}"
                                } else {
                                    level.displayName
                                },
                                selected = thinkingLevel == level,
                                onSelect = { viewModel.setThinkingLevel(level) },
                                showDivider = index < levels.distinct().lastIndex,
                            )
                        }
                    } else {
                        SettingsValueRow(
                            title = stringResource(R.string.session_advanced_thinking),
                            value = stringResource(R.string.session_advanced_thinking_not_supported),
                            showDivider = false,
                        )
                    }
                }
            }

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
                SettingsSection(
                    header = stringResource(R.string.session_advanced_model_section),
                    footer = stringResource(R.string.session_advanced_sampling_help),
                ) {
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
                        title = stringResource(R.string.session_advanced_top_p),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customTopP,
                        onCheckedChange = { customTopP = it; errorText = null },
                        showDivider = true,
                    )
                    if (customTopP) {
                        OutlinedTextField(
                            value = topPText,
                            onValueChange = { topPText = it; errorText = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            label = { Text(stringResource(R.string.session_advanced_top_p)) },
                            placeholder = { Text(stringResource(R.string.session_advanced_top_p_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }

                    SettingsSwitchRow(
                        title = stringResource(R.string.session_advanced_top_k),
                        subtitle = stringResource(R.string.session_advanced_custom),
                        checked = customTopK,
                        onCheckedChange = { customTopK = it; errorText = null },
                        showDivider = true,
                    )
                    if (customTopK) {
                        OutlinedTextField(
                            value = topKText,
                            onValueChange = { topKText = it; errorText = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            label = { Text(stringResource(R.string.session_advanced_top_k)) },
                            placeholder = { Text(stringResource(R.string.session_advanced_top_k_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                            if (enabled && selectedToolIds.isEmpty()) selectedToolIds = allToolIds
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
                        enabled = !isSaving && persistedSessionId != null,
                        onClick = {
                            val sid = persistedSessionId ?: return@TextButton
                            isSaving = true
                            errorText = null
                            scope.launch {
                                val result = runCatching {
                                    chatRepository.dao.updateSessionOverrides(sid, null)
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
                        enabled = !isSaving && persistedSessionId != null && errorText != missingSessionMessage,
                        onClick = save@{
                            val sid = persistedSessionId ?: return@save
                            val overrides = buildOverridesOrShowError() ?: return@save
                            isSaving = true
                            scope.launch {
                                val result = runCatching {
                                    chatRepository.dao.updateSessionOverrides(
                                        sid,
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
