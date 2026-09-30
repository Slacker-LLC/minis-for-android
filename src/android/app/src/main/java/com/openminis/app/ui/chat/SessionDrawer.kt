package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.MinisMenuDefaults
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.sessions.FolderActionDialogs
import com.openminis.app.ui.sessions.FolderGroupBlock
import com.openminis.app.ui.sessions.GroupPickerSheet
import com.openminis.app.ui.sessions.SessionListViewModel
import com.openminis.app.ui.sessions.partitionByFolder
import com.openminis.app.ui.sessions.rememberFolderDialogState
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Left-hand session drawer (docs/design/UI-DESIGN-LANGUAGE.md §8).
 *
 * Sessions, groups, pins and multi-select all come from [viewModel] — the same
 * [SessionListViewModel] the session list page uses — so the drawer adds no
 * second copy of grouping or selection state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawerContent(
    viewModel: SessionListViewModel,
    chatRepository: ChatRepository,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBots: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sessions by viewModel.displayedSessions.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val collapsedFolderIds by viewModel.collapsedFolderIds.collectAsState()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsState()

    val scope = rememberCoroutineScope()
    // Search runs through the view-model so the drawer gets the same message-content matches and
    // snippets the session list page shows; the field text is mirrored into it.
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchSnippets by viewModel.searchSnippets.collectAsState()
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose {
            viewModel.isSearchActive.value = false
            viewModel.searchQuery.value = ""
        }
    }

    var sessionToRename by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBulkDelete by remember { mutableStateOf(false) }
    val folderDialogs = rememberFolderDialogState()

    val searchActive = searchQuery.isNotBlank()
    // displayedSessions is already the search result set while a query is active.
    val filteredSessions = sessions

    // Groups are shown only when not searching: a filtered flat list is what a
    // search wants, and it avoids collapsing matches inside closed groups.
    val (folderBlocks, ungrouped) = remember(filteredSessions, folders, collapsedFolderIds, searchActive) {
        if (searchActive) emptyList<FolderGroupBlock>() to filteredSessions
        else partitionByFolder(filteredSessions, folders, collapsedFolderIds)
    }
    val sessionsById = remember(filteredSessions) { filteredSessions.associateBy { it.id } }
    val pinned = remember(ungrouped) { ungrouped.filter { it.pinnedAt != null } }
    val recent = remember(ungrouped) { ungrouped.filter { it.pinnedAt == null } }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .padding(top = topInset),
    ) {
        if (isSelecting) {
            SelectionHeader(
                count = selectedIds.size,
                onDone = { viewModel.clearSelection() },
            )
        } else {
            Text(
                text = "Minis",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            )
            DrawerSearchField(
                value = searchQuery,
                onValueChange = {
                    viewModel.searchQuery.value = it
                    viewModel.isSearchActive.value = it.isNotBlank()
                },
                placeholder = stringResource(R.string.drawer_search_chats),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!searchActive) {
                DrawerNewChatRow(onClick = onNewChat)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                DrawerGridEntry(
                    icon = Icons.Outlined.Group,
                    title = stringResource(R.string.bots_team),
                    onClick = onOpenBots,
                    modifier = Modifier.weight(1f),
                )
                DrawerGridEntry(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.scheduled_tasks_title),
                    onClick = onOpenScheduledTasks,
                    modifier = Modifier.weight(1f),
                )
                DrawerGridEntry(
                    icon = Icons.Default.Terminal,
                    title = stringResource(R.string.drawer_terminal),
                    onClick = onOpenTerminal,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            if (searchActive) {
                if (filteredSessions.isNotEmpty()) {
                    item(key = "hdr-results") {
                        SectionLabel(stringResource(R.string.drawer_search_results, filteredSessions.size))
                    }
                }
                items(filteredSessions, key = { "s-${it.id}" }) { session ->
                    DrawerSearchResultRow(
                        session = session,
                        query = searchQuery.trim(),
                        snippet = searchSnippets[session.id],
                        onOpen = { onSelectSession(session.id) },
                    )
                }
            } else {
            if (pinned.isNotEmpty()) {
                item(key = "hdr-pinned") { SectionLabel(stringResource(R.string.sessionlist_section_pinned)) }
                items(pinned, key = { "p-${it.id}" }) { session ->
                    DrawerSessionRow(
                        session = session,
                        viewModel = viewModel,
                        isCurrent = session.id == selectedSessionId,
                        isSelecting = isSelecting,
                        isChecked = session.id in selectedIds,
                        onOpen = { onSelectSession(session.id) },
                        onRename = { sessionToRename = session; renameText = session.title ?: "" },
                        onDelete = { sessionToDelete = session },
                    )
                }
            }

            if (folderBlocks.isNotEmpty()) {
                folderBlocks.forEach { block ->
                    item(key = "f-${block.folder.id}") {
                        FolderHeaderRow(
                            block = block,
                            enabled = !isSelecting,
                            onToggle = { viewModel.toggleFolderCollapsed(block.folder.id) },
                            onNewChatInGroup = {
                                if (block.isCollapsed) viewModel.toggleFolderCollapsed(block.folder.id)
                                viewModel.createNewSession(folderId = block.folder.id)?.let(onSelectSession)
                            },
                            onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                            onRename = { folderDialogs.rename = block.folder },
                            onDissolve = { folderDialogs.dissolve = block.folder },
                            onDeleteWithSessions = { folderDialogs.delete = block.folder to block.totalCount },
                        )
                    }
                    items(block.ids, key = { "m-${block.folder.id}-$it" }) { id ->
                        val session = sessionsById[id] ?: return@items
                        DrawerSessionRow(
                            session = session,
                            viewModel = viewModel,
                            isCurrent = session.id == selectedSessionId,
                            isSelecting = isSelecting,
                            isChecked = session.id in selectedIds,
                            indent = 12.dp,
                            onOpen = { onSelectSession(session.id) },
                            onRename = { sessionToRename = session; renameText = session.title ?: "" },
                            onDelete = { sessionToDelete = session },
                        )
                    }
                }
            }

            item(key = "hdr-recent") { SectionLabel(stringResource(R.string.drawer_recent)) }
            items(recent, key = { "r-${it.id}" }) { session ->
                DrawerSessionRow(
                    session = session,
                    viewModel = viewModel,
                    isCurrent = session.id == selectedSessionId,
                    isSelecting = isSelecting,
                    isChecked = session.id in selectedIds,
                    onOpen = { onSelectSession(session.id) },
                    onRename = { sessionToRename = session; renameText = session.title ?: "" },
                    onDelete = { sessionToDelete = session },
                )
            }
            }

            if (filteredSessions.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (searchActive) stringResource(R.string.drawer_no_search_match) else stringResource(R.string.drawer_no_history),
                        fontSize = 13.5.sp,
                        color = ChatColors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        }

        if (isSelecting) {
            SelectionFooter(
                count = selectedIds.size,
                bottomInset = bottomInset,
                onMove = { viewModel.requestGroupPickerForSelection() },
                onDelete = { showBulkDelete = true },
            )
        }
        // Bottom docked settings row (full width; no floating button).
        if (!isSelecting) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
            // Files and Settings are the two "places" of the app, so they sit together at the bottom,
            // apart from the agent's working tools (team, tasks, terminal) in the grid above.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomInset),
            ) {
                DrawerDockEntry(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.settings_section_files),
                    onClick = onOpenStorage,
                    modifier = Modifier.weight(1f),
                )
                DrawerDockEntry(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // Rename session
    sessionToRename?.let { session ->
        MinisAlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text(stringResource(R.string.drawer_rename_session), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newTitle = renameText.trim()
                        if (newTitle.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                chatRepository.updateSessionTitle(session.id, newTitle)
                            }
                        }
                        sessionToRename = null
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Delete one session (destructive → red confirm)
    sessionToDelete?.let { session ->
        MinisAlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = stringResource(R.string.drawer_delete_session),
            text = stringResource(R.string.drawer_delete_session_confirm),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSession(session.id)
                if (session.id == selectedSessionId) onNewChat()
                sessionToDelete = null
            },
        )
    }

    // Delete the multi-selection
    if (showBulkDelete) {
        val n = selectedIds.size
        MinisAlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = if (n == 1) {
                stringResource(R.string.sessionlist_delete_one_title)
            } else {
                stringResource(R.string.sessionlist_delete_n_title, n)
            },
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                val deletesCurrent = selectedSessionId != null && selectedSessionId in selectedIds
                viewModel.deleteSelected()
                if (deletesCurrent) onNewChat()
                showBulkDelete = false
            },
        )
    }

    FolderActionDialogs(
        state = folderDialogs,
        memberCounts = folderMemberCounts,
        onRename = { id, name, desc -> viewModel.renameFolder(id, name, desc) },
        onDissolve = { viewModel.dissolveFolder(it) },
        onDeleteWithSessions = { viewModel.deleteFolderWithSessions(it) },
    )

    groupPickerRequest?.let { request ->
        val suggesting by viewModel.groupSuggesting.collectAsState()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsState()
        val suggestion by viewModel.groupSuggestion.collectAsState()
        GroupPickerSheet(
            folders = folders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            onSuggest = { viewModel.suggestGroup() },
        )
    }
}

/** Multi-select header from the board: a large "N selected" title with Done on the right. */
@Composable
private fun SelectionHeader(
    count: Int,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.sessionlist_n_selected, count),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        MinisTextButton(onClick = onDone) {
            Text(stringResource(R.string.drawer_select_done), fontSize = 16.sp)
        }
    }
}

/** Multi-select actions docked at the bottom: move (accent) and delete (red), disabled at zero. */
@Composable
private fun SelectionFooter(
    count: Int,
    bottomInset: Dp,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .padding(bottom = bottomInset),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinisTextButton(onClick = onMove, enabled = count > 0) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.drawer_move_n, count), fontSize = 15.sp)
        }
        MinisTextButton(onClick = onDelete, enabled = count > 0) {
            val tint = if (count > 0) MaterialTheme.colorScheme.error else Color.Unspecified
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.delete), fontSize = 15.sp, color = tint)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = ChatColors.secondaryText,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

/** Always-visible search field: fill grey, search glyph, clear button once something is typed. */
@Composable
private fun DrawerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = ChatColors.primaryText),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ChatColors.secondaryBg)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, fontSize = 15.sp, color = ChatColors.secondaryText, maxLines = 1)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.model_picker_search_clear),
                        tint = ChatColors.secondaryText,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onValueChange("") },
                    )
                }
            }
        },
    )
}

/** New chat: accent glyph and label, no tinted pill (the buttons are text). */
@Composable
private fun DrawerNewChatRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.scheduled_task_target_new),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** One of the four daily entries: glyph over a short label, both in the accent. */
@Composable
private fun DrawerDockEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = ChatColors.secondaryText, modifier = Modifier.size(20.dp))
        Text(text = title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = ChatColors.primaryText)
    }
}

@Composable
private fun DrawerGridEntry(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Search hit: title with the match highlighted, then the matching message excerpt if any. */
@Composable
private fun DrawerSearchResultRow(
    session: ChatSessionEntity,
    query: String,
    snippet: String?,
    onOpen: () -> Unit,
) {
    val newSessionLabel = stringResource(R.string.drawer_new_session)
    val title = session.title?.ifBlank { newSessionLabel } ?: newSessionLabel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = com.openminis.app.ui.sessions.highlightedAnnotatedString(title, query),
            fontSize = 15.sp,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (snippet != null) {
            Text(
                text = com.openminis.app.ui.sessions.highlightedAnnotatedString(snippet, query),
                fontSize = 12.5.sp,
                color = ChatColors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Menu look from the board: 14dp corners, 44dp rows, the glyph on the right. */
@Composable
private fun DrawerMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 15.sp) },
        trailingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
        colors = if (destructive) MinisMenuDefaults.destructiveItemColors() else MinisMenuDefaults.itemColors(
            leadingIconColor = MaterialTheme.colorScheme.onSurface,
            trailingIconColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = MinisMenuDefaults.ItemPadding,
        modifier = Modifier.height(44.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeaderRow(
    block: FolderGroupBlock,
    enabled: Boolean,
    onToggle: () -> Unit,
    onNewChatInGroup: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onDeleteWithSessions: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onToggle,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
                ),
                tint = ChatColors.secondaryText,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (block.isCollapsed) -90f else 0f),
            )
            Text(
                text = block.folder.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (block.totalCount == 0) {
                    stringResource(R.string.group_empty)
                } else {
                    stringResource(R.string.group_n_chats, block.totalCount)
                },
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
            )
        }

        MinisMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(14.dp),
            minWidth = 220.dp,
            // The board's menus are plain white; the default tonal lift tints them with the accent.
            tonalElevation = 0.dp,
        ) {
            DrawerMenuItem(
                text = stringResource(R.string.group_rename),
                icon = Icons.Outlined.Edit,
                onClick = { showMenu = false; onRename() },
            )
            DrawerMenuItem(
                text = stringResource(R.string.group_new_chat_in),
                icon = Icons.Outlined.EditNote,
                onClick = { showMenu = false; onNewChatInGroup() },
            )
            DrawerMenuItem(
                text = stringResource(
                    if (block.folder.pinnedAt != null) R.string.sessionlist_unpin else R.string.sessionlist_pin,
                ),
                icon = Icons.Outlined.PushPin,
                onClick = { showMenu = false; onTogglePin() },
            )
            MinisMenuDivider()
            DrawerMenuItem(
                text = stringResource(R.string.group_dissolve),
                icon = Icons.AutoMirrored.Outlined.Undo,
                onClick = { showMenu = false; onDissolve() },
            )
            DrawerMenuItem(
                text = stringResource(R.string.group_delete_with_sessions, block.totalCount),
                icon = Icons.Outlined.Delete,
                onClick = { showMenu = false; onDeleteWithSessions() },
                destructive = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: ChatSessionEntity,
    viewModel: SessionListViewModel,
    isCurrent: Boolean,
    isSelecting: Boolean,
    isChecked: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    indent: Dp = 0.dp,
) {
    val newSessionLabel = stringResource(R.string.drawer_new_session)
    val title = session.title?.ifBlank { newSessionLabel } ?: newSessionLabel
    // One accent, one wash: the open conversation gets a light accent fill and an accent label.
    val accent = MaterialTheme.colorScheme.primary
    val bgColor = if (isCurrent && !isSelecting) accent.copy(alpha = 0.10f) else Color.Transparent
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp + indent, end = 8.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .combinedClickable(
                    onClick = {
                        if (isSelecting) viewModel.toggleSelect(session.id) else onOpen()
                    },
                    onLongClick = { if (!isSelecting) showMenu = true },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isSelecting) {
                Icon(
                    imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isChecked) accent else ChatColors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                color = if (isCurrent) accent else ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        MinisMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(14.dp),
            minWidth = 220.dp,
            // The board's menus are plain white; the default tonal lift tints them with the accent.
            tonalElevation = 0.dp,
        ) {
            DrawerMenuItem(
                text = stringResource(R.string.drawer_rename),
                icon = Icons.Outlined.Edit,
                onClick = { showMenu = false; onRename() },
            )
            DrawerMenuItem(
                text = stringResource(
                    if (session.pinnedAt != null) R.string.sessionlist_unpin else R.string.sessionlist_pin,
                ),
                icon = Icons.Outlined.PushPin,
                onClick = { showMenu = false; viewModel.togglePin(session.id) },
            )
            DrawerMenuItem(
                text = stringResource(R.string.group_move_to),
                icon = Icons.Outlined.Folder,
                onClick = { showMenu = false; viewModel.requestGroupPicker(session.id) },
            )
            DrawerMenuItem(
                text = stringResource(R.string.sessionlist_select_action),
                icon = Icons.Outlined.CheckCircleOutline,
                onClick = { showMenu = false; viewModel.enterSelection(session.id) },
            )
            MinisMenuDivider()
            DrawerMenuItem(
                text = stringResource(R.string.delete),
                icon = Icons.Outlined.Delete,
                onClick = { showMenu = false; onDelete() },
                destructive = true,
            )
        }
    }
}
