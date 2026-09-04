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
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.ui.settings.SettingsValueRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var persistedSessionId by remember(sessionId) { mutableStateOf<String?>(null) }
    var isLoading by remember(sessionId) { mutableStateOf(true) }
    var isSaving by remember(sessionId) { mutableStateOf(false) }
    var errorText by remember(sessionId) { mutableStateOf<String?>(null) }

    var customInstructions by remember(sessionId) { mutableStateOf(false) }
    var instructionsText by remember(sessionId) { mutableStateOf("") }
    var soulLocked by remember(sessionId) { mutableStateOf(false) }
    var lockedSoulText by remember(sessionId) { mutableStateOf<String?>(null) }

    val missingSessionMessage = stringResource(R.string.session_advanced_missing_session)
    val saveFailedMessage = stringResource(R.string.session_advanced_save_failed)
    val resetFailedMessage = stringResource(R.string.session_advanced_reset_failed)
    val invalidPromptMessage = stringResource(R.string.session_advanced_invalid_prompt)

    /**
     * Re-check at write time, not only when the sheet opened. A remote send or
     * another surface could start the conversation while this sheet is still
     * visible. lastMessage keeps the lock sticky even after Clear Chat removes
     * message rows, while the user-row check covers sessions whose preview has
     * not been populated yet.
     */
    suspend fun currentSoulLockState(sid: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val latestSession = runCatching { chatRepository.dao.getSession(sid) }.getOrNull()
        val hasUserMessage = runCatching {
            chatRepository.dao.loadMessages(sid).any { it.role == "user" }
        }.getOrDefault(false)
        val locked = hasUserMessage || latestSession?.lastMessage != null
        val soul = SessionOverrides.fromJson(latestSession?.sessionOverrides).editableSystemPrompt()
        locked to soul
    }

    LaunchedEffect(Unit) {
        isLoading = true
        errorText = null
        try {
            android.util.Log.e("PROMPT_DEBUG", "LaunchedEffect started: prop sessionId='$sessionId', vm.sessionId='${viewModel.sessionId}', vm.activeSessionId='${viewModel.activeSessionId}'")
            val sid = viewModel.ensureSessionForSettings()
            android.util.Log.e("PROMPT_DEBUG", "ensureSessionForSettings returned sid='$sid'")
            val session = withContext(Dispatchers.IO) {
                chatRepository.dao.getSession(sid)
            }
            android.util.Log.e("PROMPT_DEBUG", "chatRepository.dao.getSession('$sid') returned: $session")
            if (session == null) {
                android.util.Log.e("PROMPT_DEBUG", "Session is NULL for sid='$sid'!")
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
            isLoading = false
        } catch (t: Throwable) {
            android.util.Log.e("SessionAdvancedSettings", "Failed to load session settings", t)
            isLoading = false
            errorText = missingSessionMessage
        }
    }

    fun buildOverridesOrShowError(): SessionOverrides? {
        errorText = null

        val prompt = if (soulLocked) {
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

        return SessionOverrides(
            systemPrompt = prompt,
        )
    }

    StandardChatSheet(
        title = "会话提示词",
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
                    header = "会话提示词",
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
                                    withContext(Dispatchers.IO) {
                                        val (lockedNow, existingSoul) = currentSoulLockState(sid)
                                        val resetValue = if (lockedNow) {
                                            SessionOverrides(systemPrompt = existingSoul).toJsonOrNull()
                                        } else {
                                            null
                                        }
                                        chatRepository.dao.updateSessionOverrides(sid, resetValue)
                                    }
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
                                    withContext(Dispatchers.IO) {
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
