package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
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
import com.openminis.app.R
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
fun ChatInputSettingsScreen(onBack: () -> Unit, onOpenVoiceCall: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var launchSession by remember { mutableIntStateOf(prefs.getInt(KEY_LAUNCH_SESSION, 0)) }
    var returnKeyBehavior by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)) }
    var toolPreview by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_PREVIEW, true)) }
    var toolStatusBar by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_STATUS_BAR, false)) }
    var autoFocusAfterReply by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, true)) }
    var autoExpandThinking by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_EXPAND_THINKING, true)) }
    var showChatTitle by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }
    var autoGrouping by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_GROUPING, true)) }

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
        // 输入：回车键（0=换行，1=发送；实体键盘 Shift+Enter 永远换行）+ 回复后自动聚焦（默认开）。
        SettingsSection(header = stringResource(R.string.chat_settings_group_input)) {
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                iconColor = tilePurple,
                title = stringResource(R.string.chat_settings_return_key),
                subtitle = stringResource(R.string.appearance_return_key_footer),
                showDivider = false,
            )
            SettingsCardBlock {
                SettingsSegmented(
                    options = listOf(
                        stringResource(R.string.appearance_return_key_newline),
                        stringResource(R.string.appearance_return_key_send),
                    ),
                    selectedIndex = returnKeyBehavior.coerceIn(0, 1),
                    onSelect = {
                        returnKeyBehavior = it
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, it).apply()
                    },
                )
            }
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

        // 回复显示：思考块、顶部标题条、工具预览/状态栏、工作过程折叠。
        SettingsSection(header = stringResource(R.string.chat_settings_group_reply)) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
            )
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
            )
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
            )
            val grouped = stepsPresentation == com.openminis.app.data.StepsPresentation.GROUPED
            SettingsSwitchRow(
                icon = Icons.Outlined.Build,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_steps_presentation_title),
                subtitle = stringResource(
                    if (grouped) R.string.appearance_steps_presentation_on else R.string.appearance_steps_presentation_off,
                ),
                checked = grouped,
                onCheckedChange = { on ->
                    com.openminis.app.data.StepsPresentationPrefs.set(
                        context,
                        if (on) com.openminis.app.data.StepsPresentation.GROUPED
                        else com.openminis.app.data.StepsPresentation.PER_TOOL,
                    )
                },
                showDivider = false,
            )
        }

        // 会话：启动时进哪里、自动归类（借标题生成请求，不多花一次调用）、任务期间保持亮屏（默认关，费电）。
        SettingsSection(header = stringResource(R.string.chat_settings_group_session)) {
            SettingsRow(
                icon = Icons.Outlined.Bolt,
                iconColor = tileBlue,
                title = stringResource(R.string.chat_settings_launch),
                subtitle = stringResource(R.string.appearance_launch_footer),
                showDivider = false,
            )
            SettingsCardBlock {
                SettingsSegmented(
                    options = listOf(
                        stringResource(R.string.appearance_launch_auto),
                        stringResource(R.string.appearance_launch_last),
                        stringResource(R.string.appearance_launch_new),
                        stringResource(R.string.appearance_launch_home),
                    ),
                    selectedIndex = launchSession.coerceIn(0, 3),
                    onSelect = {
                        launchSession = it
                        prefs.edit().putInt(KEY_LAUNCH_SESSION, it).apply()
                    },
                )
            }
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
            )
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

        // 语音：通话设置入口 + 听写纠正学习。
        SettingsSection(header = stringResource(R.string.chat_settings_group_voice)) {
            SettingsRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = stringResource(R.string.voice_call_settings_title),
                subtitle = stringResource(R.string.voice_call_settings_sub),
                onClick = onOpenVoiceCall,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
