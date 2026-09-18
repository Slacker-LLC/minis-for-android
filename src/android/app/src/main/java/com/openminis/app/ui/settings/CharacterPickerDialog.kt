package com.openminis.app.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.roleplay.CharacterProfile
import com.openminis.app.roleplay.CharacterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-eta-character-cards] Choose the character a session talks to, or clear the choice.
 *
 * Ported from Eta's conversation binding UI (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The list is read once when the dialog opens; picking writes through
 * [CharacterRepository], which stores the card snapshot with the binding, so a later edit or
 * deletion of the character cannot turn the session into a plain chat behind the user's back.
 */
@Composable
fun CharacterPickerDialog(
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var characters by remember { mutableStateOf<List<CharacterProfile>>(emptyList()) }
    var boundId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        withContext(Dispatchers.IO) {
            characters = runCatching { CharacterRepository.list() }.getOrDefault(emptyList())
            boundId = runCatching { CharacterRepository.bindingFor(sessionId)?.characterId }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.characters_pick_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (characters.isEmpty()) {
                    Text(
                        text = stringResource(R.string.characters_pick_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn {
                        items(characters, key = { it.id }) { profile ->
                            CharacterChoiceRow(
                                profile = profile,
                                selected = profile.id == boundId,
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching { CharacterRepository.bindToSession(sessionId, profile) }
                                        }
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }
                if (boundId != null) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { CharacterRepository.unbindSession(sessionId) }
                            }
                            onDismiss()
                        }
                    }) {
                        Text(stringResource(R.string.characters_pick_none))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun CharacterChoiceRow(profile: CharacterProfile, selected: Boolean, onClick: () -> Unit) {
    val bitmap = remember(profile.avatarPath) {
        profile.avatarPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = profile.card.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
