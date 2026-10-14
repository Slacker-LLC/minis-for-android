package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import android.app.usage.StorageStatsManager
import android.os.Process
import android.os.storage.StorageManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.data.storage.SessionStorage
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.openminis.app.ui.components.MinisAlertDialog

internal data class SessionStorageInfo(
    val id: String,
    val title: String?,
    val minisSize: Long,
    val mediaSize: Long,
    /** Byte values are measured lower bounds when a category could not be inspected. */
    val sizesComplete: Boolean = true,
) {
    val totalSize: Long get() = minisSize + mediaSize
}

/** Platform package totals include all app data; file categories below are subsets, never added twice. */
internal data class StorageSnapshot(
    val shellSize: Long?,
    val dbSize: Long,
    val sessions: List<SessionStorageInfo>,
    val appSize: Long?,
    val appDataSize: Long?,
    val cacheSize: Long?,
    val globalSizes: Map<String, Long?>,
    val sessionSizesComplete: Boolean,
) {
    val sessionsTotal: Long get() = sessions.sumOf { it.totalSize }
    val total: Long get() = (shellSize ?: 0L) + (appSize ?: 0L) + (appDataSize ?: 0L)
    val totalComplete: Boolean get() = shellSize != null && appSize != null && appDataSize != null
    val hasMeasuredTotal: Boolean get() = shellSize != null || appSize != null || appDataSize != null
}

/** Android supplies own-package statistics without Root or a usage-access permission. */
internal suspend fun loadStorageSnapshot(context: Context, chatDao: ChatDao): StorageSnapshot =
    withContext(Dispatchers.IO) {
        val shellSize = runCatching {
            RootfsManager.getInstance(context.applicationContext).getRootfsSize()
        }.getOrNull()
        val packageStats = runCatching {
            context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(
                context.applicationInfo.storageUuid ?: StorageManager.UUID_DEFAULT,
                context.packageName, Process.myUserHandle(),
            )
        }.getOrNull()
        val globalSizes = linkedMapOf(
            "shared" to "/var/minis/shared", "skills" to "/var/minis/skills",
            "memory" to "/var/minis/memory", "home" to "/home/minis",
        ).mapValues { (_, path) -> runCatching { WorkspaceFileClient.treeSize(null, path) }.getOrNull() }
        var sessionSizesComplete = true
        val allSessions = chatDao.listSessions()
        val mediaDir = File(context.filesDir, "media")
        val mediaSizes = runCatching { mediaSizesBySession(mediaDir, allSessions.map { it.id }.toSet()) }
            .getOrElse { sessionSizesComplete = false; null }
        val sessions = allSessions.map { session ->
            val minisSize = runCatching { WorkspaceFileClient.sessionTreeSize(session.id) }
                .getOrElse { sessionSizesComplete = false; null }
            SessionStorageInfo(session.id, session.title, minisSize ?: 0L, mediaSizes?.get(session.id) ?: 0L,
                sizesComplete = minisSize != null && mediaSizes != null)
        }.sortedByDescending { it.totalSize }
        StorageSnapshot(shellSize, databaseSize(context), sessions, packageStats?.appBytes,
            packageStats?.dataBytes, packageStats?.cacheBytes, globalSizes, sessionSizesComplete)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    chatDao: ChatDao,
    onBack: () -> Unit,
    onRootfsClick: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }

    fun reload() {
        scope.launch {
            isLoading = true
            snapshot = loadStorageSnapshot(context, chatDao)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val sessions = snapshot?.sessions.orEmpty()
    fun size(bytes: Long?): String = bytes?.let { Formatter.formatFileSize(context, it) }
        ?: if (isLoading) "—" else context.getString(R.string.storage_size_unavailable)

    SettingsScaffold(
        title = stringResource(R.string.storage_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_section_files),
    ) {
        SettingsSection(header = stringResource(R.string.storage_section_overview)) {
            StorageOverviewRow(Color(0xFF8E8E93), stringResource(R.string.storage_overview_shell), size(snapshot?.shellSize), onRootfsClick, true)
            StorageOverviewRow(Color(0xFFFF9F0A), stringResource(R.string.storage_overview_app), size(snapshot?.appSize), showDivider = true)
            StorageOverviewRow(Color(0xFF34C759), stringResource(R.string.storage_overview_data), size(snapshot?.appDataSize), showDivider = false)
        }
        SettingsSection(header = stringResource(R.string.storage_data_breakdown), footer = stringResource(R.string.storage_data_breakdown_footer)) {
            StorageOverviewRow(Color(0xFF007AFF), stringResource(R.string.storage_overview_database), size(snapshot?.dbSize), showDivider = true)
            StorageOverviewRow(Color(0xFF5856D6), stringResource(R.string.storage_overview_sessions),
                size(snapshot?.takeIf { it.sessionSizesComplete }?.sessionsTotal), showDivider = true)
            StorageOverviewRow(Color(0xFF8E8E93), stringResource(R.string.storage_overview_cache), size(snapshot?.cacheSize), showDivider = true)
            listOf("shared" to R.string.files_shared_title, "skills" to R.string.settings_skills,
                "memory" to R.string.settings_memory, "home" to R.string.storage_overview_home).forEachIndexed { index, (key, label) ->
                StorageOverviewRow(Color(0xFF007AFF), stringResource(label), size(snapshot?.globalSizes?.get(key)), showDivider = index < 3)
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_sessions)) {
            when {
                isLoading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                sessions.isEmpty() -> Text(
                    stringResource(R.string.storage_no_sessions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                else -> sessions.forEachIndexed { index, session ->
                    SettingsValueRow(
                        title = session.title ?: stringResource(R.string.chat_title_pill_session_default),
                        value = size(session.totalSize.takeIf { session.sizesComplete }),
                        onClick = { onSessionClick(session.id) },
                        showDivider = index < sessions.size - 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStorageDetailScreen(
    sessionId: String,
    chatDao: ChatDao,
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit = {},
    onBrowseArtifact: (String?, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember(sessionId) { mutableStateOf<ChatSessionEntity?>(null) }
    var preview by remember(sessionId) { mutableStateOf<SessionStorage.Preview?>(null) }
    var isClearing by remember(sessionId) { mutableStateOf(false) }
    var showClearDialog by remember(sessionId) { mutableStateOf(false) }
    var selected by remember(sessionId) { mutableStateOf(setOf(SessionStorage.Category.BROWSER)) }
    fun reload() {
        scope.launch {
            session = withContext(Dispatchers.IO) { chatDao.getSession(sessionId) }
            preview = SessionStorage.preview(context, sessionId)
        }
    }
    LaunchedEffect(sessionId) { reload() }
    val hasFiles = preview?.bytes?.values?.any { it == null || it > 0L } == true
    fun sizeText(bytes: Long?): String = bytes?.let { Formatter.formatFileSize(context, it) }
        ?: context.getString(R.string.storage_size_unavailable)

    SettingsScaffold(
        title = session?.title ?: stringResource(R.string.storage_session_fallback_title),
        onBack = onBack, backLabel = stringResource(R.string.storage_title),
    ) {
        SettingsSection(header = stringResource(R.string.storage_session_categories)) {
            SessionStorage.Category.entries.forEach { category ->
                SettingsValueRow(
                    title = stringResource(categoryLabel(category)), value = sizeText(preview?.bytes?.get(category)),
                    onClick = if (category == SessionStorage.Category.WORK) onBrowseFiles else null,
                    showDivider = category != SessionStorage.Category.BROWSER,
                )
            }
        }
        TaskArtifactsSection(sessionId = sessionId, onBrowse = onBrowseArtifact)
        SettingsSection(footer = stringResource(R.string.storage_cleanup_footer)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = hasFiles && session != null && !isClearing) {
                selected = setOf(SessionStorage.Category.BROWSER)
                showClearDialog = true
            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isClearing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(if (isClearing) R.string.storage_clearing_status else R.string.storage_clear_session_button),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (hasFiles) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showClearDialog) {
        MinisAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = {
                Column {
                    SessionStorage.Category.entries.forEach { category ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (category in selected) selected - category else selected + category
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = category in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + category else selected - category
                            })
                            Column {
                                Text(stringResource(categoryLabel(category)))
                                Text(sizeText(preview?.bytes?.get(category)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(stringResource(R.string.storage_clear_confirm_body, sizeText(preview?.selectedBytes(selected))))
                    Text(stringResource(R.string.storage_cleanup_footer), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                MinisTextButton(enabled = selected.isNotEmpty() && !isClearing, onClick = {
                    val confirmed = selected.toSet()
                    showClearDialog = false; isClearing = true
                    scope.launch {
                        try {
                            val result = SessionStorage.clear(context, sessionId, confirmed)
                            if (!result.complete) android.widget.Toast.makeText(context, R.string.storage_clear_incomplete, android.widget.Toast.LENGTH_LONG).show()
                            else result.removedBytes?.let { removed ->
                                android.widget.Toast.makeText(context,
                                    context.getString(R.string.storage_cleanup_done, Formatter.formatFileSize(context, removed)),
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { android.widget.Toast.makeText(context, R.string.storage_clear_incomplete, android.widget.Toast.LENGTH_LONG).show() }
                        finally { isClearing = false; reload() }
                    }
                }) { Text(stringResource(R.string.storage_clear_selected), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { MinisTextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun categoryLabel(category: SessionStorage.Category): Int = when (category) {
    SessionStorage.Category.INPUTS -> R.string.storage_category_inputs
    SessionStorage.Category.WORK -> R.string.storage_category_work
    SessionStorage.Category.OUTPUTS -> R.string.storage_category_outputs
    SessionStorage.Category.BROWSER -> R.string.storage_category_browser
}

@Composable
private fun StorageOverviewRow(
    color: Color,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(divider),
            )
        }
    }
}

private fun databaseSize(context: Context): Long {
    val dbFile = context.getDatabasePath("minis.db")
    var size = if (dbFile.exists()) dbFile.length() else 0L
    val wal = File(dbFile.path + "-wal")
    val shm = File(dbFile.path + "-shm")
    if (wal.exists()) size += wal.length()
    if (shm.exists()) size += shm.length()
    return size
}

private fun mediaSizesBySession(mediaDir: File, sessionIds: Set<String>): Map<String, Long> =
    MediaStore(mediaDir).sessionSizes(sessionIds)

/** Kept as the existing settings entry; all callers share the no-follow MediaStore implementation. */
internal fun deleteSessionMedia(mediaDir: File, sessionId: String): Boolean =
    MediaStore(mediaDir).deleteSessionMedia(sessionId)
