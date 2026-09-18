package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.runtime.ubuntu.RootAccessState
import com.openminis.app.runtime.ubuntu.RootAccessStatus
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleSettingsStore
import com.openminis.app.xposed.system.AccessibilityProtectionClient

/**
 * [T-system-enhance-android] The aggregate page Phase 6 still owed: what root is for, what
 * the module unlocks, and the two facts this app can actually read about them — the last
 * root probe ([RootAccess]) and the switches/protection the module obeys.
 *
 * Shaped after Eta's `SystemEnhanceScreen` (Mangi-11/Eta @ c15de97), which pairs a root card
 * with a framework-connection card and two explanation sections.
 *
 * What this page deliberately does NOT show is a "module connected" state: the module runs
 * inside other processes and only *reads* preferences, so this app has no handle to ask, and
 * a page that guessed one would be inventing state. The rows below report what is readable
 * and send the user to the page that owns each switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemEnhanceScreen(
    onBack: () -> Unit,
    onOpenSystemPermissions: () -> Unit = {},
    onOpenModuleSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val rootState by RootAccess.state.collectAsState()
    var protection by remember { mutableStateOf(AccessibilityProtectionClient.isEnabled(context)) }
    var hooksOn by remember { mutableIntStateOf(enabledHookSwitchCount(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_enhance_title)) },
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
            SettingsSection(header = stringResource(R.string.system_enhance_section_status)) {
                SettingsRow(
                    icon = Icons.Outlined.Key,
                    iconColor = Color(0xFFFF9F0A),
                    title = stringResource(R.string.system_enhance_root),
                    subtitle = stringResource(rootState.subtitleRes()),
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
                            text = "$hooksOn/${HOOK_SWITCH_KEYS.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        hooksOn = enabledHookSwitchCount(context)
                        onOpenModuleSettings()
                    },
                    showDivider = false,
                )
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
}

@Composable
private fun CapabilityRow(titleRes: Int, showDivider: Boolean = true) {
    SettingsRow(
        title = stringResource(titleRes),
        showChevron = false,
        showDivider = showDivider,
    )
}

/** The switch keys the module settings page owns; the row above counts them. */
private val HOOK_SWITCH_KEYS = listOf(
    ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
    ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
    ModulePrefs.Keys.HOTWORD_SELF_HEAL,
)

private fun enabledHookSwitchCount(context: android.content.Context): Int =
    HOOK_SWITCH_KEYS.count { ModuleSettingsStore.isEnabled(context, it) }

private fun RootAccessState.subtitleRes(): Int = when {
    isChecking -> R.string.system_enhance_root_checking
    status == RootAccessStatus.GRANTED -> R.string.system_enhance_root_granted
    status == RootAccessStatus.NOT_GRANTED -> R.string.system_enhance_root_not_confirmed
    status == RootAccessStatus.DENIED -> R.string.system_enhance_root_denied
    status == RootAccessStatus.UNAVAILABLE -> R.string.system_enhance_root_absent
    status == RootAccessStatus.TIMED_OUT -> R.string.system_enhance_root_timeout
    else -> R.string.system_enhance_root_unknown
}
