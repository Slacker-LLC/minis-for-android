package com.openminis.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawerContent(
    chatRepository: ChatRepository,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions by chatRepository.dao.observeSessions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // State for rename dialog
    var sessionToRename by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    // State for delete confirm dialog
    var sessionToDelete by remember { mutableStateOf<ChatSessionEntity?>(null) }

    val filteredSessions = remember(sessions, searchQuery, isSearching) {
        if (isSearching && searchQuery.isNotBlank()) {
            sessions.filter { (it.title ?: "").contains(searchQuery.trim(), ignoreCase = true) }
        } else {
            sessions
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .padding(top = topInset),
    ) {
        // Top App Bar / Title Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Minis",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.primaryText,
            )
            IconButton(
                onClick = {
                    isSearching = !isSearching
                    if (!isSearching) searchQuery = ""
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = "Search",
                    tint = ChatColors.primaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Search Input (shown when search icon is tapped)
        if (isSearching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索对话...", fontSize = 14.sp) },
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

        // High-priority "新建聊天" Row directly below title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .clickable(onClick = onNewChat)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "新建聊天",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "新建聊天",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Quick Tools section (定时任务, 终端, 存储与沙箱)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            DrawerToolRow(
                icon = Icons.Outlined.Schedule,
                title = "定时任务",
                onClick = onOpenScheduledTasks,
            )
            DrawerToolRow(
                icon = Icons.Default.Terminal,
                title = "终端",
                onClick = onOpenTerminal,
            )
            DrawerToolRow(
                icon = Icons.Outlined.Folder,
                title = "存储与沙箱",
                onClick = onOpenStorage,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Section header: "最近"
        Text(
            text = "最近",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Session list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(filteredSessions, key = { it.id }) { session ->
                val isSelected = session.id == selectedSessionId
                DrawerSessionItem(
                    session = session,
                    isSelected = isSelected,
                    onClick = { onSelectSession(session.id) },
                    onRename = {
                        sessionToRename = session
                        renameText = session.title ?: ""
                    },
                    onDelete = {
                        sessionToDelete = session
                    },
                )
            }

            if (filteredSessions.isEmpty()) {
                item {
                    Text(
                        text = if (isSearching) "未找到相关对话" else "暂无历史对话",
                        fontSize = 13.5.sp,
                        color = ChatColors.tertiaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        }

        // Bottom Docked Settings Row: [⚙️ 设置] (Full width row, no floating circular button)
        HorizontalDivider(
            color = ChatColors.separator.copy(alpha = 0.2f),
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
                contentDescription = "设置",
                tint = ChatColors.secondaryText,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "设置",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
            )
        }
    }

    // Rename Dialog
    sessionToRename?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("重命名会话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("取消")
                }
            },
        )
    }

    // Delete Confirm Dialog
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("删除会话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除此会话吗？此操作无法撤销。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletedId = session.id
                        scope.launch(Dispatchers.IO) {
                            chatRepository.deleteSession(deletedId)
                        }
                        if (deletedId == selectedSessionId) {
                            onNewChat()
                        }
                        sessionToDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
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
private fun DrawerSessionItem(
    session: ChatSessionEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = session.title?.ifBlank { "新会话" } ?: "新会话"
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
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
                .background(bgColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else ChatColors.primaryText,
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
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    showMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}

