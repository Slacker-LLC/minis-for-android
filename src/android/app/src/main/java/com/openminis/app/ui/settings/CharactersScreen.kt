package com.openminis.app.ui.settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.app.roleplay.CharacterCardPng
import com.openminis.app.roleplay.CharacterProfile
import com.openminis.app.roleplay.CharacterRepository
import com.openminis.app.util.BoundedStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * [T-eta-character-cards] The character library: import a card, see what is stored, share it back
 * out or delete it.
 *
 * Ported from Eta's character screens (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta reads a card from a file picker the same way — a PNG with the card
 * inside it, or a JSON card — and keeps the artwork so an export can carry it back out.
 *
 * Everything the screen stores goes through [CharacterRepository], so a card that the importer
 * refuses never reaches the database, and the message the user sees is the codec's own reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterProfile>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<CharacterProfile?>(null) }

    suspend fun refresh() {
        characters = withContext(Dispatchers.IO) {
            runCatching { CharacterRepository.list() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        BoundedStreams.readBytes(input, CharacterCardPng.MAX_FILE_BYTES.toLong())
                    } ?: error("could not read the selected file")
                    CharacterRepository.import(bytes)
                }.exceptionOrNull()
            }
            if (failure != null) {
                Toast.makeText(
                    context,
                    failure.message ?: context.getString(R.string.characters_import_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.characters_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.characters_import))
                    }
                },
            )
        },
    ) { padding ->
        if (characters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.characters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(characters, key = { it.id }) { profile ->
                    CharacterRow(
                        profile = profile,
                        onShare = {
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { shareCard(context, profile) }
                                if (uri == null) {
                                    Toast.makeText(context, R.string.characters_export_failed, Toast.LENGTH_LONG).show()
                                } else {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = if (profile.avatarPath != null) "image/png" else "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, context.getString(R.string.characters_export))
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    )
                                }
                            }
                        },
                        onDelete = { pendingDelete = profile },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.characters_delete)) },
            text = { Text(stringResource(R.string.characters_delete_confirm, target.card.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { CharacterRepository.delete(target.id) }
                        }
                        refresh()
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun CharacterRow(profile: CharacterProfile, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarThumbnail(profile)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val imported = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(profile.updatedAt))
            Text(
                text = imported,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.characters_export))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.characters_delete))
        }
    }
}

@Composable
private fun AvatarThumbnail(profile: CharacterProfile) {
    val bitmap = remember(profile.avatarPath) {
        profile.avatarPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
        } else {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Writes the export into the shared cache so FileProvider can hand it out. Null on failure. */
private suspend fun shareCard(context: android.content.Context, profile: CharacterProfile): Uri? = try {
    val bytes = CharacterRepository.export(profile)
    val name = com.openminis.app.roleplay.CharacterStoragePolicy.exportFileName(profile.card.name)
    val extension = com.openminis.app.roleplay.CharacterStoragePolicy.exportExtension(profile.avatarPath != null)
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "$name.$extension")
    file.writeBytes(bytes)
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (_: Throwable) {
    null
}
