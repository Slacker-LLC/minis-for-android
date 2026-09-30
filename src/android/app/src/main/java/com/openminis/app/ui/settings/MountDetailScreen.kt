package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisAlertDialog

/**
 * Detail/edit screen for a single mounted folder. Mirrors iOS
 * MountDetailView (external case only). Allows renaming, toggling
 * the user soft-lock on writes, opening the in-app file browser
 * (placeholder toast for T219-2), and unmounting with confirmation.
 *
 * Save is enabled only when there are pending changes AND the new
 * name is valid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountDetailScreen(
    store: MountedFoldersStore,
    mountId: String,
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == mountId }

    if (entry == null) {
        // Entry was removed (e.g. unmount + back-stack pop race). Pop out.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var nameText by remember(entry.id) { mutableStateOf(entry.name) }
    var allowWrite by remember(entry.id) { mutableStateOf(entry.userAllowWrite) }
    var showUnmountConfirm by remember { mutableStateOf(false) }

    val nameTrimmed = nameText.trim()
    val nameChanged = nameTrimmed != entry.name
    val nameValid = isValidMountName(nameText)
    val allowWriteChanged = allowWrite != entry.userAllowWrite
    val hasChanges = nameChanged || allowWriteChanged
    val canSave = hasChanges && (!nameChanged || nameValid)

    val source = entry.sourceDisplayName.ifEmpty { stringResource(R.string.mount_path_unavailable) }
    SettingsScaffold(
        title = stringResource(R.string.mount_detail_title),
        onBack = onBack,
        backLabel = stringResource(R.string.mount_folders_title),
        actions = {
            MinisTextButton(
                enabled = canSave,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (nameChanged) store.rename(entry.id, nameTrimmed)
                        if (allowWriteChanged) store.setUserAllowWrite(entry.id, allowWrite)
                        withContext(Dispatchers.Main.immediate) { onBack() }
                    }
                },
            ) {
                Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.mount_add_source_path),
                showDivider = false,
                trailing = {
                    Text(
                        source,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                },
            )
        }

        SettingsSection(
            header = stringResource(R.string.mount_folders_header),
            footer = if (nameChanged && !nameValid) {
                stringResource(R.string.mount_detail_name_invalid)
            } else {
                stringResource(R.string.mount_add_name_hint)
            },
        ) {
            SettingsRow(
                title = stringResource(R.string.mount_add_name_label),
                trailing = {
                    androidx.compose.foundation.text.BasicTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = if (nameChanged && !nameValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.widthIn(min = 80.dp, max = 200.dp),
                    )
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mount_add_allow_writes),
                subtitle = stringResource(R.string.mount_add_allow_writes_desc),
                checked = allowWrite,
                onCheckedChange = { allowWrite = it },
                // Disable when the OS-level grant itself isn't writable.
                enabled = entry.isWritable,
                showDivider = false,
            )
        }

        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.mount_detail_browse_files),
                icon = Icons.Outlined.Folder,
                iconColor = Color(0xFF007AFF),
                onClick = onBrowseFiles,
                showDivider = false,
            )
        }

        SettingsSection(footer = stringResource(R.string.mount_unmount_message)) {
            SettingsRow(
                title = stringResource(R.string.mount_unmount_confirm),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { showUnmountConfirm = true },
                showChevron = false,
                showDivider = false,
            )
        }
    }

    if (showUnmountConfirm) {
        MinisAlertDialog(
            onDismissRequest = { showUnmountConfirm = false },
            title = { Text(stringResource(R.string.mount_unmount_title)) },
            text = { Text(stringResource(R.string.mount_unmount_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showUnmountConfirm = false
                    scope.launch(Dispatchers.IO) {
                        store.remove(entry.id)
                        withContext(Dispatchers.Main.immediate) { onBack() }
                    }
                }) {
                    Text(
                        stringResource(R.string.mount_unmount_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showUnmountConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
