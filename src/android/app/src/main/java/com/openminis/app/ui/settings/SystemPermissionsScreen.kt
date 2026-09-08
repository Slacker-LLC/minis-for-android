package com.openminis.app.ui.settings

import android.widget.Toast
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.power.PowerOptimizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Surfaces OS-level permission states the user must grant through Android.
 * The structure follows upstream OpenMinis; the desktop-pet overlay grant is
 * the only fork-specific system permission kept here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var a11yEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var a11yDegraded by remember { mutableStateOf(false) }
    var a11yRevoked by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairFailed by remember { mutableStateOf(false) }
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val inSettings = isAccessibilityEnabled(context)
            val connected = MinisAccessibilityService.getInstance() != null
            a11yEnabled = inSettings || connected
            a11yDegraded = inSettings && !connected
            a11yRevoked = !inSettings && !connected &&
                AccessibilityRecoveryManager.hasEverBeenGranted(context)
            shizukuReady = ShizukuManager.isReady()
            a11yRestricted = !a11yEnabled && RestrictedSettingsManager.isRestricted(context)
            overlayGranted = Settings.canDrawOverlays(context)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_section_a11y),
                footer = stringResource(R.string.system_permissions_a11y_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Accessibility,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.system_permissions_a11y_row),
                    subtitle = if (a11yEnabled) {
                        stringResource(R.string.system_permissions_a11y_enabled)
                    } else {
                        stringResource(R.string.system_permissions_a11y_disabled)
                    },
                    onClick = { openAccessibilitySettings(context) },
                    showDivider = false,
                )
            }

            if (a11yRestricted) {
                SettingsSection(
                    header = stringResource(R.string.system_permissions_a11y_restricted_header),
                    footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
                ) {
                    if (shizukuReady) {
                        SettingsRow(
                            icon = Icons.Outlined.LockOpen,
                            iconColor = Color(0xFF34C759),
                            title = stringResource(R.string.system_permissions_a11y_restricted_shizuku),
                            subtitle = when {
                                unrestricting ->
                                    stringResource(R.string.system_permissions_a11y_restricted_working)
                                unrestrictFailed ->
                                    stringResource(R.string.system_permissions_a11y_restricted_failed)
                                else ->
                                    stringResource(R.string.system_permissions_a11y_restricted_shizuku_sub)
                            },
                            onClick = {
                                if (unrestricting) return@SettingsRow
                                unrestricting = true
                                unrestrictFailed = false
                                scope.launch {
                                    val ok = RestrictedSettingsManager.clearWithShizuku(context)
                                    unrestricting = false
                                    unrestrictFailed = !ok
                                }
                            },
                        )
                    }
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_restricted_manual),
                        subtitle = stringResource(R.string.system_permissions_a11y_restricted_manual_sub),
                        onClick = {
                            (context as? Activity)?.let {
                                PowerOptimizationManager.openAppDetailsSettings(it)
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            // Fork-specific system grant required by the floating desktop pet.
            SettingsSection(
                header = "悬浮窗",
                footer = "桌面宠物需要这个权限才能浮在其他应用上面。",
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Layers,
                    iconColor = if (overlayGranted) Color(0xFF34C759) else Color(0xFFFF2D55),
                    title = "显示在其他应用上层",
                    subtitle = if (overlayGranted) "已授权" else "未授权，点击前往系统设置",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        }
                    },
                    showDivider = false,
                )
            }

            if (a11yRevoked) {
                SettingsSection(
                    header = stringResource(R.string.a11y_repair_section_header),
                    footer = stringResource(
                        if (shizukuReady) R.string.a11y_repair_footer_shizuku
                        else R.string.a11y_repair_footer_manual,
                    ),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        iconColor = Color(0xFFFF3B30),
                        title = stringResource(
                            if (shizukuReady) R.string.a11y_repair_row_shizuku
                            else R.string.a11y_repair_row_manual,
                        ),
                        subtitle = when {
                            repairing -> stringResource(R.string.a11y_repair_in_progress)
                            repairFailed -> stringResource(R.string.a11y_repair_failed)
                            else -> stringResource(R.string.a11y_repair_row_sub)
                        },
                        onClick = {
                            if (!shizukuReady) {
                                openAccessibilitySettings(context)
                                return@SettingsRow
                            }
                            if (repairing) return@SettingsRow
                            repairing = true
                            repairFailed = false
                            scope.launch {
                                val ok = AccessibilityRecoveryManager.repairWithShizuku(context)
                                repairing = false
                                repairFailed = !ok
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            val activity = context as? Activity
            if (
                a11yDegraded &&
                activity != null &&
                PowerOptimizationManager.needsOemAutostartGuidance()
            ) {
                val vendor = PowerOptimizationManager.Vendor.current().displayName
                SettingsSection(
                    header = stringResource(R.string.system_permissions_a11y_oem_header),
                    footer = stringResource(R.string.system_permissions_a11y_oem_footer, vendor),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.RestartAlt,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_oem_autostart),
                        subtitle = stringResource(R.string.system_permissions_a11y_oem_autostart_sub),
                        onClick = {
                            if (!PowerOptimizationManager.openOemAutostartSettings(activity)) {
                                PowerOptimizationManager.openAppDetailsSettings(activity)
                            }
                        },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.BatteryAlert,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_oem_battery),
                        subtitle = stringResource(R.string.system_permissions_a11y_oem_battery_sub),
                        onClick = {
                            if (!PowerOptimizationManager.requestBatteryOptimizationExemption(activity)) {
                                PowerOptimizationManager.openAppDetailsSettings(activity)
                            }
                        },
                        showDivider = false,
                    )
                }
            }

            var correctionEnabled by remember {
                mutableStateOf(
                    com.openminis.app.speech.correction.VoiceCorrectionConsent.isEnabled(context),
                )
            }
            var showClearCorrectionConfirm by remember { mutableStateOf(false) }

            SettingsSection(
                header = stringResource(R.string.voice_correction_section),
                footer = stringResource(R.string.voice_correction_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.voice_correction_toggle),
                    checked = correctionEnabled,
                    onCheckedChange = { on ->
                        correctionEnabled = on
                        com.openminis.app.speech.correction.VoiceCorrectionConsent
                            .setEnabled(context, on)
                        com.openminis.app.speech.correction.VoiceCorrectionConsent
                            .setPrompted(context, true)
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

            if (showClearCorrectionConfirm) {
                AlertDialog(
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
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
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
