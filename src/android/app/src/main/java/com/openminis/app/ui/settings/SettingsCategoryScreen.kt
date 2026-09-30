package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.openminis.app.pet.PetControlActivity
import com.openminis.app.tools.SubagentLimits
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisModalBottomSheet
import com.openminis.app.ui.theme.minisSheetColor

/**
 * [T-android-settings-hierarchy] One level-2 page: the settings that belong to a single category.
 *
 * The Settings home lists seven categories in the order of the design board (Models, Agent, Chat &
 * input, Appearance, Runtime environment, System & permissions, Data & about). Files are not a
 * settings category any more: they are a daily entry in the session drawer. [FILES] stays as a
 * hidden page only because that drawer entry still opens it.
 */
enum class SettingsCategory(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val inRoot: Boolean = true,
) {
    MODELS("models", R.string.settings_cat_models, R.string.settings_cat_models_sub2),
    AGENT("agent", R.string.settings_cat_agent, R.string.settings_cat_agent_sub2),
    CHAT("chat", R.string.settings_cat_chat, R.string.settings_cat_chat_sub),
    APPEARANCE("appearance", R.string.appearance_title, R.string.settings_cat_appearance_sub2),
    RUNTIME("runtime", R.string.settings_cat_runtime, R.string.settings_cat_runtime_sub2),
    SYSTEM("system", R.string.settings_section_system, R.string.settings_cat_system_sub2),
    DATA("data", R.string.settings_cat_data, R.string.settings_cat_data_sub),
    FILES("files", R.string.settings_section_files, R.string.settings_category_files_sub, inRoot = false),
    ;

    companion object {
        /** Cards on the Settings home, top to bottom, as the design groups them. */
        val rootGroups: List<List<SettingsCategory>> = listOf(
            listOf(MODELS, AGENT, CHAT, APPEARANCE),
            listOf(RUNTIME, SYSTEM, DATA),
        )

        /** Also accepts the keys the six- and seven-category layouts used to publish. */
        fun parse(raw: String?): SettingsCategory? = entries.firstOrNull { it.key == raw } ?: when (raw) {
            "assistant" -> AGENT
            "about" -> DATA
            else -> null
        }
    }
}

fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.MODELS -> Icons.Outlined.Memory
    SettingsCategory.AGENT -> Icons.Outlined.AutoAwesome
    SettingsCategory.CHAT -> Icons.Outlined.ChatBubbleOutline
    SettingsCategory.APPEARANCE -> Icons.Outlined.LightMode
    SettingsCategory.RUNTIME -> Icons.Outlined.Dns
    SettingsCategory.SYSTEM -> Icons.Outlined.Shield
    SettingsCategory.DATA -> Icons.Outlined.Inventory2
    SettingsCategory.FILES -> Icons.Outlined.Folder
}

fun SettingsCategory.iconColor(): Color = when (this) {
    SettingsCategory.MODELS -> Color(0xFF007AFF)
    SettingsCategory.AGENT -> Color(0xFFAF52DE)
    SettingsCategory.CHAT -> Color(0xFF34C759)
    SettingsCategory.APPEARANCE -> Color(0xFFFF9500)
    SettingsCategory.RUNTIME -> Color(0xFF636366)
    SettingsCategory.SYSTEM -> Color(0xFFFF3B30)
    SettingsCategory.DATA -> Color(0xFF8E8E93)
    SettingsCategory.FILES -> Color(0xFF007AFF)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    onBack: () -> Unit,
    onProvidersClick: () -> Unit = {},
    onModelsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onCharactersClick: () -> Unit = {},
    onSoulClick: () -> Unit = {},
    onSystemPromptClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    onMcpClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onSystemEnhanceClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onToolPermissionsClick: () -> Unit = {},
    onShizukuClick: () -> Unit = {},
    onVirtualScreenClick: () -> Unit = {},
    onBackgroundClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onFileBrowserClick: () -> Unit = {},
    onRootfsManagementClick: () -> Unit = {},
    onOpenBackground: () -> Unit = {},
) {
    val context = LocalContext.current
    var showSubagentLimits by remember { mutableStateOf(false) }
    var showFeedbackSheet by remember { mutableStateOf(false) }
    var autoCompactState by remember { mutableStateOf(com.openminis.app.data.AutoCompactPrefs.isEnabled()) }

    SettingsScaffold(
        title = stringResource(category.titleRes),
        onBack = onBack,
    ) {
        when (category) {
            SettingsCategory.MODELS -> {
                SettingsSection(
                    footer = stringResource(R.string.settings_section_llm_providers_footer),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Cloud,
                        iconColor = Color(0xFF007AFF),
                        title = stringResource(R.string.settings_manage_providers),
                        subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                        onClick = onProvidersClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Layers,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.model_slots_header),
                        subtitle = stringResource(R.string.model_slots_footer),
                        onClick = onModelsClick,
                        showDivider = false,
                    )
                }
                SettingsSection {
                    SettingsRow(
                        icon = Icons.Outlined.BarChart,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.settings_token_usage),
                        subtitle = stringResource(R.string.settings_token_usage_subtitle),
                        onClick = onUsageClick,
                    )
                    SettingsSwitchRow(
                        icon = Icons.Outlined.AutoAwesome,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.settings_auto_compact),
                        subtitle = stringResource(R.string.settings_auto_compact_subtitle),
                        checked = autoCompactState,
                        onCheckedChange = { checked ->
                            autoCompactState = checked
                            com.openminis.app.data.AutoCompactPrefs.setEnabled(context, checked)
                        },
                        showDivider = false,
                    )
                }
            }

            SettingsCategory.AGENT -> {
                SettingsSection {
                    SettingsRow(
                        icon = Icons.Outlined.Bolt,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.settings_skills),
                        subtitle = stringResource(R.string.settings_skills_subtitle),
                        onClick = onSkillsClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Psychology,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.settings_memory),
                        subtitle = stringResource(R.string.settings_memory_subtitle),
                        onClick = onMemoryClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Dashboard,
                        iconColor = Color(0xFF34C759),
                        title = stringResource(R.string.settings_mcp),
                        subtitle = stringResource(R.string.settings_mcp_subtitle),
                        onClick = onMcpClick,
                        showDivider = false,
                    )
                }
                SettingsSection(header = stringResource(R.string.settings_agent_section_identity)) {
                    SettingsRow(
                        icon = Icons.Outlined.AutoAwesome,
                        iconColor = Color(0xFF007AFF),
                        title = stringResource(R.string.settings_soul),
                        subtitle = stringResource(R.string.settings_soul_subtitle),
                        onClick = onSoulClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        iconColor = Color(0xFFAF52DE),
                        title = stringResource(R.string.characters_title),
                        subtitle = stringResource(R.string.characters_subtitle),
                        onClick = onCharactersClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Description,
                        iconColor = Color(0xFF636366),
                        title = stringResource(R.string.settings_system_prompt),
                        subtitle = stringResource(R.string.settings_system_prompt_subtitle),
                        onClick = onSystemPromptClick,
                        showDivider = false,
                    )
                }
                SettingsSection(header = stringResource(R.string.settings_agent_section_delegation)) {
                    SettingsRow(
                        icon = Icons.Outlined.AccountTree,
                        iconColor = Color(0xFFFF3B30),
                        title = stringResource(R.string.settings_subagent_limits),
                        subtitle = stringResource(
                            R.string.settings_subagent_limits_subtitle,
                            SubagentLimits.maxDepth(context),
                            SubagentLimits.timeoutMs(context) / 60_000L,
                        ),
                        onClick = { showSubagentLimits = true },
                        showDivider = false,
                    )
                }
            }

            // Chat & input, Appearance and Files are pages of their own; the navigation host renders
            // ChatInputSettingsScreen / AppearanceScreen / FilesHubScreen for these keys.
            SettingsCategory.CHAT, SettingsCategory.APPEARANCE, SettingsCategory.FILES -> Unit

            SettingsCategory.RUNTIME -> {
                SettingsSection {
                    SettingsRow(
                        icon = Icons.Outlined.Dns,
                        iconColor = Color(0xFF636366),
                        title = stringResource(R.string.settings_runtime_rootfs_title),
                        subtitle = stringResource(R.string.settings_runtime_rootfs_sub),
                        onClick = onRootfsManagementClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Key,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.settings_env_vars),
                        subtitle = stringResource(R.string.settings_env_vars_subtitle),
                        onClick = onEnvVarsClick,
                        showDivider = false,
                    )
                }
                SettingsSection(
                    header = stringResource(R.string.settings_runtime_section_tools),
                    footer = stringResource(R.string.settings_runtime_footer),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Terminal,
                        iconColor = Color(0xFF1C1C1E),
                        title = stringResource(R.string.terminal_title),
                        subtitle = stringResource(R.string.settings_terminal_subtitle),
                        onClick = onTerminalClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Folder,
                        iconColor = Color(0xFF007AFF),
                        title = stringResource(R.string.settings_runtime_browse_files),
                        onClick = onFileBrowserClick,
                        showDivider = false,
                    )
                }
            }

            SettingsCategory.SYSTEM -> {
                ReadinessSection(items = rememberReadiness(), onOpenBackground = onOpenBackground)
                SettingsSection(
                    header = stringResource(R.string.settings_manage_header),
                    footer = stringResource(R.string.settings_system_footer),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Shield,
                        iconColor = Color(0xFFFF3B30),
                        title = stringResource(R.string.system_enhance_title),
                        subtitle = stringResource(R.string.system_enhance_row_subtitle),
                        onClick = onSystemEnhanceClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Terminal,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.shizuku_title),
                        subtitle = stringResource(R.string.settings_shizuku_sub),
                        onClick = onShizukuClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Dashboard,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.settings_vscreen_entry),
                        subtitle = stringResource(R.string.settings_vscreen_subtitle),
                        onClick = onVirtualScreenClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.FrontHand,
                        iconColor = Color(0xFF007AFF),
                        title = stringResource(R.string.settings_tool_permissions),
                        subtitle = stringResource(R.string.settings_tool_permissions_sub),
                        onClick = onToolPermissionsClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Lock,
                        iconColor = Color(0xFF34C759),
                        title = stringResource(R.string.settings_section_permissions),
                        subtitle = stringResource(R.string.settings_permissions_category_sub),
                        onClick = onPermissionsClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.BatteryFull,
                        iconColor = Color(0xFF34C759),
                        title = stringResource(R.string.bg_section_header),
                        subtitle = stringResource(R.string.bg_section_subtitle),
                        onClick = onBackgroundClick,
                        showDivider = false,
                    )
                }
            }

            SettingsCategory.DATA -> {
                SettingsSection {
                    SettingsRow(
                        icon = Icons.Outlined.Backup,
                        iconColor = Color(0xFF5856D6),
                        title = stringResource(R.string.settings_backup_restore),
                        subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                        onClick = onBackupClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Description,
                        iconColor = Color(0xFF8E8E93),
                        title = stringResource(R.string.settings_section_logs),
                        subtitle = stringResource(R.string.settings_logs_subtitle),
                        onClick = onLogsClick,
                        showDivider = false,
                    )
                }
                SettingsSection(header = stringResource(R.string.settings_data_section_app)) {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        iconColor = Color(0xFF007AFF),
                        title = stringResource(R.string.settings_about_minis),
                        subtitle = stringResource(R.string.settings_about_subtitle),
                        onClick = onAboutClick,
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Lock,
                        iconColor = Color(0xFF34C759),
                        title = stringResource(R.string.settings_privacy_policy),
                        onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Mail,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.settings_feedback),
                        onClick = { showFeedbackSheet = true },
                        showDivider = false,
                    )
                }
            }
        }
    }

    if (showFeedbackSheet) {
        MinisModalBottomSheet(
            onDismissRequest = { showFeedbackSheet = false },
            containerColor = if (LocalUiStyle.current == UiStyle.GLASS) {
                Color.Transparent
            } else {
                minisSheetColor()
            },
        ) {
            GlassSheetWindowBlur()
            Column(modifier = Modifier.fillMaxWidth().glassSheetSurface().padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
            }
        }
    }

    if (showSubagentLimits) {
        SubagentLimitsDialog(
            initialDepth = SubagentLimits.maxDepth(context),
            initialTimeoutMinutes = SubagentLimits.timeoutMs(context) / 60_000L,
            onSave = { depth, timeoutMinutes ->
                SubagentLimits.save(context, depth, timeoutMinutes)
                showSubagentLimits = false
            },
            onDismiss = { showSubagentLimits = false },
        )
    }
}
