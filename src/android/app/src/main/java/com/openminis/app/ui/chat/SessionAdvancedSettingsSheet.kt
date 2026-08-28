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
 *
 * Session Soul is special: null means "use global SOUL.md". A custom Soul may
 * only be selected while the conversation is still empty. Once the first user
 * message exists, that choice is immutable for the lifetime of the session.
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
    var soulLocked by remember(sessionId) { mutableStateOf(false) }
    var lockedSoulText by remember(sessionId) { mutableStateOf<String?>(null) }
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

    /**
     * Re-check at write time, not only when the sheet opened. A remote send or
     * another surface could start the conversation while this sheet is still
     * visible. lastMessage keeps the lock sticky even after Clear Chat removes
     * message rows, while the user-row check covers sessions whose preview has
     * not been populated yet.
     */
    suspend fun currentSoulLockState(sid: String): Pair<Boolean, String?> {
        val latestSession = runCatching { chatRepository.dao.getSession(sid) }.getOrNull()
        val hasUserMessage = runCatching {
            chatRepository.dao.loadMessages(sid).any { it.role == "user" }
        }.getOrDefault(false)
        val locked = hasUserMessage || latestSession?.lastMessage != null
        val soul = SessionOverrides.fromJson(latestSession?.sessionOverrides).editableSystemPrompt()
        return locked to soul
    }

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
        val editableSoul = overrides.editableSystemPrompt()
        val (locked, persistedSoul) = currentSoulLockState(sid)
        soulLocked = locked
        lockedSoulText = persistedSoul
        customInstructions = editableSoul != null
        instructionsText = editableSoul.orEmpty()
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

        val prompt = if (soulLocked) {
            // Immutable after the first user message: always preserve the value
            // that was already persisted when the session became active.
            lockedSoulText
        } else if (customInstructions) {
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
                    footer = if (soulLocked) {
                        stringResource(R.string.session_advanced_soul_locked_help)
                    } else {
                        stringResource(R.string.session_advanced_soul_help)
                    },
                ) {
                    if (soulLocked) {
                        SettingsValueRow(
                            title = stringResource(R.string.session_advanced_system_prompt),
                            value = if (customInstructions) {
                                stringResource(R.string.session_advanced_soul_locked_custom)
                            } else {
                                stringResource(R.string.session_advanced_soul_locked_global)
                            },
                            showDivider = customInstructions,
                        )
                        if (customInstructions) {
                            OutlinedTextField(
                                value = instructionsText,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                minLines = 4,
                                maxLines = 10,
                            )
                        }
                    } else {
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
            }

            item {
                SettingsSection(
                    header = stringResource(R.string.session_advanced_model_section),
                    footer = stringResource(R.string.session_advanced_settings_inherit_help),
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
                                    val (lockedNow, existingSoul) = currentSoulLockState(sid)
                                    val resetValue = if (lockedNow) {
                                        // Reset every mutable advanced override, but never
                                        // change the Soul decision after the chat has begun.
                                        SessionOverrides(systemPrompt = existingSoul).toJsonOrNull()
                                    } else {
                                        null
                                    }
                                    chatRepository.dao.updateSessionOverrides(sid, resetValue)
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
                            val requested = buildOverridesOrShowError() ?: return@save
                            isSaving = true
                            scope.launch {
                                val result = runCatching {
                                    // Re-check the lock immediately before the write. If
                                    // another surface sent the first message while this
                                    // sheet was open, preserve the already-persisted Soul.
                                    val (lockedNow, existingSoul) = currentSoulLockState(sid)
                                    val safeOverrides = if (lockedNow) {
                                        requested.copy(systemPrompt = existingSoul)
                                    } else {
                                        requested
                                    }
                                    chatRepository.dao.updateSessionOverrides(
                                        sid,
                                        safeOverrides.toJsonOrNull(),
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
