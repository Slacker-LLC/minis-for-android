package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
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
import androidx.compose.material3.ModalBottomSheet
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
import com.openminis.app.R
import com.openminis.app.pet.PetControlActivity
import com.openminis.app.tools.SubagentLimits
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle

/**
 * [T-android-settings-hierarchy] One level-2 page: the settings that belong to a single category.
 *
 * The hub used to list every leaf screen at once - nine section headers and twenty-six rows, with
 * four unrelated rows (root and module, app permissions, the assistant role, background access)
 * sharing one "system" card. The hub is the category list now, a category owns its rows here, and
 * the leaf screen stays the level-3 page.
 */
enum class SettingsCategory(val key: String, val titleRes: Int, val subtitleRes: Int) {
    MODELS("models", R.string.settings_section_llm_providers, R.string.settings_category_models_sub),
    ASSISTANT("assistant", R.string.settings_section_agent_runtime, R.string.settings_category_agent_sub),
    APPEARANCE("appearance", R.string.settings_section_appearance, R.string.settings_category_appearance_sub),
    RUNTIME("runtime", R.string.settings_section_runtime_storage, R.string.settings_category_runtime_sub),
    SYSTEM("system", R.string.settings_section_system, R.string.settings_category_system_sub),
    ABOUT("about", R.string.settings_section_diagnostics, R.string.settings_category_about_sub),
    ;

    companion object {
        fun parse(raw: String?): SettingsCategory? = entries.firstOrNull { it.key == raw }
    }
}

/** The hub's leading icon for a category: one shape per subject, not per leaf screen. */
fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.MODELS -> Icons.Outlined.BarChart
    SettingsCategory.ASSISTANT -> Icons.Outlined.AutoAwesome
    SettingsCategory.APPEARANCE -> Icons.Outlined.Palette
    SettingsCategory.RUNTIME -> Icons.Outlined.Inventory2
    SettingsCategory.SYSTEM -> Icons.Outlined.Build
    SettingsCategory.ABOUT -> Icons.Outlined.Info
}

fun SettingsCategory.iconColor(): Color = when (this) {
    SettingsCategory.MODELS -> Color(0xFF007AFF)
    SettingsCategory.ASSISTANT -> Color(0xFFAF52DE)
    SettingsCategory.APPEARANCE -> Color(0xFF5856D6)
    SettingsCategory.RUNTIME -> Color(0xFF34C759)
    SettingsCategory.SYSTEM -> Color(0xFFFF9F0A)
    SettingsCategory.ABOUT -> Color(0xFF007AFF)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    onBack: () -> Unit,
    onProvidersClick: () -> Unit = {},
    onModelGroupsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onCharactersClick: () -> Unit = {},
    onSoulClick: () -> Unit = {},
    onSystemPromptClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    onMcpClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    onSharedFoldersClick: () -> Unit = {},
    onMountedFoldersClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onSystemEnhanceClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onBackgroundClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
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
            SettingsCategory.MODELS -> SettingsSection(
                header = stringResource(R.string.settings_section_llm_providers),
                footer = stringResource(R.string.settings_section_llm_providers_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_manage_providers),
                    subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                    onClick = onProvidersClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_model_groups),
                    subtitle = stringResource(R.string.settings_model_groups_subtitle),
                    onClick = onModelGroupsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.BarChart,
                    iconColor = Color(0xFF007AFF),
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

            SettingsCategory.ASSISTANT -> SettingsSection(
                header = stringResource(R.string.settings_section_agent_runtime),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Extension,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_skills),
                    subtitle = stringResource(R.string.settings_skills_subtitle),
                    onClick = onSkillsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    iconColor = Color(0xFFAF52DE),
                    title = stringResource(R.string.characters_title),
                    subtitle = stringResource(R.string.characters_subtitle),
                    onClick = onCharactersClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.AutoAwesome,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_soul),
                    subtitle = stringResource(R.string.settings_soul_subtitle),
                    onClick = onSoulClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_system_prompt),
                    subtitle = stringResource(R.string.settings_system_prompt_subtitle),
                    onClick = onSystemPromptClick,
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
                    iconColor = Color(0xFF30B0C7),
                    title = stringResource(R.string.settings_mcp),
                    subtitle = stringResource(R.string.settings_mcp_subtitle),
                    onClick = onMcpClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.AccountTree,
                    iconColor = Color(0xFFAF52DE),
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

            SettingsCategory.APPEARANCE -> SettingsSection(
                header = stringResource(R.string.settings_section_appearance),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    onClick = onAppearanceClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconColor = Color(0xFF4D6BFE),
                    title = stringResource(R.string.settings_pet),
                    subtitle = stringResource(R.string.settings_pet_subtitle),
                    onClick = { context.startActivity(android.content.Intent(context, PetControlActivity::class.java)) },
                    showDivider = false,
                )
            }

            SettingsCategory.RUNTIME -> SettingsSection(
                header = stringResource(R.string.settings_section_runtime_storage),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Terminal,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.terminal_title),
                    subtitle = stringResource(R.string.settings_terminal_subtitle),
                    onClick = onTerminalClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Key,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_env_vars),
                    subtitle = stringResource(R.string.settings_env_vars_subtitle),
                    onClick = onEnvVarsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Inventory2,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_storage),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    onClick = onRootfsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Folder,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_shared_folders),
                    subtitle = stringResource(R.string.settings_shared_folders_subtitle),
                    onClick = onSharedFoldersClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.FolderShared,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_mount_external_folders),
                    subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                    onClick = onMountedFoldersClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                    onClick = onBackupClick,
                    showDivider = false,
                )
            }

            SettingsCategory.SYSTEM -> SettingsSection(
                header = stringResource(R.string.settings_section_system),
                footer = stringResource(R.string.settings_system_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Build,
                    iconColor = Color(0xFFFF9F0A),
                    title = stringResource(R.string.system_enhance_title),
                    subtitle = stringResource(R.string.system_enhance_row_subtitle),
                    onClick = onSystemEnhanceClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Shield,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_permissions),
                    subtitle = stringResource(R.string.settings_permissions_category_sub),
                    onClick = onPermissionsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.BatteryFull,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.bg_section_header),
                    subtitle = stringResource(R.string.bg_section_subtitle),
                    onClick = onBackgroundClick,
                    showDivider = false,
                )
            }

            SettingsCategory.ABOUT -> SettingsSection(
                header = stringResource(R.string.settings_section_diagnostics),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    onClick = onLogsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_about_minis),
                    subtitle = stringResource(R.string.settings_about_subtitle),
                    onClick = onAboutClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.FrontHand,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_privacy_policy),
                    onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
                )
                SettingsRow(
                    icon = Icons.Outlined.BugReport,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_feedback),
                    onClick = { showFeedbackSheet = true },
                    showDivider = false,
                )
            }
        }
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFeedbackSheet = false },
            containerColor = if (LocalUiStyle.current == UiStyle.GLASS) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surface
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
