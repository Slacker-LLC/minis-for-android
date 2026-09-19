package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.runtime.ubuntu.RootAccessState
import com.openminis.app.runtime.ubuntu.RootAccessStatus
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleSettingsStore
import com.openminis.app.xposed.PowerAssistantTarget
import com.openminis.app.xposed.system.AccessibilityProtectionClient

/**
 * [T-system-enhance-android] One page for everything the device has to grant before the
 * takeovers work: root, the LSPosed module, and the switches the module reads.
 *
 * Shaped after Eta's `SystemEnhanceScreen` (Mangi-11/Eta @ c15de97), which pairs a root card
 * with a framework-connection card and the explanation sections. The module's switches used to
 * live on a second page ("module settings") while this one only counted them, which meant the
 * same subject - the module and what it does - was spread over two screens with two different
 * row styles. They are one page now: status first, then the switches themselves, then what root
 * and the module each unlock.
 *
 * The framework connection is readable here only because the module app now writes its switches
 * through the libxposed service: when that service is absent there is nothing to write, and the
 * switches say so instead of appearing to work. What is still NOT shown is the per-process hook
 * ledger - that lives in other processes' logs, and a page that guessed it would be inventing
 * state.
 */
@Composable
fun SystemEnhanceScreen(
    onBack: () -> Unit,
    onOpenSystemPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val rootState by RootAccess.state.collectAsState()
    var protection by remember { mutableStateOf(AccessibilityProtectionClient.isEnabled(context)) }
    var hooksOn by remember { mutableIntStateOf(enabledHookSwitchCount(context)) }

    // [T-eta-xposed-groups] The switches below are committed through the framework's service;
    // without a framework there is nothing to write, so the rows are disabled and the footer
    // says why. The local mirror still supplies the last accepted value for display.
    val connected by ModuleSettingsStore.connected.collectAsState()
    var gestureBar by remember(connected) {
        mutableStateOf(ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH))
    }
    var doubleFinger by remember(connected) {
        mutableStateOf(ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH))
    }
    var hotword by remember(connected) {
        mutableStateOf(ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.HOTWORD_SELF_HEAL))
    }
    var assistantTarget by remember(connected) {
        mutableStateOf(ModuleSettingsStore.assistantTarget(context))
    }

    fun writeFailed() {
        Toast.makeText(context, context.getString(R.string.module_settings_write_failed), Toast.LENGTH_SHORT).show()
    }

    // The assistant role can also change from the OEM settings app, so re-read it whenever this
    // screen comes back to the foreground instead of trusting a value captured at composition.
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

    SettingsScaffold(
        title = stringResource(R.string.system_enhance_title),
        onBack = onBack,
    ) {
        SettingsSection(header = stringResource(R.string.system_enhance_section_status)) {
            SettingsRow(
                icon = Icons.Outlined.Key,
                iconColor = Color(0xFFFF9F0A),
                title = stringResource(R.string.system_enhance_root),
                subtitle = rootStateSubtitle(rootState),
                trailing = {
                    MinisTextButton(
                        onClick = { RootAccess.request(context) },
                        enabled = !rootState.isChecking,
                    ) {
                        Text(
                            if (rootState.isChecking) {
                                stringResource(R.string.system_enhance_root_checking)
                            } else {
                                stringResource(R.string.system_enhance_root_action)
                            },
                        )
                    }
                },
            )
            SettingsRow(
                icon = Icons.Outlined.Accessibility,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.system_enhance_protection),
                subtitle = stringResource(
                    if (protection) {
                        R.string.system_enhance_protection_on
                    } else {
                        R.string.system_enhance_protection_off
                    },
                ),
                onClick = onOpenSystemPermissions,
            )
            SettingsRow(
                icon = Icons.Outlined.Extension,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.system_enhance_hooks),
                subtitle = stringResource(R.string.system_enhance_hooks_subtitle),
                trailing = {
                    Text(
                        text = "$hooksOn/" + HOOK_SWITCH_KEYS.size,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showChevron = false,
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.module_settings_section_switches),
            footer = stringResource(
                if (connected) R.string.module_settings_footer else R.string.module_settings_not_connected,
            ),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Search,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.module_settings_gesture_bar),
                subtitle = stringResource(R.string.module_settings_gesture_bar_sub),
                checked = gestureBar,
                enabled = connected,
                onCheckedChange = { enabled ->
                    if (ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                            enabled,
                        )
                    ) {
                        gestureBar = enabled
                        hooksOn = enabledHookSwitchCount(context)
                    } else {
                        writeFailed()
                    }
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.TouchApp,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.module_settings_double_finger),
                subtitle = stringResource(R.string.module_settings_double_finger_sub),
                checked = doubleFinger,
                enabled = connected,
                onCheckedChange = { enabled ->
                    if (ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                            enabled,
                        )
                    ) {
                        doubleFinger = enabled
                        hooksOn = enabledHookSwitchCount(context)
                    } else {
                        writeFailed()
                    }
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.RecordVoiceOver,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.module_settings_hotword),
                subtitle = stringResource(R.string.module_settings_hotword_sub),
                checked = hotword,
                enabled = connected,
                onCheckedChange = { enabled ->
                    if (ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.HOTWORD_SELF_HEAL,
                            enabled,
                        )
                    ) {
                        hotword = enabled
                        hooksOn = enabledHookSwitchCount(context)
                    } else {
                        writeFailed()
                    }
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.module_settings_section_assistant),
            footer = stringResource(R.string.module_settings_assistant_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.module_settings_assistant_role),
                subtitle = when {
                    roleHeld -> stringResource(R.string.module_settings_assistant_role_held)
                    roleAvailable -> stringResource(R.string.module_settings_assistant_role_needed)
                    else -> stringResource(R.string.module_settings_assistant_role_unavailable)
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
                    val manager = roleManager
                    when {
                        roleHeld -> Toast.makeText(
                            context,
                            context.getString(R.string.module_settings_assistant_role_held),
                            Toast.LENGTH_SHORT,
                        ).show()
                        manager != null && roleAvailable -> roleLauncher.launch(manager.assistantRoleRequestIntent())
                        else -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.module_settings_assistant_role_unavailable),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
            PowerAssistantTarget.entries.forEachIndexed { index, target ->
                SettingsRow(
                    title = stringResource(target.labelRes()),
                    trailing = {
                        if (target == assistantTarget) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = if (!connected) {
                        null
                    } else {
                        {
                            if (ModuleSettingsStore.setAssistantTarget(context, target)) {
                                assistantTarget = target
                            } else {
                                writeFailed()
                            }
                        }
                    },
                    showDivider = index != PowerAssistantTarget.entries.lastIndex,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.system_enhance_root_section),
            footer = stringResource(R.string.system_enhance_root_footer),
        ) {
            CapabilityRow(R.string.system_enhance_root_feature_runtime)
            CapabilityRow(R.string.system_enhance_root_feature_device)
            CapabilityRow(R.string.system_enhance_root_feature_data, showDivider = false)
        }

        SettingsSection(
            header = stringResource(R.string.system_enhance_module_section),
            footer = stringResource(R.string.system_enhance_module_footer),
        ) {
            CapabilityRow(R.string.system_enhance_module_feature_entries)
            CapabilityRow(R.string.system_enhance_module_feature_google)
            CapabilityRow(R.string.system_enhance_module_feature_accessibility, showDivider = false)
        }
    }
}

@Composable
private fun CapabilityRow(titleRes: Int, showDivider: Boolean = true) {
    SettingsRow(
        title = stringResource(titleRes),
        showChevron = false,
        showDivider = showDivider,
    )
}

private fun PowerAssistantTarget.labelRes(): Int = when (this) {
    PowerAssistantTarget.OEM -> R.string.module_settings_target_oem
    PowerAssistantTarget.MINIS -> R.string.module_settings_target_minis
    PowerAssistantTarget.GEMINI -> R.string.module_settings_target_gemini
}

/** The switch keys the module owns; the status row above counts them. */
private val HOOK_SWITCH_KEYS = listOf(
    ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
    ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
    ModulePrefs.Keys.HOTWORD_SELF_HEAL,
)

private fun enabledHookSwitchCount(context: android.content.Context): Int =
    HOOK_SWITCH_KEYS.count { ModuleSettingsStore.isEnabled(context, it) }

/**
 * [T-root-manager-detection-android] "su is absent" and "su is hidden from this app" are
 * different dead ends: the real device that produced this distinction runs KernelSU Next,
 * which keeps su invisible to apps that are not on its allowlist while the manager itself
 * stays listed. Only the second case can be fixed by the user, so it says how.
 */
@Composable
private fun rootStateSubtitle(state: RootAccessState): String = when {
    state.isChecking -> stringResource(R.string.system_enhance_root_checking)
    state.status == RootAccessStatus.GRANTED -> stringResource(R.string.system_enhance_root_granted)
    state.status == RootAccessStatus.NOT_GRANTED ->
        stringResource(R.string.system_enhance_root_not_confirmed)
    state.status == RootAccessStatus.DENIED -> stringResource(R.string.system_enhance_root_denied)
    state.status == RootAccessStatus.TIMED_OUT -> stringResource(R.string.system_enhance_root_timeout)
    state.status == RootAccessStatus.UNAVAILABLE && state.rootManager != null ->
        stringResource(R.string.system_enhance_root_hidden, state.rootManager)
    state.status == RootAccessStatus.UNAVAILABLE -> stringResource(R.string.system_enhance_root_absent)
    else -> stringResource(R.string.system_enhance_root_unknown)
}
