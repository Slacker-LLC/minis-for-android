package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.ui.theme.ChatColors

/**
 * T50 settings screen — surfaces the two pieces of background-keep-alive
 * state the user can fix from the OS but the app cannot:
 *
 *   1. Battery-optimisation exemption (`isIgnoringBatteryOptimizations`).
 *   2. OEM-specific autostart permission (Xiaomi/Huawei/OPPO/Vivo/Samsung).
 *
 * Both rows just deep-link to the right system settings page; the app
 * has no privileged way to flip these directly. The state for (1) is
 * polled on resume — when the user comes back from the system settings
 * dialog, the row updates to "already allowed".
 *
 * Built from the shared settings components (SettingsSection / SettingsRow / SettingsSwitchRow), so it
 * looks like every other settings page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    // T180-bg-notif: pull the BackgroundSettingsRepository off the
    // Application instance — keeps AppNavigation parameters unchanged
    // (no plumbing churn through the nav graph). MinisApp.onCreate is
    // guaranteed to have run before any composable composes.
    val app = context.applicationContext as MinisApp
    val backgroundRepo = app.backgroundSettingsRepository
    val taskNotificationsEnabled by backgroundRepo.taskNotificationsEnabled.collectAsState()
    val backgroundOverlayEnabled by backgroundRepo.backgroundOverlayEnabled.collectAsState()
    // [T-android-dynamic-island] Live-Updates toggle + device capability.
    val dynamicIslandEnabled by backgroundRepo.dynamicIslandEnabled.collectAsState()

    var ignoringOptimizations by remember {
        mutableStateOf(PowerOptimizationManager.isIgnoringBatteryOptimizations(context))
    }
    var canDrawOverlays by remember {
        mutableStateOf(
            Settings.canDrawOverlays(context),
        )
    }
    // [T-android-dynamic-island] Re-probed on ON_RESUME (spec §3) so a
    // Live-Updates grant the user toggled in system settings is picked up
    // without an app restart.
    var dynamicIslandCapable by remember {
        mutableStateOf(
            com.openminis.app.service.DynamicIslandSupport.isDynamicIslandCapable(context),
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringOptimizations =
                    PowerOptimizationManager.isIgnoringBatteryOptimizations(context)
                canDrawOverlays =
                    Settings.canDrawOverlays(context)
                dynamicIslandCapable =
                    com.openminis.app.service.DynamicIslandSupport.isDynamicIslandCapable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val vendor = remember { PowerOptimizationManager.Vendor.current() }
    val needsOemGuidance = remember { PowerOptimizationManager.needsOemAutostartGuidance() }
    val autostartByApp = remember { com.openminis.app.permissions.RuntimePermissionGranter.autostartGrantedByApp(context) }

    // [T-android-settings-metrics] The shared scaffold, like every other settings page. The old
    // 16dp horizontal padding it carried is dropped with it: every section already insets its card
    // by that much, so this page's cards sat 16dp narrower than the same cards elsewhere.
    SettingsScaffold(
        title = stringResource(R.string.bg_section_header),
        onBack = onBack, backLabel = stringResource(R.string.settings_section_system),
    ) {
        // The same grouped sections and rows as every other settings page: one card per section, a divider
        // between rows, the explanation as the section's footer.
        SettingsSection(
            header = stringResource(R.string.settings_section_notifications),
            footer = if (!canDrawOverlays && backgroundOverlayEnabled) {
                stringResource(R.string.settings_bg_overlay_permission_needed)
            } else if (!dynamicIslandCapable) {
                stringResource(R.string.settings_dynamic_island_unsupported)
            } else {
                null
            },
        ) {
            // T180-bg-notif: Task Notifications toggle. Mirrors iOS EnhancedBackgroundSettingsView's first
            // section (ships ON to match iOS default - see BackgroundSettingsRepository.DEFAULT_TASK_NOTIFICATIONS).
            SettingsSwitchRow(
                icon = Icons.Outlined.NotificationsActive,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_task_notifications),
                checked = taskNotificationsEnabled,
                onCheckedChange = { backgroundRepo.setTaskNotificationsEnabled(it) },
            )
            // T-bg-overlay phase 2: floating tool-status overlay. Switching it on without SYSTEM_ALERT_WINDOW
            // sends the user to "Display over other apps"; canDrawOverlays is re-read on ON_RESUME.
            SettingsSwitchRow(
                icon = Icons.Outlined.Layers,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_bg_overlay),
                // The switch is the persisted USER INTENT; the permission is a separate gate, explained in
                // the footer and enforced by the service.
                checked = backgroundOverlayEnabled,
                onCheckedChange = { wanted ->
                    if (wanted && !canDrawOverlays) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            try { context.startActivity(intent) } catch (_: Throwable) {}
                        }
                        // Keep the intent so the overlay turns on as soon as the grant is in place.
                        backgroundRepo.setBackgroundOverlayEnabled(true)
                    } else {
                        backgroundRepo.setBackgroundOverlayEnabled(wanted)
                    }
                },
            )
            // [T-android-dynamic-island] Live Updates / "dynamic island". Only usable on devices with the
            // per-app Live-Updates grant (Android 16+); when on it REPLACES the floating overlay (the
            // exclusion lives in AgentForegroundService.applyOverlayState).
            SettingsSwitchRow(
                icon = Icons.Outlined.Bolt,
                iconColor = ChatColors.ok,
                title = stringResource(R.string.settings_dynamic_island),
                checked = dynamicIslandEnabled && dynamicIslandCapable,
                enabled = dynamicIslandCapable,
                onCheckedChange = { backgroundRepo.setDynamicIslandEnabled(it) },
                showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.battery_opt_section_title)) {
            SettingsRow(
                icon = Icons.Outlined.BatteryFull,
                iconColor = if (ignoringOptimizations) ChatColors.ok else ChatColors.warn,
                title = stringResource(R.string.battery_opt_row_title),
                subtitle = if (ignoringOptimizations) {
                    stringResource(R.string.battery_opt_already_exempt)
                } else {
                    stringResource(R.string.battery_opt_request_subtitle)
                },
                onClick = {
                    if (!ignoringOptimizations && activity != null) {
                        PowerOptimizationManager.requestBatteryOptimizationExemption(activity)
                    }
                },
                showDivider = false,
            )
        }

        if (needsOemGuidance) {
            SettingsSection(header = stringResource(R.string.rom_autostart_section_title)) {
                SettingsRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    iconColor = if (autostartByApp) ChatColors.ok else ChatColors.warn,
                    title = stringResource(R.string.rom_autostart_row_title),
                    subtitle = if (autostartByApp) {
                        stringResource(R.string.battery_opt_already_exempt)
                    } else {
                        stringResource(R.string.rom_autostart_row_subtitle, vendor.displayName)
                    },
                    onClick = {
                        if (activity != null) {
                            val ok = PowerOptimizationManager.openOemAutostartSettings(activity)
                            if (!ok) PowerOptimizationManager.openAppDetailsSettings(activity)
                        }
                    },
                    showDivider = false,
                )
            }
        }
    }
}
