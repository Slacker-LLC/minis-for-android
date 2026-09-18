package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.roleplay.CharacterBookDraft
import com.openminis.app.roleplay.CharacterBookEntryDraft
import com.openminis.app.roleplay.CharacterProfile
import com.openminis.app.roleplay.CharacterRepository
import com.openminis.app.roleplay.CharacterWorldbookDraftCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-eta-character-cards] One character, and the world book that decides what it remembers.
 *
 * Ported from Eta's character detail and world-book editor (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The screen edits a DRAFT and only writes on Save, through
 * [CharacterWorldbookDraftCodec], so an abandoned edit changes nothing and a saved edit touches only
 * the fields the editor actually knows — the entry fields it does not model survive (see the codec).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(characterId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<CharacterProfile?>(null) }
    var draft by remember { mutableStateOf<CharacterBookDraft?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<Int?>(null) }
    var addingEntry by remember { mutableStateOf(false) }

    LaunchedEffect(characterId) {
        withContext(Dispatchers.IO) {
            val loaded = runCatching { CharacterRepository.get(characterId) }.getOrNull()
            profile = loaded
            draft = loaded?.let { CharacterWorldbookDraftCodec.read(it.card) }
        }
    }

    val current = draft
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = profile?.card?.name ?: stringResource(R.string.characters_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = dirty && current != null && profile != null,
                        onClick = {
                            val target = profile ?: return@TextButton
                            val book = current ?: return@TextButton
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val card = CharacterWorldbookDraftCodec.write(target.card, book)
                                        CharacterRepository.save(target.copy(card = card))
                                    }
                                }
                                dirty = false
                                onBack()
                            }
                        },
                    ) { Text(stringResource(R.string.common_save)) }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.characters_detail_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.characters_book_settings),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                NumberField(
                    label = stringResource(R.string.characters_book_scan_depth),
                    value = current.scanDepth,
                    onValue = { draft = current.copy(scanDepth = it); dirty = true },
                )
            }
            item {
                NumberField(
                    label = stringResource(R.string.characters_book_token_budget),
                    value = current.tokenBudget,
                    onValue = { draft = current.copy(tokenBudget = it); dirty = true },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.characters_book_recursive))
                    Switch(
                        checked = current.recursiveScanning == true,
                        onCheckedChange = { draft = current.copy(recursiveScanning = it); dirty = true },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.characters_entries, current.entries.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = { addingEntry = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.characters_entry_add))
                    }
                }
            }
            items(current.entries.indices.toList(), key = { it }) { index ->
                val entry = current.entries[index]
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { editingEntry = index },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { enabled ->
                            val entries = current.entries.toMutableList()
                            entries[index] = entry.copy(enabled = enabled)
                            draft = current.copy(entries = entries)
                            dirty = true
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name.ifBlank { stringResource(R.string.characters_entry_unnamed) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.keys.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        val entries = current.entries.toMutableList().also { it.removeAt(index) }
                        draft = current.copy(entries = entries)
                        dirty = true
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.characters_entry_delete))
                    }
                }
            }
        }
    }

    val editing = editingEntry?.let { index -> current?.entries?.getOrNull(index)?.let { index to it } }
    if (editing != null) {
        val (index, entry) = editing
        EntryEditorDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onConfirm = { updated ->
                val entries = (current?.entries ?: emptyList()).toMutableList()
                entries[index] = updated
                draft = current?.copy(entries = entries)
                dirty = true
                editingEntry = null
            },
        )
    }
    if (addingEntry && current != null) {
        EntryEditorDialog(
            entry = CharacterBookEntryDraft(insertionOrder = current.entries.size),
            onDismiss = { addingEntry = false },
            onConfirm = { added ->
                draft = current.copy(entries = current.entries + added)
                dirty = true
                addingEntry = false
            },
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int?, onValue: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val trimmed = text.trim()
            onValue(if (trimmed.isEmpty()) null else trimmed.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EntryEditorDialog(
    entry: CharacterBookEntryDraft,
    onDismiss: () -> Unit,
    onConfirm: (CharacterBookEntryDraft) -> Unit,
) {
    var name by remember(entry) { mutableStateOf(entry.name) }
    var keys by remember(entry) { mutableStateOf(entry.keys.joinToString(", ")) }
    var content by remember(entry) { mutableStateOf(entry.content) }
    var before by remember(entry) { mutableStateOf(entry.position == "before_char") }
    var constant by remember(entry) { mutableStateOf(entry.constant) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.characters_entry_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.characters_entry_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text(stringResource(R.string.characters_entry_keys)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.characters_entry_content)) },
                    minLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.characters_entry_before))
                    Switch(checked = before, onCheckedChange = { before = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.characters_entry_constant))
                    Switch(checked = constant, onCheckedChange = { constant = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    entry.copy(
                        name = name.trim(),
                        keys = keys.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        content = content,
                        position = if (before) "before_char" else "after_char",
                        constant = constant,
                    ),
                )
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
