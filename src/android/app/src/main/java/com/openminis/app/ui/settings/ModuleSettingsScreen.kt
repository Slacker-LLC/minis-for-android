package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleSettingsStore
import com.openminis.app.xposed.PowerAssistantTarget

/**
 * [T-eta-xposed-groups] The switches the LSPosed module reads, in the app that ships it.
 *
 * The module runs inside other processes; the app is where these values are written, through the
 * framework's service ([ModuleSettingsStore]), because that is the store a hooked process is
 * handed. Only switches a ported hook group actually reads are shown - a switch for a feature that
 * does not exist yet would be a promise the module cannot keep.
 *
 * Two facts about this device are stated rather than assumed, because both silently disable a
 * takeover: whether the framework is connected at all (without it there is nothing to write and
 * the switches are disabled), and whether this app is the system's assistant (the module keeps
 * the power-key gesture otherwise, by design).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by ModuleSettingsStore.connected.collectAsState()
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

    // The role can also change from the OEM settings app, so re-read it whenever this screen
    // comes back to the foreground instead of trusting a value captured at first composition.
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

    // Re-read the switches when the framework arrives: with it connected the values come from the
    // store the hooks read, without it from the local mirror of the last accepted write.
    var gestureBar by remember(connected) {
        mutableStateOf(
            ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
        )
    }
    var doubleFinger by remember(connected) {
        mutableStateOf(
            ModuleSettingsStore.isEnabled(context, ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH),
        )
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
        }
    }
}

private fun PowerAssistantTarget.labelRes(): Int = when (this) {
    PowerAssistantTarget.OEM -> R.string.module_settings_target_oem
    PowerAssistantTarget.MINIS -> R.string.module_settings_target_minis
    PowerAssistantTarget.GEMINI -> R.string.module_settings_target_gemini
}
