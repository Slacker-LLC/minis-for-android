package com.openminis.app.ui.settings

import com.openminis.app.ui.theme.ChatColors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.android.DeviceScreenLease
import com.openminis.app.tools.android.ScreenshotFrameRegistry
import com.openminis.app.tools.android.UnattendedAccessPrefs
import com.openminis.app.tools.android.VirtualScreenObservationRegistry
import com.openminis.app.tools.android.vscreen.VirtualScreenClient
import com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
import com.openminis.app.tools.android.vscreen.VirtualScreenDisplaySettings
import com.openminis.app.tools.android.vscreen.VirtualScreenDisplaySettingsPolicy
import com.openminis.app.tools.android.vscreen.VirtualScreenPreferences
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class ProbeRow(val id: String, val status: String, val code: String, val detail: String)
private data class PreviewResult(val displayId: Int?, val bitmap: Bitmap?)

@Composable
fun VirtualScreenSettingsScreen(onBack: () -> Unit, onOpenRoot: () -> Unit = {}) {
    val context = LocalContext.current
    val client = remember(context) { VirtualScreenClientProvider.get(context) }
    val preferences = remember(context) { VirtualScreenPreferences(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(client.isEnabled()) }
    var probing by remember { mutableStateOf(false) }
    var probePassed by remember { mutableStateOf(client.lastProbe?.passed == true) }
    var probeRows by remember { mutableStateOf(client.lastProbe?.json?.let(::parseProbeRows).orEmpty()) }
    var operationMessage by remember { mutableStateOf("") }
    var showFailures by remember { mutableStateOf(false) }
    var previewMessage by remember { mutableStateOf("") }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    var allowPhysicalUnattended by remember { mutableStateOf(UnattendedAccessPrefs.allowsPhysicalScreen(context)) }
    val savedSpec = remember(context) { preferences.displaySettings() }
    var widthText by remember { mutableStateOf(savedSpec.width.toString()) }
    var heightText by remember { mutableStateOf(savedSpec.height.toString()) }
    var dpiText by remember { mutableStateOf(savedSpec.dpi.toString()) }
    var activeDisplayId by remember { mutableStateOf<Int?>(client.displayId) }

    // The bitmap is captured when the effect starts: reading previewBitmap inside onDispose would return
    // the NEW bitmap (the state has already changed by then) and recycle the picture about to be drawn.
    DisposableEffect(previewBitmap) {
        val shown = previewBitmap
        onDispose { shown?.recycle() }
    }
    // A display opened earlier (by the agent, or before the app was restarted) is still held by the service.
    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { runCatching { client.displayInfo() }.getOrNull() }
        if (info != null) activeDisplayId = info.id
    }

    fun refreshProbe(json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull()
        probePassed = root?.optBoolean("passed", false) == true
        probeRows = parseProbeRows(json)
    }

    fun runCompatibilityCheck(enableWhenPassed: Boolean) {
        if (probing) return
        probing = true
        operationMessage = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // A fresh probe creates a temporary display. Do not disrupt a live display.
                    val live = runCatching { client.queryActiveDisplayId() }.getOrNull()
                    if (live != null) null else client.runProbe()
                } catch (_: Throwable) {
                    null
                }
            }
            if (result == null) {
                operationMessage = context.getString(R.string.vscreen_reason_display_busy)
                probing = false
                return@launch
            }
            refreshProbe(result)
            // A failed check tells the user what failed right away, in one dialog.
            if (!probePassed) showFailures = true
            if (enableWhenPassed && probePassed) {
                val enabledResult = withContext(Dispatchers.IO) {
                    runCatching { client.setEnabled(true) }.isSuccess
                }
                enabled = enabledResult && client.isEnabled()
                operationMessage = if (enabled) context.getString(R.string.vscreen_probe_passed)
                else context.getString(R.string.vscreen_probe_failed)
            } else {
                enabled = client.isEnabled()
                if (enableWhenPassed) {
                    // Probe failure never opts the feature in.
                    runCatching { client.setEnabled(false) }
                    enabled = false
                    operationMessage = context.getString(R.string.vscreen_probe_failed)
                } else {
                    operationMessage = if (probePassed) context.getString(R.string.vscreen_probe_passed)
                    else context.getString(R.string.vscreen_probe_failed)
                }
            }
            probing = false
        }
    }

    fun setFeatureEnabled(requested: Boolean) {
        if (requested) {
            runCompatibilityCheck(enableWhenPassed = true)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val displayId = client.displayId ?: runCatching { client.queryActiveDisplayId() }.getOrNull()
                if (displayId != null && DeviceScreenLease.shared.owner(displayId) != null) return@withContext false
                runCatching { client.setEnabled(false) }.isSuccess
            }
            if (result) {
                enabled = false
                activeDisplayId = null
                previewBitmap = null
                operationMessage = ""
            } else {
                operationMessage = context.getString(R.string.bots_routine_vscreen_busy)
            }
        }
    }

    fun saveDisplaySettingsIfValid() {
        if (activeDisplayId != null) return
        val spec = VirtualScreenDisplaySettings(
            width = widthText.toIntOrNull() ?: return,
            height = heightText.toIntOrNull() ?: return,
            dpi = dpiText.toIntOrNull() ?: return,
        )
        if (VirtualScreenDisplaySettingsPolicy.isValid(spec)) preferences.setDisplaySettings(spec)
    }

    if (showFailures) {
        val failed = probeRows.filter { it.status == "fail" }
        val xiaomi = android.os.Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.equals("redmi", ignoreCase = true)
        val action = failed.map { VirtualScreenCopyPolicy.actionFor(it.code, xiaomi) }
            .firstOrNull { it != VirtualScreenCopyPolicy.FailAction.NONE }
        MinisAlertDialog(
            onDismissRequest = { showFailures = false },
            title = { Text(stringResource(R.string.vscreen_fail_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    failed.forEach { row ->
                        Column {
                            Text(
                                text = stringResource(VirtualScreenCopyPolicy.step(row.id)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(VirtualScreenCopyPolicy.reason(row.code)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = row.code,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (action != null) {
                    MinisTextButton(onClick = {
                        showFailures = false
                        when (action) {
                            VirtualScreenCopyPolicy.FailAction.ROOT -> onOpenRoot()
                            VirtualScreenCopyPolicy.FailAction.DEVELOPER_OPTIONS -> runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                                )
                            }
                            VirtualScreenCopyPolicy.FailAction.NONE -> Unit
                        }
                    }) {
                        Text(
                            stringResource(
                                if (action == VirtualScreenCopyPolicy.FailAction.ROOT) {
                                    R.string.vscreen_fail_action_root
                                } else {
                                    R.string.vscreen_fail_action_devopts
                                },
                            ),
                        )
                    }
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showFailures = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }

    SettingsScaffold(title = stringResource(R.string.vscreen_title), onBack = onBack, backLabel = stringResource(R.string.settings_section_system)) {
        SettingsSection(header = stringResource(R.string.vscreen_title)) {
            SettingsSwitchRow(
                title = stringResource(R.string.vscreen_enabled_label),
                subtitle = stringResource(R.string.vscreen_enabled_summary),
                checked = enabled,
                enabled = !probing,
                onCheckedChange = ::setFeatureEnabled,
                showDivider = false,
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MinisButton(
                    onClick = { runCompatibilityCheck(enableWhenPassed = false) },
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (probing) CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.vscreen_check_compatibility))
                }
                if (probeRows.isNotEmpty()) {
                    // The full step list is not shown: a pass is one line, a failure opens a dialog
                    // that lists only what failed.
                    Text(
                        text = stringResource(if (probePassed) R.string.vscreen_probe_passed else R.string.vscreen_probe_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (probePassed) ChatColors.ok else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    if (!probePassed) {
                        MinisTextButton(onClick = { showFailures = true }) {
                            Text(stringResource(R.string.vscreen_view_failures))
                        }
                    }
                }
                // The pass/fail line above already says this; only show other messages (busy, released...).
                if (operationMessage.isNotBlank() &&
                    operationMessage != context.getString(R.string.vscreen_probe_passed) &&
                    operationMessage != context.getString(R.string.vscreen_probe_failed)
                ) {
                    Text(
                        operationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (operationMessage == context.getString(R.string.vscreen_probe_failed) ||
                            operationMessage == context.getString(R.string.bots_routine_vscreen_busy)
                        ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Everything below belongs to the master switch: it only shows while the feature is on.
        if (enabled) {
            SettingsSection(header = stringResource(R.string.vscreen_resolution)) {
                val sizeLocked = activeDisplayId != null
                val currentSpec = VirtualScreenDisplaySettings(
                    width = widthText.toIntOrNull() ?: 0,
                    height = heightText.toIntOrNull() ?: 0,
                    dpi = dpiText.toIntOrNull() ?: 0,
                )
                SettingsInlineTextRow(
                    title = stringResource(R.string.vscreen_width),
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(4); saveDisplaySettingsIfValid() },
                    numeric = true,
                    enabled = !sizeLocked,
                )
                SettingsInlineTextRow(
                    title = stringResource(R.string.vscreen_height),
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(4); saveDisplaySettingsIfValid() },
                    numeric = true,
                    enabled = !sizeLocked,
                )
                SettingsInlineTextRow(
                    title = stringResource(R.string.vscreen_dpi),
                    value = dpiText,
                    onValueChange = { dpiText = it.filter(Char::isDigit).take(3); saveDisplaySettingsIfValid() },
                    numeric = true,
                    enabled = !sizeLocked,
                    showDivider = false,
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (!VirtualScreenDisplaySettingsPolicy.isValid(currentSpec)) {
                        Text(
                            stringResource(R.string.vscreen_resolution_bounds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SettingsSection(header = stringResource(R.string.vscreen_preview)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    MinisOutlinedButton(
                        onClick = {
                            previewBusy = true
                            previewMessage = ""
                            previewBitmap = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    if (!client.isEnabled()) {
                                        PreviewResult(null, null)
                                    } else {
                                        val id = runCatching { client.queryActiveDisplayId() }.getOrNull()
                                        if (id == null) PreviewResult(null, null) else {
                                            PreviewResult(
                                                id,
                                                runCatching {
                                                    val descriptor = client.screenshot(id)
                                                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                                                        BitmapFactory.decodeStream(stream)
                                                    }
                                                }.getOrNull(),
                                            )
                                        }
                                    }
                                }
                                previewBusy = false
                                activeDisplayId = result.displayId
                                previewBitmap = result.bitmap
                                if (result.displayId == null) previewMessage = context.getString(R.string.vscreen_no_display)
                                else if (result.bitmap == null) previewMessage = context.getString(R.string.vscreen_preview_failed)
                            }
                        },
                        enabled = enabled && !previewBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (previewBusy) CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.vscreen_preview))
                    }
                    if (previewMessage.isNotBlank()) {
                        Text(previewMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.vscreen_preview),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 12.dp),
                        )
                    }
                    MinisOutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    val id = client.displayId ?: runCatching { client.queryActiveDisplayId() }.getOrNull()
                                        ?: return@withContext null
                                    runCatching {
                                        client.releaseDisplay()
                                        VirtualScreenObservationRegistry.clearDisplay(id)
                                        ScreenshotFrameRegistry.clearDisplay(id)
                                        id
                                    }.getOrNull()
                                }
                                when (result) {
                                    null -> previewMessage = context.getString(R.string.vscreen_no_display)
                                    else -> {
                                        activeDisplayId = null
                                        previewBitmap = null
                                        previewMessage = context.getString(R.string.vscreen_released)
                                    }
                                }
                            }
                        },
                        enabled = activeDisplayId != null || enabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(stringResource(R.string.vscreen_release)) }
                }
            }

            SettingsSection(header = stringResource(R.string.vscreen_allow_physical_unattended)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.vscreen_allow_physical_unattended),
                    subtitle = stringResource(R.string.vscreen_allow_physical_unattended_risk),
                    checked = allowPhysicalUnattended,
                    onCheckedChange = {
                        UnattendedAccessPrefs.setAllowPhysicalScreen(context, it)
                        allowPhysicalUnattended = it
                    },
                    showDivider = false,
                )
            }
        }
    }
}

private fun parseProbeRows(raw: String): List<ProbeRow> = runCatching {
    val rows = JSONObject(raw).optJSONArray("steps") ?: return emptyList()
    (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        ProbeRow(
            id = row.optString("id", "unknown"),
            status = row.optString("status", "unknown"),
            code = row.optString("code", "unknown"),
            detail = row.optString("detail").filterNot(Char::isISOControl).take(512),
        )
    }
}.getOrDefault(emptyList())
