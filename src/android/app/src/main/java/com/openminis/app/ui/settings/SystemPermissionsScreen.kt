package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.xposed.system.AccessibilityProtectionClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var a11yEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    // [T-android-settings-hierarchy] The assistant role is a grant like the others on this page,
    // and the module's power-key takeover depends on it, so it lives here rather than as a
    // stand-alone row on the hub. The role can change from the OEM settings app too, so it is
    // re-read whenever this screen returns to the foreground.
    val roleManager = remember(context) { context.assistantRoleManagerOrNull() }
    var roleHeld by remember { mutableStateOf(false) }
    var roleAvailable by remember { mutableStateOf(false) }

    fun refreshAssistantRole() {
        val manager = roleManager
        if (manager == null) {
            roleAvailable = false
            roleHeld = false
            return
        }
        roleAvailable = runCatching { manager.assistantRoleAvailable() }.getOrDefault(false)
        roleHeld = roleAvailable && runCatching { manager.assistantRoleHeld() }.getOrDefault(false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, roleManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAssistantRole()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshAssistantRole()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshAssistantRole() }
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
                header = stringResource(R.string.settings_assistant_role),
                footer = stringResource(R.string.settings_assistant_role_footer),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    iconColor = Color(0xFF30B0C7),
                    title = stringResource(R.string.settings_assistant_role),
                    subtitle = when {
                        roleHeld -> stringResource(R.string.settings_assistant_role_held)
                        roleAvailable -> stringResource(R.string.settings_assistant_role_available)
                        else -> stringResource(R.string.settings_assistant_role_unavailable)
                    },
                    trailing = {
                        if (roleHeld) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        when {
                            roleHeld -> Toast.makeText(
                                context,
                                context.getString(R.string.settings_assistant_role_held_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                            roleManager != null && roleAvailable -> roleLauncher.launch(roleManager.assistantRoleRequestIntent())
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
                    showDivider = false,
                )
            }

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

            // [T-eta-xposed-groups] The module's accessibility protection: it keeps this app's
            // service enabled from inside system_server, so it needs neither Shizuku nor the user
            // coming back to this screen. Off until asked, and the row says when the module is not
            // there instead of pretending the switch did something.
            var moduleProtectionEnabled by remember {
                mutableStateOf(AccessibilityProtectionClient.isEnabled(context))
            }
            var moduleProtectionUnavailable by remember { mutableStateOf(false) }

            SettingsSection(
                header = stringResource(R.string.system_permissions_module_a11y_header),
                footer = stringResource(R.string.system_permissions_module_a11y_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.HealthAndSafety,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.system_permissions_module_a11y_toggle),
                    subtitle = if (moduleProtectionUnavailable) {
                        stringResource(R.string.system_permissions_module_a11y_unavailable)
                    } else {
                        null
                    },
                    checked = moduleProtectionEnabled,
                    onCheckedChange = { on ->
                        AccessibilityProtectionClient.setEnabled(context, on) { result ->
                            moduleProtectionEnabled = result.enabled
                            moduleProtectionUnavailable =
                                result.status != AccessibilityProtectionClient.ControlStatus.APPLIED
                        }
                    },
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

            // Fork-only system grant required by the floating desktop pet.
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

            // [T-android-settings-hierarchy] Battery-optimisation and OEM-autostart guidance used
            // to be repeated here and on "background & notifications" with different wording for
            // the same two system screens. They live there now, one tap away in the same category;
            // this page keeps only the grants that belong to accessibility itself.

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

private fun isAccessibilityEnabled(context: Context): Boolean =
    MinisAccessibilityService.isEnabled(context)

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
