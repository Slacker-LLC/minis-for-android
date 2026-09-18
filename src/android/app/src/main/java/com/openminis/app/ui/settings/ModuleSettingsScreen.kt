package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleSettingsStore
import com.openminis.app.xposed.PowerAssistantTarget

/**
 * [T-eta-xposed-groups] The switches the LSPosed module reads, in the app that ships it.
 *
 * The module runs inside other processes and can only read settings; the app is where they are
 * written, through the same preference group the framework hands over. Only switches that a ported
 * hook group actually reads are shown - a switch for a feature that does not exist yet would be a
 * promise the module cannot keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var gestureBar by remember {
        mutableStateOf(
            ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
        )
    }
    var doubleFinger by remember {
        mutableStateOf(
            ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH),
        )
    }
    var hotword by remember {
        mutableStateOf(ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.HOTWORD_SELF_HEAL))
    }
    var assistantTarget by remember { mutableStateOf(ModuleSettingsStore.assistantTarget(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_settings_title)) },
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
                header = stringResource(R.string.module_settings_section_switches),
                footer = stringResource(R.string.module_settings_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Search,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.module_settings_gesture_bar),
                    subtitle = stringResource(R.string.module_settings_gesture_bar_sub),
                    checked = gestureBar,
                    onCheckedChange = { enabled ->
                        gestureBar = enabled
                        ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                            enabled,
                        )
                    },
                )
                SettingsSwitchRow(
                    icon = Icons.Outlined.TouchApp,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.module_settings_double_finger),
                    subtitle = stringResource(R.string.module_settings_double_finger_sub),
                    checked = doubleFinger,
                    onCheckedChange = { enabled ->
                        doubleFinger = enabled
                        ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                            enabled,
                        )
                    },
                )
                SettingsSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.module_settings_hotword),
                    subtitle = stringResource(R.string.module_settings_hotword_sub),
                    checked = hotword,
                    onCheckedChange = { enabled ->
                        hotword = enabled
                        ModuleSettingsStore.setEnabled(
                            context,
                            ModulePrefs.Keys.HOTWORD_SELF_HEAL,
                            enabled,
                        )
                    },
                    showDivider = false,
                )
            }

            SettingsSection(
                header = stringResource(R.string.module_settings_section_assistant),
                footer = stringResource(R.string.module_settings_assistant_footer),
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
                        onClick = {
                            assistantTarget = target
                            ModuleSettingsStore.setAssistantTarget(context, target)
                        },
                        showDivider = index != PowerAssistantTarget.entries.lastIndex,
                    )
                }
            }
        }
    }
}

private fun PowerAssistantTarget.labelRes(): Int = when (this) {
    PowerAssistantTarget.OEM -> R.string.module_settings_target_oem
    PowerAssistantTarget.MINIS -> R.string.module_settings_target_minis
    PowerAssistantTarget.GEMINI -> R.string.module_settings_target_gemini
}
