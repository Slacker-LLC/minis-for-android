package com.openminis.app.ui.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.tools.SubagentLimits
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.pet.PetControlActivity
import com.openminis.app.ui.components.openExternalUrl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** RoleManager was added in Android 10; older devices use voice-input Settings. */
@Suppress("NewApi")
internal fun Context.assistantRoleManagerOrNull(): RoleManager? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(RoleManager::class.java)
    } else {
        null
    }

/**
 * The assistant role's three calls, each behind the one suppression above so no screen has to
 * repeat it: with minSdk 26 every caller would otherwise need its own guard for an API the
 * nullable manager already carries the version check for.
 */
@Suppress("NewApi")
internal fun RoleManager.assistantRoleAvailable(): Boolean = isRoleAvailable(RoleManager.ROLE_ASSISTANT)

@Suppress("NewApi")
internal fun RoleManager.assistantRoleHeld(): Boolean = isRoleHeld(RoleManager.ROLE_ASSISTANT)

@Suppress("NewApi")
internal fun RoleManager.assistantRoleRequestIntent(): Intent = createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
   onSkillsClick: () -> Unit = {},
    // [T-eta-character-cards] Character library entry, next to Skills.
    onCharactersClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    // [T-system-prompt-modules] Editable agent system prompt modules. Listed
    // next to Soul so the two prompt-authoring surfaces sit together.
    onSystemPromptClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    // [T-system-enhance-android] Root + module status, the switches the module reads, and what
    // each of them unlocks - one page since the two used to say the same thing twice.
    onSystemEnhanceClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val roleManager = remember(context) { context.assistantRoleManagerOrNull() }
    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showSubagentLimits by remember { mutableStateOf(false) }
    var roleHeld by remember { mutableStateOf(false) }
    var roleAvailable by remember { mutableStateOf(false) }

    fun refreshAssistantRole() {
        val manager = roleManager
        if (manager == null) {
            roleAvailable = false
            roleHeld = false
            return
        }
        roleAvailable = runCatching {
            manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)
        roleHeld = roleAvailable && runCatching {
            manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrDefault(false)
    }

    // The role can also be changed from the OEM Settings app, so re-check it
    // whenever this screen returns to foreground instead of retaining a stale row.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, roleManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAssistantRole()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshAssistantRole()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val assistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAssistantRole()
        Toast.makeText(context, if (roleHeld) "已设为默认数字助手" else "未设置为默认助手", Toast.LENGTH_SHORT).show()
    }
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        // ── 模型与用量 ─────────────────────────────────────────────────────
        SettingsSection(
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
            var autoCompactState by remember { mutableStateOf(com.openminis.app.data.AutoCompactPrefs.isEnabled()) }
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

        // ── 助手能力 ───────────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.settings_section_agent_runtime)) {
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

        // ── 运行时与沙箱 ───────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.settings_section_runtime_storage)) {
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

        // ── 外观 ───────────────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.settings_section_appearance)) {
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
                onClick = { context.startActivity(Intent(context, PetControlActivity::class.java)) },
                showDivider = false,
            )
        }

        // ── 系统与权限 ─────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.settings_section_system),
            footer = stringResource(R.string.settings_system_enhance_footer),
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
                subtitle = stringResource(R.string.settings_permissions_subtitle),
                onClick = onPermissionsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.RecordVoiceOver,
                iconColor = Color(0xFF30B0C7),
                title = stringResource(R.string.settings_assistant_role),
                subtitle = when {
                    roleHeld -> stringResource(R.string.settings_assistant_role_held)
                    roleManager == null -> stringResource(R.string.settings_assistant_role_unavailable)
                    roleAvailable -> stringResource(R.string.settings_assistant_role_available)
                    else -> stringResource(R.string.settings_assistant_role_unavailable)
                },
                onClick = {
                    when {
                        roleHeld -> Toast.makeText(
                            context,
                            context.getString(R.string.settings_assistant_role_held_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                        roleManager != null && roleAvailable -> {
                            assistLauncher.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                            )
                        }
                        else -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_assistant_role_missing_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
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

        // ── 诊断与关于 ─────────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.settings_section_diagnostics)) {
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
                icon = Icons.Outlined.Feedback,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_feedback),
                onClick = { showFeedbackSheet = true },
                showDivider = false,
            )
        }
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFeedbackSheet = false },
            containerColor = if (LocalUiStyle.current == UiStyle.GLASS) Color.Transparent else MaterialTheme.colorScheme.surface,
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

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SubagentLimitsDialog(
    initialDepth: Int,
    initialTimeoutMinutes: Long,
    onSave: (Int, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var depth by remember { mutableStateOf(initialDepth.toString()) }
    var timeout by remember { mutableStateOf(initialTimeoutMinutes.toString()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = "子代理委派限制",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = depth,
                    onValueChange = { depth = it.filter(Char::isDigit).take(1) },
                    label = { Text("委派深度（1–5 层）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter(Char::isDigit).take(2) },
                    label = { Text("单任务超时（1–30 分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MinisTextButton(onClick = onDismiss) { Text("取消") }
                    MinisTextButton(
                        onClick = {
                            val d = depth.toIntOrNull() ?: SubagentLimits.DEFAULT_MAX_DEPTH
                            val t = timeout.toLongOrNull() ?: SubagentLimits.DEFAULT_TIMEOUT_MINUTES
                            onSave(
                                d.coerceIn(SubagentLimits.MAX_DEPTH_RANGE.first, SubagentLimits.MAX_DEPTH_RANGE.last),
                                t.coerceIn(SubagentLimits.TIMEOUT_MINUTES_RANGE.first, SubagentLimits.TIMEOUT_MINUTES_RANGE.last),
                            )
                        },
                    ) { Text("保存") }
                }
            }
        }
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/limuzi013/minis-for-android/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return "https://github.com/limuzi013/minis-for-android/issues/new" +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}
