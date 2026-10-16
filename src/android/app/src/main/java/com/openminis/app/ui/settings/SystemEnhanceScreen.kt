package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.runtime.ubuntu.RootAccessState
import com.openminis.app.runtime.ubuntu.RootAccessStatus
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleSettingsStore
import com.openminis.app.xposed.PowerAssistantTarget
import com.openminis.app.xposed.system.AccessibilityProtectionClient

/**
 * [T-system-enhance-android] The LSPosed module page: the switches the module reads and the
 * target of the power-button assistant. Root has a page of its own ([RootAccessScreen]).
 *
 * Shaped after Eta's `SystemEnhanceScreen` (Mangi-11/Eta @ c15de97).
 *
 * The framework connection is readable here only because the module app now writes its switches
 * through the libxposed service: when that service is absent there is nothing to write, and the
 * switches say so instead of appearing to work. What is still NOT shown is the per-process hook
 * ledger - that lives in other processes' logs, and a page that guessed it would be inventing
 * state.
 */
@Composable
fun SystemEnhanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

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
        com.openminis.app.ui.components.MinisToast.show(context, context.getString(R.string.module_settings_write_failed))
    }

    SettingsScaffold(
        title = stringResource(R.string.system_enhance_title),
        onBack = onBack, backLabel = stringResource(R.string.settings_section_system),
    ) {
        if (!connected) {
            // The framework hands its service to an app only when the app's process starts, so a module
            // enabled while Minis was already running stays "not connected" until the process restarts.
            SettingsSection(footer = stringResource(R.string.module_settings_not_connected)) {
                SettingsRow(
                    icon = Icons.Outlined.Refresh,
                    iconColor = ChatColors.warn,
                    title = stringResource(R.string.module_settings_restart_title),
                    subtitle = stringResource(R.string.module_settings_restart_sub),
                    onClick = { com.openminis.app.AppRestart.restart(context) },
                    showDivider = false,
                )
            }
        }
        SettingsSection(
            header = stringResource(R.string.module_settings_section_switches),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Search,
                iconColor = ChatColors.ok,
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
                    } else {
                        writeFailed()
                    }
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.TouchApp,
                iconColor = ChatColors.ok,
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
                    } else {
                        writeFailed()
                    }
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.RecordVoiceOver,
                iconColor = ChatColors.ok,
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
                    } else {
                        writeFailed()
                    }
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.module_settings_section_assistant),
        ) {
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
    }
}

private fun PowerAssistantTarget.labelRes(): Int = when (this) {
    PowerAssistantTarget.OEM -> R.string.module_settings_target_oem
    PowerAssistantTarget.MINIS -> R.string.module_settings_target_minis
}

/**
 * [T-root-manager-detection-android] "su is absent" and "su is hidden from this app" are
 * different dead ends: the real device that produced this distinction runs KernelSU Next,
 * which keeps su invisible to apps that are not on its allowlist while the manager itself
 * stays listed. Only the second case can be fixed by the user, so it says how.
 */
@Composable
internal fun rootStateSubtitle(state: RootAccessState): String = when {
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

/** The Root row: whether su works for this app, and a button to ask again. Shown on the Root page. */
@Composable
internal fun RootStatusSection() {
    val context = LocalContext.current
    val rootState by RootAccess.state.collectAsState()
    SettingsSection(header = stringResource(R.string.system_enhance_root)) {
        SettingsRow(
            icon = Icons.Outlined.Key,
            iconColor = ChatColors.warn,
            title = stringResource(R.string.system_enhance_root),
            subtitle = rootStateSubtitle(rootState),
            showDivider = false,
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
    }
}
