package com.openminis.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * The Root page under System & permissions: whether su works for this app, what Root is used for,
 * and the `android-root-cli` command surface the agent gets from it. Root is the app's only
 * privileged path; this page replaced the Root & Shizuku page in 2026-10.
 */
@Composable
fun RootAccessScreen(onBack: () -> Unit) {
    SettingsScaffold(
        title = stringResource(R.string.system_enhance_root),
        onBack = onBack, backLabel = stringResource(R.string.settings_section_system),
    ) {
        RootStatusSection()

        SettingsSection(header = stringResource(R.string.system_enhance_root_section)) {
            RootUses.forEachIndexed { index, use ->
                SettingsRow(
                    title = stringResource(use.titleRes),
                    icon = use.icon,
                    showChevron = false,
                    showDivider = index != RootUses.lastIndex,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.perm_tool_root_cli)) {
            RootCliCapabilities.forEachIndexed { index, cap ->
                SettingsRow(
                    // Subcommand name is a literal CLI token — not localized; only the description is.
                    title = cap.name,
                    subtitle = stringResource(cap.descriptionRes),
                    icon = cap.icon,
                    showChevron = false,
                    showDivider = index != RootCliCapabilities.lastIndex,
                    minHeight = 72.dp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private data class RootUse(@StringRes val titleRes: Int, val icon: ImageVector)

private val RootUses: List<RootUse> = listOf(
    RootUse(R.string.system_enhance_root_feature_runtime, Icons.Outlined.Terminal),
    RootUse(R.string.system_enhance_root_feature_device, Icons.Outlined.TouchApp),
    RootUse(R.string.system_enhance_root_feature_data, Icons.Outlined.Lock),
    RootUse(R.string.settings_vscreen_entry, Icons.Outlined.Dashboard),
)

/**
 * One `android-root-cli` subcommand group. The list is static: the CLI's command surface is fixed
 * at compile time, so nothing is probed at runtime. The CLI token in [name] is the source of truth
 * and is never localized.
 */
private data class RootCliCapability(
    val name: String,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
)

private val RootCliCapabilities: List<RootCliCapability> = listOf(
    RootCliCapability("package", R.string.root_cli_cap_package_desc, Icons.Default.Apps),
    RootCliCapability("permission", R.string.root_cli_cap_permission_desc, Icons.Default.Lock),
    RootCliCapability("activity", R.string.root_cli_cap_activity_desc, Icons.AutoMirrored.Filled.Launch),
    RootCliCapability("display", R.string.root_cli_cap_display_desc, Icons.Default.Fullscreen),
    RootCliCapability("settings", R.string.root_cli_cap_settings_desc, Icons.Default.Settings),
    RootCliCapability("user", R.string.root_cli_cap_user_desc, Icons.Default.Person),
    RootCliCapability("network", R.string.root_cli_cap_network_desc, Icons.Default.Wifi),
    RootCliCapability("input", R.string.root_cli_cap_input_desc, Icons.Default.TouchApp),
    RootCliCapability("notification", R.string.root_cli_cap_notification_desc, Icons.Default.Notifications),
    RootCliCapability("file", R.string.root_cli_cap_file_desc, Icons.Default.Folder),
    RootCliCapability("device", R.string.root_cli_cap_device_desc, Icons.Default.PhoneAndroid),
    RootCliCapability("service", R.string.root_cli_cap_service_desc, Icons.Default.Dns),
)
