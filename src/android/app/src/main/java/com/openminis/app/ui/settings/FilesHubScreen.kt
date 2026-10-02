package com.openminis.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.db.ChatDao

/** Colours of the three usage segments, matching the legend dots. */
private val ShellColor = Color(0xFF0A84FF)
private val DatabaseColor = Color(0xFFFF9F0A)
private val SessionsColor = Color(0xFF34C759)

/**
 * Proportions of the usage bar: Shell container, database, session files. All zero when nothing is
 * measured yet, so the bar shows as an empty track instead of dividing by zero.
 */
internal fun storageBarShares(shell: Long, database: Long, sessions: Long): List<Float> {
    val parts = listOf(shell, database, sessions).map { it.coerceAtLeast(0L) }
    val total = parts.sum()
    if (total == 0L) return listOf(0f, 0f, 0f)
    return parts.map { it.toFloat() / total }
}

/**
 * The Files entry (design: Files). It answers "where are my files": what Minis occupies, the
 * directories visible inside the sandbox, external mounts, generated session files and the
 * advanced Rootfs browser. Settings no longer hold any of this; the drawer's Files entry opens it.
 */
@Composable
fun FilesHubScreen(
    chatDao: ChatDao,
    mountedFoldersStore: MountedFoldersStore,
    onBack: () -> Unit,
    onOpenShared: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenMounts: () -> Unit,
    onOpenSessionFiles: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    LaunchedEffect(Unit) { snapshot = loadStorageSnapshot(context, chatDao) }
    val mounts by mountedFoldersStore.entries.collectAsState()

    SettingsScaffold(title = stringResource(R.string.settings_section_files), onBack = onBack, largeTitle = true) {
        UsageHeader(snapshot)

        SettingsSection(
            header = stringResource(R.string.files_section_sandbox),
        ) {
            SettingsRow(
                icon = Icons.Outlined.Folder,
                iconColor = Color(0xFF0A84FF),
                title = stringResource(R.string.files_shared_title),
                subtitle = "/var/minis/shared",
                onClick = onOpenShared,
            )
            SettingsRow(
                icon = Icons.Outlined.Folder,
                iconColor = Color(0xFFAF52DE),
                title = stringResource(R.string.settings_skills),
                subtitle = "/var/minis/skills",
                onClick = onOpenSkills,
            )
            SettingsRow(
                icon = Icons.Outlined.Folder,
                iconColor = Color(0xFFFF9F0A),
                title = stringResource(R.string.settings_memory),
                subtitle = "/var/minis/memory",
                onClick = onOpenMemory,
                showDivider = false,
            )
        }

        SettingsSection(header = stringResource(R.string.files_section_external)) {
            SettingsRow(
                icon = Icons.Outlined.LinkOff,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.files_mounted_title),
                subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                onClick = onOpenMounts,
                showDivider = false,
                trailing = {
                    Text(
                        text = "${mounts.size} / ${MountedFoldersStore.MAX_MOUNTS}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        SettingsSection(header = stringResource(R.string.files_section_generated)) {
            SettingsRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = Color(0xFF8E8E93),
                title = stringResource(R.string.files_session_files_title),
                subtitle = stringResource(R.string.files_session_files_sub),
                onClick = onOpenSessionFiles,
                showDivider = false,
                trailing = {
                    snapshot?.let {
                        Text(
                            text = Formatter.formatFileSize(context, it.sessionsTotal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        // Backups are files too: they are written to Downloads/Minis Backups and restored from a file.
        SettingsSection(header = stringResource(R.string.files_section_backup)) {
            SettingsRow(
                icon = Icons.Outlined.Backup,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                onClick = onOpenBackup,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UsageHeader(snapshot: StorageSnapshot?) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsMetrics.SectionTextInset, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = snapshot?.let { Formatter.formatFileSize(context, it.total) } ?: "—",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.files_hub_used_by),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        val shares = storageBarShares(snapshot?.shellSize ?: 0L, snapshot?.dbSize ?: 0L, snapshot?.sessionsTotal ?: 0L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            listOf(ShellColor, DatabaseColor, SessionsColor).forEachIndexed { i, color ->
                if (shares[i] > 0f) Box(Modifier.weight(shares[i]).height(10.dp).background(color))
            }
        }
        Spacer(Modifier.height(12.dp))
        LegendRow(ShellColor, stringResource(R.string.storage_overview_shell), snapshot?.shellSize)
        LegendRow(DatabaseColor, stringResource(R.string.storage_overview_database), snapshot?.dbSize)
        LegendRow(SessionsColor, stringResource(R.string.storage_overview_sessions), snapshot?.sessionsTotal)
    }
}

@Composable
private fun LegendRow(color: Color, label: String, bytes: Long?) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = bytes?.let { Formatter.formatFileSize(context, it) } ?: "—",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
