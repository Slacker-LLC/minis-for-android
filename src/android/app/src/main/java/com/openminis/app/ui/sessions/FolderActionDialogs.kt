package com.openminis.app.ui.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField

/**
 * Which group-management dialog is open. Shared by the session list page and
 * the chat drawer so both surfaces render the same rename / dissolve / delete
 * dialogs from the same view-model actions.
 */
@Stable
internal class FolderDialogState {
    var rename by mutableStateOf<FolderEntity?>(null)
    var dissolve by mutableStateOf<FolderEntity?>(null)

    /** iOS "Delete Group & N Sessions" — the pair carries the member count so the confirmation restates the consequence. */
    var delete by mutableStateOf<Pair<FolderEntity, Int>?>(null)
}

@Composable
internal fun rememberFolderDialogState(): FolderDialogState = remember { FolderDialogState() }

@Composable
internal fun FolderActionDialogs(
    state: FolderDialogState,
    memberCounts: Map<String, Int>,
    onRename: (folderId: String, name: String, description: String?) -> Unit,
    onDissolve: (folderId: String) -> Unit,
    onDeleteWithSessions: (folderId: String) -> Unit,
) {
    state.rename?.let { folder ->
        // Both fields are SEEDED from the current group. The rename always
        // writes the description through, so an unseeded field would silently
        // wipe a description the user never touched.
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        MinisAlertDialog(
            onDismissRequest = { state.rename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    // SectionTextField is built for settings screens: it draws
                    // NO border and uses horizontal contentPadding = 0, because
                    // there its parent (SettingsCardBlock) supplies both the
                    // 16dp inset and the card surface that bounds it. A dialog
                    // has neither, so wrap each field the way a settings card
                    // would, plus a hairline border so the input edge is visible.
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    onRename(folder.id, name, desc)
                    state.rename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { state.rename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.dissolve?.let { folder ->
        val count = memberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { state.dissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            // Spells out that nothing is deleted — dissolve is deliberately NOT
            // styled destructive, because it touches no user data.
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                onDissolve(folder.id)
                state.dissolve = null
            },
        )
    }

    // iOS "Delete Group & N Sessions" confirmation — the one destructive
    // folder action, so isDestructive here where dissolve deliberately isn't.
    state.delete?.let { (folder, openedCount) ->
        // The delete removes whoever is in the group when it runs, so the count follows the
        // live membership, not the number at the moment the dialog opened.
        val count = memberCounts[folder.id] ?: openedCount
        MinisAlertDialog(
            onDismissRequest = { state.delete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                onDeleteWithSessions(folder.id)
                state.delete = null
            },
        )
    }
}

/**
 * Card frame for a [SectionTextField] used inside a dialog.
 *
 * The settings screens get this for free from `SettingsCardBlock`: it supplies
 * the 16dp horizontal inset that SectionTextField deliberately omits (its
 * contentPadding is horizontal = 0 so glyphs align with sibling section rows —
 * T352) and the card surface that gives the input an edge. A dialog has no such
 * parent, so a bare SectionTextField renders as text jammed against its fill
 * with no visible boundary.
 *
 * Uses [SectionDesign.CardShape] and the fill grey (`surfaceContainerLow`,
 * #F2F2F7 in light), plus a hairline outline: the dialog's surface is opaque
 * white, and without the outline the field edge is effectively invisible in
 * dark mode.
 */
@Composable
private fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // Full-strength outlineVariant, not a faded one: anything dimmer reads
        // as no border at all in dark mode — verified on device.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}
