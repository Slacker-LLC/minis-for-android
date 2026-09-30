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
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    var sessionToRename by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBulkDelete by remember { mutableStateOf(false) }
    val folderDialogs = rememberFolderDialogState()

    val searchActive = isSearching && searchQuery.isNotBlank()
    val filteredSessions = remember(sessions, searchQuery, isSearching) {
        if (searchActive) {
            sessions.filter { (it.title ?: "").contains(searchQuery.trim(), ignoreCase = true) }
        } else {
            sessions
        }
    }

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
                onCancel = { viewModel.clearSelection() },
                onMove = { viewModel.requestGroupPickerForSelection() },
                onDelete = { showBulkDelete = true },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Minis",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatColors.primaryText,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            isSearching = !isSearching
                            if (!isSearching) searchQuery = ""
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = ChatColors.primaryText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    MinisTextButton(
                        onClick = { viewModel.isSelecting.value = true },
                        enabled = sessions.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.common_edit), fontSize = 15.sp)
                    }
                }
            }
        }

        // Search field (shown when the search icon is tapped)
        if (isSearching && !isSelecting) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.drawer_search_chats), fontSize = 14.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ChatColors.secondaryBg,
                    unfocusedContainerColor = ChatColors.secondaryBg,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (!isSelecting) {
            // New chat: a plain accent text row — no tinted pill (design language §6).
            Text(
                text = stringResource(R.string.scheduled_task_target_new),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                DrawerToolRow(
                    icon = Icons.Outlined.Group,
                    title = stringResource(R.string.bots_team),
                    onClick = onOpenBots,
                )
                DrawerToolRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.scheduled_tasks_title),
                    onClick = onOpenScheduledTasks,
                )
                DrawerToolRow(
                    icon = Icons.Default.Terminal,
                    title = stringResource(R.string.drawer_terminal),
                    onClick = onOpenTerminal,
                )
                DrawerToolRow(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.drawer_storage),
                    onClick = onOpenStorage,
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
                item(key = "hdr-groups") { SectionLabel(stringResource(R.string.group_section_header)) }
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
                            indent = 16.dp,
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

        // Bottom docked settings row (full width; no floating button).
        if (!isSelecting) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
                    .padding(bottom = bottomInset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = ChatColors.primaryText,
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

@Composable
private fun SelectionHeader(
    count: Int,
    onCancel: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MinisTextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), fontSize = 15.sp)
        }
        Text(
            text = stringResource(R.string.sessionlist_n_selected, count),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row {
            MinisTextButton(onClick = onMove, enabled = count > 0) {
                Text(stringResource(R.string.group_move_action), fontSize = 15.sp)
            }
            MinisTextButton(onClick = onDelete, enabled = count > 0) {
                Text(
                    stringResource(R.string.delete),
                    fontSize = 15.sp,
                    color = if (count > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun DrawerToolRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = ChatColors.primaryText,
        )
    }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = enabled,
                    onClick = onToggle,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = block.folder.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
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
            Spacer(modifier = Modifier.weight(1f))
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
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_new_chat_in)) },
                onClick = { showMenu = false; onNewChatInGroup() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (block.folder.pinnedAt != null) R.string.sessionlist_unpin else R.string.sessionlist_pin,
                        ),
                    )
                },
                onClick = { showMenu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_rename)) },
                onClick = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_dissolve)) },
                onClick = { showMenu = false; onDissolve() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.group_delete_with_sessions, block.totalCount),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { showMenu = false; onDeleteWithSessions() },
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
    // One accent, one grey: the open conversation gets the fill grey and an accent label.
    val bgColor = if (isCurrent && !isSelecting) ChatColors.secondaryBg else Color.Transparent
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp + indent, end = 12.dp, top = 1.5.dp, bottom = 1.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
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
                    tint = if (isChecked) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_rename)) },
                onClick = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (session.pinnedAt != null) R.string.sessionlist_unpin else R.string.sessionlist_pin,
                        ),
                    )
                },
                onClick = { showMenu = false; viewModel.togglePin(session.id) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_move_to)) },
                onClick = { showMenu = false; viewModel.requestGroupPicker(session.id) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessionlist_select_action)) },
                onClick = { showMenu = false; viewModel.enterSelection(session.id) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}
