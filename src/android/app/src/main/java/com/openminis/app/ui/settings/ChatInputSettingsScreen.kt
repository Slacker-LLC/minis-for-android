package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors

/**
 * "Chat & input" settings page (design: Settings > Chat & input).
 *
 * The rows used to live at the bottom of Appearance, after the theme and font sections. They are
 * about how a conversation behaves, not how the app looks, so they are their own page now: input
 * (return key, focus after reply), reply display (thinking, chat title, tool preview, work
 * process), session (launch target, auto grouping) and the screen-awake switch. The preference
 * keys and accessors are unchanged and still defined in AppearanceScreen.kt.
 */
@Composable
fun ChatInputSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var launchSession by remember { mutableIntStateOf(prefs.getInt(KEY_LAUNCH_SESSION, 0)) }
    var returnKeyBehavior by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)) }
    var toolPreview by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_PREVIEW, true)) }
    var toolStatusBar by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_STATUS_BAR, true)) }
    var autoFocusAfterReply by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, true)) }
    var autoExpandThinking by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_EXPAND_THINKING, true)) }
    var showChatTitle by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }
    var autoGrouping by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_GROUPING, true)) }
    var correctionEnabled by remember {
        mutableStateOf(com.openminis.app.speech.correction.VoiceCorrectionConsent.isEnabled(context))
    }
    var showClearCorrectionConfirm by remember { mutableStateOf(false) }

    // [T-android-work-process] Collected, not remembered: the value lives in a
    // process-wide StateFlow so the toggle and ChatScreen stay in sync.
    val stepsPresentation by com.openminis.app.data.StepsPresentationPrefs.value.collectAsState()

    val tilePurple = Color(0xFF5856D6)
    val tileBlue = Color(0xFF007AFF)
    val tileOrange = ChatColors.warn
    val tileGreen = ChatColors.ok
    val tileTeal = Color(0xFF5AC8FA)

    SettingsScaffold(
        title = stringResource(R.string.settings_cat_chat),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_title),
        largeTitle = true,
    ) {

        // -- Return Key (mirrors iOS AppearanceSettingsView stringResource(R.string.appearance_section_return_key) section) --
        // 0=Newline (default), 1=Send. Hardware Shift+Enter always inserts a
        // newline regardless of this setting — matches iOS behavior and
        // overrides the read in ChatScreen's onKeyEvent handler.
        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
        ) {
            data class ReturnRow(val label: String, val value: Int)
            val returnRows = listOf(
                ReturnRow(stringResource(R.string.appearance_return_key_newline), 0),
                ReturnRow(stringResource(R.string.appearance_return_key_send), 1),
            )
            returnRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = returnKeyBehavior == row.value,
                    onSelect = {
                        returnKeyBehavior = row.value
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, row.value).apply()
                    },
                    leading = {
                        if (idx == 0) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                tint = tilePurple,
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                tint = tileGreen,
                            )
                        }
                    },
                    showDivider = idx < returnRows.size - 1,
                )
            }
        }

        // [T-keyboard-auto-pop default flip] -- Auto-Focus After Reply --
        // Default ON — most users want the composer ready for a follow-up
        // immediately after the model finishes.
        SettingsSection(
            header = stringResource(R.string.appearance_section_auto_focus_after_reply),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Keyboard,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_focus_after_reply_title),
                checked = autoFocusAfterReply,
                onCheckedChange = {
                    autoFocusAfterReply = it
                    prefs.edit().putBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-thinking-auto-expand-toggle] -- Deep Thinking --
        // Whether a NEW streaming thinking block opens expanded (historical
        // behavior, default ON) or stays collapsed. Only affects the streaming
        // auto-expand; manual taps always work either way. Mirrors iOS
        // AppearanceSettingsView "Deep Thinking" section.
        SettingsSection(
            header = stringResource(R.string.appearance_section_deep_thinking),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Chat Title (T-chat-title-pill) --
        // Sticky session-title pill that appears at the top of the chat
        // when the user scrolls back through history. Default ON; toggle
        // also reachable via `minis-config set appearance.show_chat_title`.
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_title),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_show_chat_title),
                subtitle = stringResource(R.string.appearance_show_chat_title_subtitle),
                checked = showChatTitle,
                onCheckedChange = {
                    showChatTitle = it
                    prefs.edit().putBoolean(KEY_SHOW_CHAT_TITLE, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Tool Status Bar --
        SettingsSection(
            header = stringResource(R.string.appearance_section_tool_preview),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_preview_title),
                checked = toolPreview,
                onCheckedChange = {
                    toolPreview = it
                    prefs.edit().putBoolean(KEY_TOOL_PREVIEW, it).apply()
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Build,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_status_bar_title),
                subtitle = stringResource(R.string.appearance_tool_status_bar_sub),
                checked = toolStatusBar,
                onCheckedChange = {
                    toolStatusBar = it
                    prefs.edit().putBoolean(KEY_TOOL_STATUS_BAR, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-android-work-process] -- Work Process --
        // Whether a turn's consecutive thinking + tool steps collapse into one
        // expandable row (default, mirrors Eta's AgentWorkProcess) or keep the
        // historical one-row-per-step layout with its long-press menus.
        SettingsSection(
            header = stringResource(R.string.appearance_section_work_process),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Build,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_steps_presentation_title),
                subtitle = stringResource(
                    if (stepsPresentation == com.openminis.app.data.StepsPresentation.GROUPED) {
                        R.string.appearance_steps_presentation_on
                    } else {
                        R.string.appearance_steps_presentation_off
                    },
                ),
                checked = stepsPresentation == com.openminis.app.data.StepsPresentation.GROUPED,
                onCheckedChange = { grouped ->
                    com.openminis.app.data.StepsPresentationPrefs.set(
                        context,
                        if (grouped) {
                            com.openminis.app.data.StepsPresentation.GROUPED
                        } else {
                            com.openminis.app.data.StepsPresentation.PER_TOOL
                        },
                    )
                },
                showDivider = false,
            )
        }

        // -- Launch Session --
        // Same per-row icon treatment as the Theme section. Bolt = Auto
        // (system picks), History = Last Session (revisit), ChatBubble =
        // New Chat (compose), Home = Home screen.
        SettingsSection(
            header = stringResource(R.string.appearance_section_launch),
        ) {
            data class LaunchRow(val label: String, val icon: ImageVector, val tint: Color)
            val launchRows = listOf(
                LaunchRow(stringResource(R.string.appearance_launch_auto), Icons.Outlined.Bolt, tileBlue),
                LaunchRow(stringResource(R.string.appearance_launch_last), Icons.Outlined.History, tileTeal),
                LaunchRow(stringResource(R.string.appearance_launch_new), Icons.Outlined.ChatBubbleOutline, tileGreen),
                LaunchRow(stringResource(R.string.appearance_launch_home), Icons.Outlined.Home, tileBlue),
            )
            launchRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = launchSession == idx,
                    onSelect = {
                        launchSession = idx
                        prefs.edit().putInt(KEY_LAUNCH_SESSION, idx).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < launchRows.size - 1,
                )
            }
        }

        // -- Auto-Grouping (T-android-auto-grouping) --
        // Rides the title-generation call, so enabling it costs no extra
        // request. Port of iOS ContentView's "Grouping" section.
        SettingsSection(
            header = stringResource(R.string.appearance_section_grouping),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Folder,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_grouping),
                subtitle = stringResource(R.string.appearance_auto_grouping_subtitle),
                checked = autoGrouping,
                onCheckedChange = {
                    autoGrouping = it
                    prefs.edit().putBoolean(KEY_AUTO_GROUPING, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Keep Screen Awake --
        // Holds FLAG_KEEP_SCREEN_ON on the activity window while any session
        // has an active task (mirrors iOS UIApplication.isIdleTimerDisabled
        // pattern in KeepScreenAwakeController). Default off — battery cost
        // is real and most users don't need it.
        SettingsSection(
            header = stringResource(R.string.appearance_section_keep_awake),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ScreenLockPortrait,
                iconColor = tileGreen,
                title = stringResource(R.string.appearance_keep_awake_title),
                checked = keepScreenAwake,
                onCheckedChange = {
                    keepScreenAwake = it
                    prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, it).apply()
                },
                showDivider = false,
            )
        }


        // [T-android-settings-hierarchy] Voice-correction learning is a chat-input behaviour.
        SettingsSection(
            header = stringResource(R.string.voice_correction_section),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = stringResource(R.string.voice_correction_toggle),
                checked = correctionEnabled,
                onCheckedChange = { on ->
                    correctionEnabled = on
                    com.openminis.app.speech.correction.VoiceCorrectionConsent.setEnabled(context, on)
                    com.openminis.app.speech.correction.VoiceCorrectionConsent.setPrompted(context, true)
                },
            )
            SettingsRow(
                icon = Icons.Outlined.DeleteSweep,
                iconColor = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.voice_correction_clear),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { showClearCorrectionConfirm = true },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showClearCorrectionConfirm) {
        MinisAlertDialog(
            onDismissRequest = { showClearCorrectionConfirm = false },
            title = { Text(stringResource(R.string.voice_correction_clear_title)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearCorrectionConfirm = false
                    com.openminis.app.speech.correction.VoiceCorrection.clearAllData(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.voice_correction_cleared),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(
                        stringResource(R.string.voice_correction_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearCorrectionConfirm = false }) {
                    Text(stringResource(R.string.voice_correction_consent_not_now))
                }
            },
        )
    }
}
