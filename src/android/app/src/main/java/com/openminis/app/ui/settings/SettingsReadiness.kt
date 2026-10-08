package com.openminis.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.permissions.SpecialAccess
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.runtime.ubuntu.RootAccess
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors

/**
 * Everything System & permissions manages, in the order the page lists it. The first four are what a
 * normal task needs; the rest are optional and only serve a feature (screen reading, desktop pet,
 * the assistant role, a tool the Agent is allowed to use).
 */
enum class ReadinessId {
    ROOT, SHIZUKU, ALL_FILES, ACCESSIBILITY, OVERLAY, ASSISTANT_ROLE, BACKGROUND, NOTIFICATIONS,
    CALENDAR, LOCATION, CONTACTS, PHOTOS,
    // The "special access" switches of Android's system settings (see SpecialAccess); all optional.
    INSTALL_APPS, EXACT_ALARM, USAGE_STATS, FULL_SCREEN, WRITE_SETTINGS, DATA_SAVER,
}

data class ReadinessItem(val id: ReadinessId, val ok: Boolean) {
    /**
     * Optional items are shown but never counted as "needs attention" (design: "可选项不计入未就绪").
     * Only Root, all-files access, background running and notifications are required.
     */
    val optional: Boolean get() = id !in REQUIRED

    companion object {
        val REQUIRED = setOf(ReadinessId.ROOT, ReadinessId.ALL_FILES, ReadinessId.BACKGROUND, ReadinessId.NOTIFICATIONS)
    }
}

object SettingsReadiness {
    /** Items the user should act on: not ready and not optional. */
    fun attention(items: List<ReadinessItem>): List<ReadinessItem> = items.filter { !it.ok && !it.optional }

    /**
     * Pure assembly so the counting rule is testable without Android. [results] holds the raw probe
     * result of every item that applies on this device; an item with no entry is left out (the
     * assistant role on a device without it, a tool the Agent may not use), and the rest keep the
     * declared order.
     */
    fun assemble(results: Map<ReadinessId, Boolean>): List<ReadinessItem> =
        ReadinessId.entries.mapNotNull { id -> results[id]?.let { ReadinessItem(id, it) } }

    /** Below Android 11 there is no all-files switch; the legacy storage grant is not this item. */
    internal fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** The Android grants each Agent tool needs; one granted is enough for location and photos. */
    internal fun toolGrants(id: ReadinessId): List<String> = when (id) {
        ReadinessId.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        ReadinessId.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ReadinessId.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        ReadinessId.PHOTOS ->
            if (Build.VERSION.SDK_INT >= 33) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        else -> emptyList()
    }

    /** The special-access switch behind a readiness item, or null for the other kinds. */
    internal fun specialAccessFor(id: ReadinessId): SpecialAccess? = when (id) {
        ReadinessId.INSTALL_APPS -> SpecialAccess.INSTALL_APPS
        ReadinessId.EXACT_ALARM -> SpecialAccess.EXACT_ALARM
        ReadinessId.USAGE_STATS -> SpecialAccess.USAGE_STATS
        ReadinessId.FULL_SCREEN -> SpecialAccess.FULL_SCREEN
        ReadinessId.WRITE_SETTINGS -> SpecialAccess.WRITE_SETTINGS
        else -> null
    }

    /** Agent tool (Settings > Agent permissions) behind each runtime-grant item. */
    internal fun toolNameFor(id: ReadinessId): String? = when (id) {
        ReadinessId.CALENDAR -> "calendar"
        ReadinessId.LOCATION -> "location"
        ReadinessId.CONTACTS -> "contacts"
        ReadinessId.PHOTOS -> "photos"
        else -> null
    }

    internal fun probe(context: Context, rootGranted: Boolean): List<ReadinessItem> {
        val results = linkedMapOf(
            ReadinessId.ROOT to rootGranted,
            ReadinessId.SHIZUKU to ShizukuManager.isReady(),
            ReadinessId.ALL_FILES to hasAllFilesAccess(),
            ReadinessId.ACCESSIBILITY to MinisAccessibilityService.isEnabled(context),
            ReadinessId.OVERLAY to Settings.canDrawOverlays(context),
            ReadinessId.BACKGROUND to PowerOptimizationManager.isIgnoringBatteryOptimizations(context),
            ReadinessId.NOTIFICATIONS to NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        val roleManager = context.assistantRoleManagerOrNull()
        if (roleManager != null && runCatching { roleManager.assistantRoleAvailable() }.getOrDefault(false)) {
            results[ReadinessId.ASSISTANT_ROLE] = runCatching { roleManager.assistantRoleHeld() }.getOrDefault(false)
        }
        // A tool the user has switched off in Agent permissions has no use for the Android grant.
        for (id in listOf(ReadinessId.CALENDAR, ReadinessId.LOCATION, ReadinessId.CONTACTS, ReadinessId.PHOTOS)) {
            val tool = toolNameFor(id) ?: continue
            if (!OffloadPermissionManager.isAllowed(tool)) continue
            results[id] = toolGrants(id).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        for (id in ReadinessId.entries) {
            val access = specialAccessFor(id) ?: continue
            if (access.applies) results[id] = runCatching { access.isGranted(context) }.getOrDefault(false)
        }
        results[ReadinessId.DATA_SAVER] = runCatching { SpecialAccess.dataSaverExempt(context) }.getOrDefault(true)
        return assemble(results)
    }
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
        ReadinessId.SHIZUKU -> if (ok) R.string.settings_ready_shizuku_ok else R.string.settings_ready_shizuku_bad
        ReadinessId.ALL_FILES -> if (ok) R.string.settings_ready_files_ok else R.string.settings_ready_files_bad
        ReadinessId.ACCESSIBILITY -> if (ok) R.string.settings_ready_a11y_ok else R.string.settings_ready_a11y_bad
        ReadinessId.OVERLAY -> if (ok) R.string.settings_ready_overlay_ok else R.string.settings_ready_overlay_bad
        ReadinessId.ASSISTANT_ROLE -> if (ok) R.string.settings_ready_role_ok else R.string.settings_ready_role_bad
        ReadinessId.BACKGROUND -> if (ok) R.string.settings_ready_bg_ok else R.string.settings_ready_bg_bad
        ReadinessId.NOTIFICATIONS -> if (ok) R.string.settings_ready_notif_ok else R.string.settings_ready_notif_bad
        ReadinessId.CALENDAR, ReadinessId.LOCATION, ReadinessId.CONTACTS, ReadinessId.PHOTOS ->
            if (ok) R.string.settings_ready_tool_ok else R.string.settings_ready_tool_bad
        ReadinessId.INSTALL_APPS -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_install_bad
        ReadinessId.EXACT_ALARM -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_alarm_bad
        ReadinessId.USAGE_STATS -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_usage_bad
        ReadinessId.FULL_SCREEN -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_fullscreen_bad
        ReadinessId.WRITE_SETTINGS -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_write_bad
        ReadinessId.DATA_SAVER -> if (ok) R.string.settings_ready_special_ok else R.string.settings_ready_datasaver_bad
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
    ReadinessId.SHIZUKU -> R.string.settings_ready_shizuku_title
    ReadinessId.ALL_FILES -> R.string.settings_ready_files_title
    ReadinessId.ACCESSIBILITY -> R.string.settings_ready_a11y_title
    ReadinessId.OVERLAY -> R.string.settings_ready_overlay_title
    ReadinessId.ASSISTANT_ROLE -> R.string.settings_assistant_role
    ReadinessId.BACKGROUND -> R.string.settings_ready_bg_title
    ReadinessId.NOTIFICATIONS -> R.string.settings_ready_notif_title
    ReadinessId.CALENDAR -> R.string.perm_tool_calendar
    ReadinessId.LOCATION -> R.string.perm_tool_location
    ReadinessId.CONTACTS -> R.string.perm_tool_contacts
    ReadinessId.PHOTOS -> R.string.perm_tool_photos
    ReadinessId.INSTALL_APPS -> R.string.settings_ready_install_title
    ReadinessId.EXACT_ALARM -> R.string.settings_ready_alarm_title
    ReadinessId.USAGE_STATS -> R.string.settings_ready_usage_title
    ReadinessId.FULL_SCREEN -> R.string.settings_ready_fullscreen_title
    ReadinessId.WRITE_SETTINGS -> R.string.settings_ready_write_title
    ReadinessId.DATA_SAVER -> R.string.settings_ready_datasaver_title
}

/** Items whose button reads "Grant" (they ask for a permission); the rest send the user to a settings page. */
private val GRANT_LABEL = setOf(
    ReadinessId.ROOT, ReadinessId.ALL_FILES, ReadinessId.OVERLAY, ReadinessId.ASSISTANT_ROLE,
    ReadinessId.CALENDAR, ReadinessId.LOCATION, ReadinessId.CONTACTS, ReadinessId.PHOTOS,
    ReadinessId.INSTALL_APPS, ReadinessId.EXACT_ALARM, ReadinessId.USAGE_STATS,
    ReadinessId.FULL_SCREEN, ReadinessId.WRITE_SETTINGS, ReadinessId.DATA_SAVER,
)

/** Runs the action that fixes [item]: the system page for a grant, or the app's own page. */
private fun fixReadiness(context: Context, item: ReadinessItem, onOpenBackground: () -> Unit) {
    when (item.id) {
        ReadinessId.ROOT -> RootAccess.request(context)
        ReadinessId.SHIZUKU -> when (ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED -> ShizukuManager.openInstallPage(context)
            ShizukuManager.State.NOT_RUNNING -> ShizukuManager.openShizukuApp(context)
            ShizukuManager.State.NEED_PERMISSION -> ShizukuManager.requestPermission()
            ShizukuManager.State.READY -> Unit
        }
        ReadinessId.ALL_FILES -> openAllFilesAccess(context)
        ReadinessId.ACCESSIBILITY -> startSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        ReadinessId.OVERLAY -> startSettings(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
        ReadinessId.ASSISTANT_ROLE -> startSettings(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        ReadinessId.BACKGROUND -> onOpenBackground()
        ReadinessId.NOTIFICATIONS -> startSettings(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        ReadinessId.CALENDAR, ReadinessId.LOCATION, ReadinessId.CONTACTS, ReadinessId.PHOTOS -> startSettings(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        ReadinessId.INSTALL_APPS, ReadinessId.EXACT_ALARM, ReadinessId.USAGE_STATS,
        ReadinessId.FULL_SCREEN, ReadinessId.WRITE_SETTINGS -> {
            val access = SettingsReadiness.specialAccessFor(item.id)!!
            startSettings(context, access.settingsIntent(context))
        }
        ReadinessId.DATA_SAVER -> startSettings(context, SpecialAccess.dataSaverIntent(context))
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
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                            if (item.id in GRANT_LABEL) {
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
