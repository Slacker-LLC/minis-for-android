package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.theme.ChatColors

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatTopBar(
    onBotDetails: kotlin.Function1<kotlin.String, kotlin.Unit>,
    onBack: kotlin.Function0<kotlin.Unit>,
    isTwoPane: kotlin.Boolean,
    onToggleSidebar: kotlin.Function0<kotlin.Unit>?,
    onOpenDrawer: kotlin.Function0<kotlin.Unit>?,
    onNewChat: kotlin.Function0<kotlin.Unit>,
    onBrowseChatFiles: kotlin.Function0<kotlin.Unit>,
    context: android.content.Context,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>,
    teamState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.data.db.BotEntity>>,
    currentBot: com.openminis.app.data.db.BotEntity?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showChatMenuState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showVirtualScreenViewerState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showClearChatDialogState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    pagePalette: com.openminis.app.ui.theme.ChatPalette,
) {
    val isStreaming by isStreamingState
    val team by teamState
    var showChatMenu by showChatMenuState
    var showVirtualScreenViewer by showVirtualScreenViewerState
    var showClearChatDialog by showClearChatDialogState
    Column {
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            // Plain chats have no title here (the model and thinking strength live in the
            // composer); a team member keeps its name and status.
            currentBot?.let { bot ->
                Column(
                    Modifier.clickable { onBotDetails(bot.id) }.padding(vertical = 6.dp),
                ) {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (isStreaming) stringResource(R.string.bots_working)
                        else if (!bot.enabled) stringResource(R.string.bots_disabled)
                        else bot.systemPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() }
                            ?: stringResource(R.string.bots_details),
                        style = MaterialTheme.typography.labelSmall,
                        color = ChatColors.secondaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (!isTwoPane) {
                if (onOpenDrawer != null) {
                    IconButton(onClick = onOpenDrawer) {
                        com.openminis.app.ui.components.SidebarPanelIcon()
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            } else if (onToggleSidebar != null) {
                IconButton(
                    onClick = onToggleSidebar,
                    modifier = Modifier.offset(y = (-2).dp),
                ) {
                    com.openminis.app.ui.components.SidebarPanelIcon(size = 26.dp)
                }
            }
        },
        actions = {
            // New chat, on its own. On a chat where nothing has happened yet it does nothing:
            // there is no second empty draft to open.
            IconButton(onClick = { if (!viewModel.isBlankDraft) onNewChat() }) {
                Icon(
                    com.openminis.app.ui.components.MinisIcons.Compose,
                    contentDescription = stringResource(R.string.chat_new_session),
                    modifier = Modifier.size(24.dp),
                )
            }
            Box {
                IconButton(onClick = { showChatMenu = true }) {
                    Icon(com.openminis.app.ui.components.MinisIcons.More, contentDescription = stringResource(R.string.common_more), modifier = Modifier.size(24.dp))
                }
                MinisMenu(
                    expanded = showChatMenu,
                    onDismissRequest = { showChatMenu = false },
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 0.dp,
                ) {
                    // Conversation actions only: new chat is its own button and the terminal lives in
                    // the drawer.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_open_browser)) },
                        onClick = {
                            showChatMenu = false
                            viewModel.toggleBrowserSheet()
                        },
                        trailingIcon = { Icon(com.openminis.app.ui.components.MinisIcons.Globe, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_browse_chat_files)) },
                        onClick = {
                            showChatMenu = false
                            onBrowseChatFiles()
                        },
                        trailingIcon = { Icon(com.openminis.app.ui.components.MinisIcons.Folder, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    )
                    if (VirtualScreenClientProvider.get(context).isEnabled()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vscreen_viewer_menu)) },
                            onClick = {
                                showChatMenu = false
                                showVirtualScreenViewer = true
                            },
                            trailingIcon = { Icon(com.openminis.app.ui.components.MinisIcons.Eye, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        )
                    }
                    MinisMenuDivider()
                    // Destructive, last, in red.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_clear_chat), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showChatMenu = false
                            showClearChatDialog = true
                        },
                        trailingIcon = {
                            Icon(com.openminis.app.ui.components.MinisIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                        },
                    )
                }
            }
        },
        windowInsets = WindowInsets.statusBars,
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = pagePalette.background.copy(alpha = 0.92f),
            scrolledContainerColor = pagePalette.background.copy(alpha = 0.92f),
        ),
        // [T-android-topbar-shrink] 76dp → 68dp. The earlier
        // T-topbar-model-row-clip fix bumped 60dp → 76dp to give the
        // 3-row title (14sp/lh17 + 12sp/lh14 + 11sp/lh13 ≈ 44sp text
        // + 4dp+2dp+1dp vertical padding ≈ 51dp on mdpi, mid-60s on
        // xxhdpi) room to breathe — but overshot, leaving visible
        // dead-space below the model row. This trim pairs with the
        // outer Column's vertical-padding drop (4dp→2dp above):
        // budget is now ~44sp text + 2dp+2dp+1dp ≈ 49dp typical,
        // ~58-62dp at xxhdpi 2.625× rounding. 68dp keeps a 6-10dp
        // safety margin so the model name still fits at any
        // user-configured font scale on xhdpi/xxhdpi without
        // re-clipping (T-topbar-model-row-clip regression check).
        // Font sizes + lineHeights stay untouched per spec.
        expandedHeight = if (currentBot == null) 60.dp else 68.dp,
    )
    // A hairline under the bar only while there is older content scrolled beneath it.
    if (listState.canScrollForward) {
        androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = ChatColors.inputBorder)
    }
    }
}
