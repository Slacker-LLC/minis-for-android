package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.TaskArtifacts
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/** Existing task proof → current file check → source-bound file browser. No duplicated file index. */
@Composable
internal fun TaskArtifactsSection(sessionId: String? = null, onBrowse: (String?, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var references by remember(sessionId) { mutableStateOf(emptyList<TaskArtifacts.Reference>()) }
    var nextOffset by remember(sessionId) { mutableStateOf<Int?>(0) }
    var loading by remember(sessionId) { mutableStateOf(false) }
    var checking by remember(sessionId) { mutableStateOf<String?>(null) }
    var loadFailed by remember(sessionId) { mutableStateOf(false) }
    var exporting by remember(sessionId) { mutableStateOf<TaskArtifacts.Reference?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val reference = exporting
        exporting = null
        if (uri != null && reference != null) scope.launch {
            checking = reference.key
            try {
                TaskArtifacts.export(context, reference, uri)
                references = emptyList(); nextOffset = 0
                val page = TaskArtifacts.page(context, sessionId)
                references = page.references; nextOffset = page.nextOffset
                Toast.makeText(context, R.string.storage_artifact_exported, Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, R.string.storage_artifact_export_failed, Toast.LENGTH_LONG).show() }
            finally { checking = null }
        }
    }
    fun load() {
        val offset = nextOffset ?: return
        scope.launch {
            loading = true; loadFailed = false
            try {
                val page = TaskArtifacts.page(context, sessionId, offset)
                references = references + page.references
                nextOffset = page.nextOffset
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { loadFailed = true }
            finally { loading = false }
        }
    }
    LaunchedEffect(sessionId) { load() }
    SettingsSection(header = stringResource(R.string.storage_artifacts_title), footer = stringResource(R.string.storage_artifacts_footer)) {
        if (references.isEmpty()) Text(
            stringResource(if (loading) R.string.storage_artifacts_loading else if (loadFailed) R.string.storage_size_unavailable else R.string.storage_artifacts_empty),
            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        references.forEach { reference ->
            Column(Modifier.fillMaxWidth().clickable(enabled = checking == null) {
                scope.launch {
                    checking = reference.key
                    try {
                        val current = TaskArtifacts.verify(context, reference)
                        if (current.state == TaskArtifacts.State.UNAVAILABLE) {
                            Toast.makeText(context, R.string.storage_artifact_unavailable, Toast.LENGTH_LONG).show()
                        } else {
                            if (current.state == TaskArtifacts.State.CHANGED) Toast.makeText(context, R.string.storage_artifact_changed, Toast.LENGTH_LONG).show()
                            if (reference.documentUri != null) {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(android.net.Uri.parse(reference.documentUri), context.contentResolver.getType(android.net.Uri.parse(reference.documentUri)))
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } else onBrowse(if (reference.path.startsWith("/shared/") || reference.path.startsWith("/var/minis/shared/")) null else requireNotNull(reference.sessionId), reference.path)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { Toast.makeText(context, R.string.storage_artifact_unavailable, Toast.LENGTH_LONG).show() }
                    finally { checking = null }
                }
            }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(reference.name, style = MaterialTheme.typography.bodyLarge)
                Text(reference.goal, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reference.verifiedAt > 0L) Text(
                    stringResource(R.string.storage_artifact_verified_at, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(reference.verifiedAt))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(if (checking == reference.key) R.string.storage_artifacts_loading else R.string.storage_artifact_browse),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                MinisTextButton(onClick = {
                    exporting = reference
                    exportLauncher.launch(reference.name.ifBlank { "artifact" })
                }, enabled = checking == null && exporting == null) { Text(stringResource(R.string.storage_artifact_export)) }
                if (reference.documentUri == null) MinisTextButton(onClick = {
                    scope.launch {
                        checking = reference.key
                        try {
                            TaskArtifacts.share(context, reference)
                            val page = TaskArtifacts.page(context, sessionId)
                            references = page.references; nextOffset = page.nextOffset
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { Toast.makeText(context, R.string.storage_artifact_export_failed, Toast.LENGTH_LONG).show() }
                        finally { checking = null }
                    }
                }, enabled = checking == null) { Text(stringResource(R.string.storage_artifact_share_members)) }
            }
        }
        if (nextOffset != null && !loading) MinisTextButton(onClick = { load() }) { Text(stringResource(R.string.storage_artifacts_more)) }
    }
}
