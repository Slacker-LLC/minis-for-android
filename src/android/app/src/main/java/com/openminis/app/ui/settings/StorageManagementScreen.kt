package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

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
) {
    val totalSize: Long get() = minisSize + mediaSize
}

private val SESSION_GUEST_ROOTS = listOf(
    "/var/minis/workspace",
    "/var/minis/attachments",
    "/var/minis/offloads",
    "/var/minis/browser",
)

/** Sizes behind the storage overview: the Shell container, the database and per-session files. */
internal data class StorageSnapshot(
    val shellSize: Long,
    val dbSize: Long,
    val sessions: List<SessionStorageInfo>,
) {
    val sessionsTotal: Long get() = sessions.sumOf { it.totalSize }
    val total: Long get() = shellSize + dbSize + sessionsTotal
}

/** Walks the guest and media trees, so callers show a placeholder until it returns. */
internal suspend fun loadStorageSnapshot(context: Context, chatDao: ChatDao): StorageSnapshot =
    withContext(Dispatchers.IO) {
        val shellSize = runCatching {
            RootfsManager.getInstance(context.applicationContext).getRootfsSize()
        }.getOrDefault(0L)
        val dbSize = databaseSize(context)

        val allSessions = chatDao.listSessions()
        val mediaDir = File(context.filesDir, "media")
        val mediaSizes = mediaSizesBySession(mediaDir, allSessions.map { it.id }.toSet())

        val sessions = allSessions.map { session ->
            var minisSize = 0L
            for (root in SESSION_GUEST_ROOTS) {
                minisSize += runCatching {
                    WorkspaceFileClient.treeSize(session.id, root)
                }.getOrDefault(0L)
            }
            SessionStorageInfo(
                id = session.id,
                title = session.title,
                minisSize = minisSize,
                mediaSize = mediaSizes[session.id] ?: 0L,
            )
        }.sortedByDescending { it.totalSize }
        StorageSnapshot(shellSize, dbSize, sessions)
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
    var shellSize by remember { mutableLongStateOf(0L) }
    var dbSize by remember { mutableLongStateOf(0L) }
    var sessions by remember { mutableStateOf<List<SessionStorageInfo>>(emptyList()) }

    fun reload() {
        scope.launch {
            isLoading = true
            val snapshot = loadStorageSnapshot(context, chatDao)
            shellSize = snapshot.shellSize
            dbSize = snapshot.dbSize
            sessions = snapshot.sessions
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val totalSessionSize = sessions.sumOf { it.totalSize }

    SettingsScaffold(
        title = stringResource(R.string.storage_title),
        onBack = onBack,
        backLabel = stringResource(R.string.settings_section_files),
    ) {
        SettingsSection(header = stringResource(R.string.storage_section_overview)) {
            StorageOverviewRow(
                color = Color(0xFF8E8E93),
                label = stringResource(R.string.storage_overview_shell),
                value = Formatter.formatFileSize(context, shellSize),
                onClick = onRootfsClick,
                showDivider = true,
            )
            StorageOverviewRow(
                color = Color(0xFF007AFF),
                label = stringResource(R.string.storage_overview_database),
                value = Formatter.formatFileSize(context, dbSize),
                showDivider = true,
            )
            StorageOverviewRow(
                color = Color(0xFF5856D6),
                label = stringResource(R.string.storage_overview_sessions),
                value = Formatter.formatFileSize(context, totalSessionSize),
                showDivider = false,
            )
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
                        value = Formatter.formatFileSize(context, session.totalSize),
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var minisSize by remember { mutableLongStateOf(0L) }
    var mediaSize by remember { mutableLongStateOf(0L) }
    var isClearing by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val mediaDir = File(context.filesDir, "media")

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                session = chatDao.getSession(sessionId)
                minisSize = 0L
                for (root in SESSION_GUEST_ROOTS) {
                    minisSize += runCatching {
                        WorkspaceFileClient.treeSize(sessionId, root)
                    }.getOrDefault(0L)
                }
                val mediaSizes = mediaSizesBySession(mediaDir, setOf(sessionId))
                mediaSize = mediaSizes[sessionId] ?: 0L
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val totalSize = minisSize + mediaSize
    val hasFiles = totalSize > 0

    SettingsScaffold(
        title = session?.title ?: stringResource(R.string.storage_session_fallback_title),
        onBack = onBack,
        backLabel = stringResource(R.string.storage_title),
    ) {
        SettingsSection(header = stringResource(R.string.storage_section_minis_files)) {
            if (minisSize > 0) {
                SettingsValueRow(
                    title = stringResource(R.string.storage_browse_files),
                    value = Formatter.formatFileSize(context, minisSize),
                    onClick = onBrowseFiles,
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_minis_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_media)) {
            if (mediaSize > 0) {
                SettingsValueRow(
                    title = stringResource(R.string.permissions_category_media),
                    value = Formatter.formatFileSize(context, mediaSize),
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_media_files),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(
            footer = stringResource(R.string.storage_clear_session_footer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasFiles && !isClearing) { showClearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.storage_clearing_status),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.storage_clear_session_button),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hasFiles) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showClearDialog) {
        MinisAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = {
                Text(stringResource(R.string.storage_clear_confirm_body, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearDialog = false
                    isClearing = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                SESSION_GUEST_ROOTS.forEach { root ->
                                    WorkspaceFileClient.deleteChildren(sessionId, root)
                                }
                                deleteSessionMedia(mediaDir, sessionId)
                            }
                        }.onSuccess { mediaGone ->
                            if (mediaGone) {
                                minisSize = 0L
                                mediaSize = 0L
                            } else {
                                // Some media files are still there: show what is left, not zero.
                                reload()
                                android.widget.Toast.makeText(
                                    context, R.string.storage_clear_incomplete, android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }.onFailure {
                            reload()
                        }
                        isClearing = false
                    }
                }) {
                    Text(
                        "Clear ${Formatter.formatFileSize(context, totalSize)}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
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

private fun mediaSizesBySession(mediaDir: File, sessionIds: Set<String>): Map<String, Long> {
    if (!mediaDir.exists()) return emptyMap()
    val sizes = mutableMapOf<String, Long>()
    mediaDir.walkTopDown().forEach { file ->
        if (file.isFile) {
            val parent = file.parentFile ?: return@forEach
            val sid = parent.name
            if (sessionIds.contains(sid)) {
                sizes[sid] = (sizes[sid] ?: 0L) + file.length()
            }
        }
    }
    return sizes
}

/** Removes the session's media folders; false when any of them could not be removed completely. */
internal fun deleteSessionMedia(mediaDir: File, sessionId: String): Boolean {
    if (!mediaDir.exists()) return true
    var allGone = true
    // Collected first: deleting while walking would invalidate the walk.
    val targets = mediaDir.walkTopDown().filter { it.isDirectory && it.name == sessionId }.toList()
    for (dir in targets) {
        if (dir.exists() && !dir.deleteRecursively()) allGone = false
    }
    return allGone
}
