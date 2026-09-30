package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors

/**
 * The system conditions the Agent needs to work, in the order the design lists them
 * (Settings > System & permissions > Readiness check).
 */
enum class ReadinessId { ROOT, ALL_FILES, ACCESSIBILITY, BACKGROUND, NOTIFICATIONS }

data class ReadinessItem(val id: ReadinessId, val ok: Boolean) {
    /**
     * Accessibility only serves the screen-reading tools; the Ubuntu runtime, file access,
     * background running and notifications are what a normal task needs. Optional items are shown
     * but never counted as "needs attention" (design: "可选项不计入未就绪").
     */
    val optional: Boolean get() = id == ReadinessId.ACCESSIBILITY
}

object SettingsReadiness {
    /** Items the user should act on: not ready and not optional. */
    fun attention(items: List<ReadinessItem>): List<ReadinessItem> = items.filter { !it.ok && !it.optional }

    /**
     * Pure assembly so the counting rule is testable without Android: [rootGranted], [allFiles],
     * [accessibility], [backgroundUnrestricted] and [notifications] are the raw probe results.
     */
    fun assemble(
        rootGranted: Boolean,
        allFiles: Boolean,
        accessibility: Boolean,
        backgroundUnrestricted: Boolean,
        notifications: Boolean,
    ): List<ReadinessItem> = listOf(
        ReadinessItem(ReadinessId.ROOT, rootGranted),
        ReadinessItem(ReadinessId.ALL_FILES, allFiles),
        ReadinessItem(ReadinessId.ACCESSIBILITY, accessibility),
        ReadinessItem(ReadinessId.BACKGROUND, backgroundUnrestricted),
        ReadinessItem(ReadinessId.NOTIFICATIONS, notifications),
    )

    /** Below Android 11 there is no all-files switch; the legacy storage grant is not this item. */
    internal fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    internal fun probe(context: Context, rootGranted: Boolean): List<ReadinessItem> = assemble(
        rootGranted = rootGranted,
        allFiles = hasAllFilesAccess(),
        accessibility = MinisAccessibilityService.isEnabled(context),
        backgroundUnrestricted = PowerOptimizationManager.isIgnoringBatteryOptimizations(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
}

/**
 * Probes on every resume: each grant is changed in a system page, so returning from it must
 * refresh the card without leaving Settings.
 */
@Composable
fun rememberReadiness(): List<ReadinessItem> {
    val context = LocalContext.current
    val root by RootAccess.state.collectAsState()
    var probeTick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) probeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // probeTick is read so a resume re-runs the probe.
    return remember(probeTick, root.isGranted) { SettingsReadiness.probe(context, root.isGranted) }
}

@Composable
private fun ReadinessId.title(): String = stringResource(titleRes())

@Composable
private fun ReadinessItem.subtitle(): String = stringResource(
    when (id) {
        ReadinessId.ROOT -> if (ok) R.string.settings_ready_root_ok else R.string.settings_ready_root_bad
        ReadinessId.ALL_FILES -> if (ok) R.string.settings_ready_files_ok else R.string.settings_ready_files_bad
        ReadinessId.ACCESSIBILITY -> if (ok) R.string.settings_ready_a11y_ok else R.string.settings_ready_a11y_bad
        ReadinessId.BACKGROUND -> if (ok) R.string.settings_ready_bg_ok else R.string.settings_ready_bg_bad
        ReadinessId.NOTIFICATIONS -> if (ok) R.string.settings_ready_notif_ok else R.string.settings_ready_notif_bad
    },
)

/** Top of the Settings home: shown only while something needs attention. */
@Composable
fun ReadinessBanner(items: List<ReadinessItem>, onClick: () -> Unit) {
    val attention = SettingsReadiness.attention(items)
    if (attention.isEmpty()) return
    SettingsSection {
        SettingsRow(
            icon = Icons.Filled.Warning,
            iconColor = ChatColors.warn,
            title = stringResource(R.string.settings_attention_count, attention.size),
            subtitle = attention.map { stringResource(it.id.titleRes()) }.joinToString(" · "),
            onClick = onClick,
            showDivider = false,
        )
    }
}

private fun ReadinessId.titleRes(): Int = when (this) {
    ReadinessId.ROOT -> R.string.settings_ready_root_title
    ReadinessId.ALL_FILES -> R.string.settings_ready_files_title
    ReadinessId.ACCESSIBILITY -> R.string.settings_ready_a11y_title
    ReadinessId.BACKGROUND -> R.string.settings_ready_bg_title
    ReadinessId.NOTIFICATIONS -> R.string.settings_ready_notif_title
}

/** Runs the action that fixes [item]: the system page for a grant, or the app's own page. */
private fun fixReadiness(context: Context, item: ReadinessItem, onOpenBackground: () -> Unit) {
    when (item.id) {
        ReadinessId.ROOT -> RootAccess.request(context)
        ReadinessId.ALL_FILES -> openAllFilesAccess(context)
        ReadinessId.ACCESSIBILITY -> startSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        ReadinessId.BACKGROUND -> onOpenBackground()
        ReadinessId.NOTIFICATIONS -> startSettings(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

/**
 * System & permissions starts with one button, not a list of five rows. Tapping it checks everything
 * the Agent needs and answers in a dialog: "Everything is ready" with Done, or only what is missing,
 * each with the button that goes and fixes it.
 */
@Composable
fun ReadinessCheckSection(onOpenBackground: () -> Unit) {
    val context = LocalContext.current
    val root by RootAccess.state.collectAsState()
    var result by remember { mutableStateOf<List<ReadinessItem>?>(null) }
    SettingsSection(
        header = stringResource(R.string.settings_ready_header),
        footer = stringResource(R.string.settings_ready_footer),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            MinisTextButton(onClick = { result = SettingsReadiness.probe(context, root.isGranted) }) {
                Text(stringResource(R.string.settings_ready_check), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    result?.let { items ->
        val missing = items.filter { !it.ok }
        // Only optional items missing still counts as ready (they are listed, not counted).
        val needsAttention = SettingsReadiness.attention(items).isNotEmpty()
        MinisAlertDialog(
            onDismissRequest = { result = null },
            title = {
                Text(
                    stringResource(
                        if (needsAttention) R.string.settings_ready_fix_title else R.string.settings_ready_all_good,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!needsAttention) Text(stringResource(R.string.settings_ready_all_good_body))
                    run {
                        missing.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.id.title() +
                                            if (item.optional) " · " + stringResource(R.string.settings_ready_optional) else "",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        text = item.subtitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MinisTextButton(onClick = {
                                    result = null
                                    fixReadiness(context, item, onOpenBackground)
                                }) {
                                    Text(
                                        stringResource(
                                            if (item.id == ReadinessId.ROOT || item.id == ReadinessId.ALL_FILES) {
                                                R.string.settings_ready_action_grant
                                            } else {
                                                R.string.settings_ready_action_open
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = { result = null }) { Text(stringResource(R.string.settings_ready_done)) }
            },
        )
    }
}

private fun openAllFilesAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    startSettings(context, intent)
}

/** Best effort: a device without the target settings page must not crash the checklist. */
private fun startSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No such settings page on this device; the row stays as-is.
    }
}
