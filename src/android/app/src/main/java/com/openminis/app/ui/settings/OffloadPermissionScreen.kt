package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.guest.RootCliOffloadHandler
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisAlertDialog

@Composable
fun OffloadPermissionScreen(
    onBack: () -> Unit,
    onOpenRoot: () -> Unit = {},
    onOpenSystemPermissions: () -> Unit = {},
) {
    val grouped = OffloadPermissionManager.toolRegistry
        .filter { it.showInSettings }
        .groupBy { it.category }
    val autoCategories = grouped.entries
        .filter { it.key != OffloadPermissionManager.PermissionCategory.INTEGRATIONS }

    var showResetConfirm by remember { mutableStateOf(false) }
    // Bumped by Reset All so every row re-reads the permission it first showed.
    var resetEpoch by remember { mutableIntStateOf(0) }
    val configEnabled by com.openminis.app.config.MinisConfigPermissionStore.enabled.collectAsState()
    val context = LocalContext.current

    var a11yEnabled by remember { mutableStateOf(isA11yServiceEnabled(context)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            a11yEnabled = isA11yServiceEnabled(context) || MinisAccessibilityService.getInstance() != null
            delay(1000)
        }
    }
    val rootState by RootAccess.state.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.perm_title),
        onBack = onBack, backLabel = stringResource(R.string.settings_section_system),
        actions = {
            MinisTextButton(onClick = { showResetConfirm = true }) {
                Text(stringResource(R.string.perm_reset_all))
            }
        },
    ) {
        val autonomy by com.openminis.app.config.AutonomyStore.mode.collectAsState()
        SettingsSection(header = stringResource(R.string.perm_section_autonomy)) {
            AutonomyOption(
                mode = com.openminis.app.config.AutonomyMode.ASK,
                title = R.string.perm_autonomy_ask, subtitle = R.string.perm_autonomy_ask_sub, current = autonomy,
            )
            AutonomyOption(
                mode = com.openminis.app.config.AutonomyMode.SMART,
                title = R.string.perm_autonomy_smart, subtitle = R.string.perm_autonomy_smart_sub, current = autonomy,
            )
            AutonomyOption(
                mode = com.openminis.app.config.AutonomyMode.FULL,
                title = R.string.perm_autonomy_full, subtitle = R.string.perm_autonomy_full_sub, current = autonomy,
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.perm_section_config_tool),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.perm_allow_minis_config),
                checked = configEnabled,
                onCheckedChange = {
                    com.openminis.app.config.MinisConfigPermissionStore.setEnabled(it)
                },
                showDivider = false,
            )
        }

        autoCategories.forEach { (category, tools) ->
            SettingsSection(header = stringResource(categoryHeaderRes(category))) {
                tools.forEachIndexed { idx, tool ->
                    PermissionRow(
                        tool = tool,
                        showDivider = idx < tools.size - 1,
                        resetEpoch = resetEpoch,
                    )
                }
            }
        }

        IntegrationSection(
            iconVector = Icons.Outlined.Accessibility,
            iconTint = ChatColors.ok,
            sectionHeaderRes = R.string.perm_section_a11y,
            toolName = "a11y_cli",
            descriptionRes = R.string.perm_a11y_cli_description,
            systemReady = a11yEnabled,
            systemStatusTitleRes =
                if (a11yEnabled) R.string.perm_a11y_system_ready
                else R.string.perm_a11y_system_disabled,
            systemActionTitleRes = R.string.perm_a11y_open_settings,
            onSystemAction = { openAccessibilitySettings(context) },
            resetEpoch = resetEpoch,
        )

        IntegrationSection(
            iconVector = Icons.Outlined.Shield,
            iconTint = Color(0xFFAF52DE),
            sectionHeaderRes = R.string.perm_section_privileged_backend,
            toolName = RootCliOffloadHandler.TOOL_NAME,
            descriptionRes = R.string.perm_privileged_cli_description,
            systemReady = rootState.isGranted,
            systemStatusTitleRes = when {
                rootState.isGranted -> R.string.system_enhance_root_granted
                rootState.isChecking -> R.string.system_enhance_root_checking
                else -> R.string.perm_root_not_granted
            },
            systemActionTitleRes = R.string.system_enhance_root_action,
            onSystemAction = onOpenRoot,
            onStatusRowClick = onOpenRoot,
            resetEpoch = resetEpoch,
        )

        // The virtual screen's tools are opt-in like the other integrations, so they are set here too.
        val vscreenTools = grouped[OffloadPermissionManager.PermissionCategory.INTEGRATIONS]
            .orEmpty()
            .filter { it.toolName.startsWith("android.vscreen.") }
        if (vscreenTools.isNotEmpty()) {
            // One master switch; the five tools are its sub-items and only show while it is on.
            var vscreenOn by remember(resetEpoch) {
                mutableStateOf(vscreenTools.any { OffloadPermissionManager.isAllowed(it.toolName) })
            }
            SettingsSection(header = stringResource(R.string.settings_vscreen_entry)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.perm_vscreen_master_title),
                    subtitle = stringResource(R.string.perm_vscreen_master_sub),
                    checked = vscreenOn,
                    onCheckedChange = { on ->
                        vscreenOn = on
                        vscreenTools.forEach { tool ->
                            val current = OffloadPermissionManager.getLevel(tool.toolName)
                            if (on && current == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) {
                                OffloadPermissionManager.setLevel(tool.toolName, OffloadPermissionManager.PermissionLevel.BYPASS)
                            } else if (!on) {
                                OffloadPermissionManager.setLevel(tool.toolName, OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
                            }
                        }
                    },
                    showDivider = vscreenOn,
                )
                if (vscreenOn) {
                    vscreenTools.forEachIndexed { idx, tool ->
                        PermissionRow(tool = tool, showDivider = idx < vscreenTools.size - 1, resetEpoch = resetEpoch)
                    }
                }
            }
        }

        // Fork-only system grants remain reachable without creating a second
        // Agent permission model. Root authority is intentionally absent here.
        SettingsSection(
            header = stringResource(R.string.perm_more_privileges),
        ) {
            SettingsRow(
                icon = Icons.Outlined.Layers,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.offload_privilege_details),
                subtitle = stringResource(R.string.offload_privilege_details_footer),
                onClick = onOpenSystemPermissions,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showResetConfirm) {
        MinisAlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.perm_reset_confirm_title)) },
            text = { Text(stringResource(R.string.perm_reset_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    OffloadPermissionManager.resetAll()
                    resetEpoch++
                    com.openminis.app.config.MinisConfigPermissionStore.setEnabled(true)
                    AppLogger.info("PermissionsScreen", "user confirmed Reset All — all tool permissions cleared, minis-config switch reset to default")
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.perm_reset_confirm))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun IntegrationSection(
    iconVector: ImageVector,
    iconTint: Color,
    sectionHeaderRes: Int,
    toolName: String,
    descriptionRes: Int,
    systemReady: Boolean,
    systemStatusTitleRes: Int,
    systemActionTitleRes: Int,
    onSystemAction: () -> Unit,
    onStatusRowClick: (() -> Unit)? = null,
    resetEpoch: Int = 0,
) {
    SettingsSection(
        header = stringResource(sectionHeaderRes),
    ) {
        SettingsRow(
            icon = iconVector,
            iconColor = iconTint,
            title = stringResource(toolTitleRes(toolName)),
            subtitle = stringResource(descriptionRes),
            showChevron = false,
        )

        AgentPolicyRow(toolName = toolName, showDivider = true, resetEpoch = resetEpoch)

        SettingsRow(
            title = stringResource(R.string.perm_system_authorization),
            onClick = onStatusRowClick,
            showChevron = onStatusRowClick != null,
            showDivider = onStatusRowClick == null && !systemReady,
            trailing = {
                Text(
                    text = stringResource(systemStatusTitleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (systemReady) ChatColors.ok
                    else MaterialTheme.colorScheme.error,
                )
            },
        )

        if (onStatusRowClick == null && !systemReady) {
            SettingsRow(
                title = stringResource(systemActionTitleRes),
                onClick = onSystemAction,
                showDivider = false,
            )
        }
    }
}

/** The Agent's access to one integration: a plain on/off switch. */
@Composable
private fun AgentPolicyRow(
    toolName: String,
    showDivider: Boolean,
    resetEpoch: Int,
) {
    var allowed by remember(resetEpoch) { mutableStateOf(OffloadPermissionManager.isAllowed(toolName)) }
    SettingsSwitchRow(
        title = stringResource(R.string.perm_agent_policy),
        checked = allowed,
        onCheckedChange = {
            allowed = it
            OffloadPermissionManager.setAllowed(toolName, it)
        },
        showDivider = showDivider,
    )
}

/** One tool the Agent may use: on = allowed, off = denied. */
@Composable
private fun PermissionRow(
    tool: OffloadPermissionManager.ToolPermissionInfo,
    showDivider: Boolean,
    resetEpoch: Int,
) {
    var allowed by remember(resetEpoch) { mutableStateOf(OffloadPermissionManager.isAllowed(tool.toolName)) }
    SettingsSwitchRow(
        title = toolTitle(tool),
        subtitle = tool.toolName,
        checked = allowed,
        onCheckedChange = {
            allowed = it
            OffloadPermissionManager.setAllowed(tool.toolName, it)
        },
        showDivider = showDivider,
    )
}

private fun categoryHeaderRes(category: OffloadPermissionManager.PermissionCategory): Int = when (category) {
    OffloadPermissionManager.PermissionCategory.PRIVACY -> R.string.perm_section_privacy
    OffloadPermissionManager.PermissionCategory.MEDIA -> R.string.perm_section_media
    OffloadPermissionManager.PermissionCategory.SYSTEM -> R.string.perm_section_system
    OffloadPermissionManager.PermissionCategory.INTEGRATIONS -> R.string.perm_section_a11y
}

@Composable
private fun toolTitle(tool: OffloadPermissionManager.ToolPermissionInfo): String {
    val res = toolTitleRes(tool.toolName)
    if (res == 0) return tool.displayName
    return stringResource(res)
}

private fun toolTitleRes(toolName: String): Int = when (toolName) {
    "calendar" -> R.string.perm_tool_calendar
    "location" -> R.string.perm_tool_location
    "clipboard" -> R.string.perm_tool_clipboard
    "contacts" -> R.string.perm_tool_contacts
    "photos" -> R.string.perm_tool_photos
    "a11y_cli" -> R.string.perm_tool_a11y_cli
    RootCliOffloadHandler.TOOL_NAME -> R.string.perm_tool_root_cli
    "apt_cli" -> R.string.perm_tool_apt_cli
    "android.vscreen.open" -> R.string.perm_tool_vscreen_open
    "android.vscreen.launch" -> R.string.perm_tool_vscreen_launch
    "android.vscreen.close" -> R.string.perm_tool_vscreen_close
    "android.vscreen.status" -> R.string.perm_tool_vscreen_status
    "android.vscreen.ui" -> R.string.perm_tool_vscreen_ui
    else -> 0
}

private fun isA11yServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Throwable) {}
    }
}

private fun openAppDetailsSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Throwable) {}
    }
}

/** One row of the autonomy choice; the selected one carries a check. */
@Composable
private fun AutonomyOption(
    mode: com.openminis.app.config.AutonomyMode,
    title: Int,
    subtitle: Int,
    current: com.openminis.app.config.AutonomyMode,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = stringResource(title),
        subtitle = stringResource(subtitle),
        onClick = { com.openminis.app.config.AutonomyStore.setMode(mode) },
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            if (mode == current) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
