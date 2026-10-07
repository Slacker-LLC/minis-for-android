package com.openminis.app.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenuItem
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import com.openminis.app.service.SessionActivityTracker
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisTextButton










@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
   // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
   onScheduledTasksClick: () -> Unit = {},
    onBotsClick: () -> Unit = {},
    // [T-android-assistant-home] Top-of-list entry into the assistant home page.
    // Kept as a parameter (with a no-op default) so every existing call site
    // compiles unchanged and a surface without the home page simply omits it.
    onOpenAssistantHome: () -> Unit = {},
    selectedSessionId: String? = null,
    draftPlaceholderId: String? = null,
) {
    val context = LocalContext.current
    // T46: hoist VM ownership to the NavBackStackEntry's ViewModelStore so
    // [searchQuery] / [isSearchActive] survive navigation. The previous
    // `remember {}` scoping tied the VM to the composable's lifetime — pushing
    // to chat detail destroyed it, then pop-back rebuilt a fresh VM with
    // empty search state. Mirrors iOS where ContentView's `@State searchText`
    // survives a NavigationLink push because the parent view never unmounts.
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SessionListViewModel.factory(chatRepository, providerRepository, context),
    )
    val persistedSessions by viewModel.displayedSessions.collectAsState()
    val sessions = remember(persistedSessions, draftPlaceholderId) {
        val draftId = draftPlaceholderId
        if (draftId == null || persistedSessions.any { it.id == draftId }) {
            persistedSessions
        } else {
            val now = System.currentTimeMillis()
            listOf(
                com.openminis.app.data.db.ChatSessionEntity(
                    id = draftId,
                    title = null,
                    modelId = "",
                    createdAt = now,
                    updatedAt = now,
                    folderId = null,
                ),
            ) + persistedSessions
        }
    }
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchSnippets by viewModel.searchSnippets.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val regeneratingIds by viewModel.regeneratingIds.collectAsState()
    val providerConfig by providerRepository.config.collectAsState()
    val hasProviders = providerConfig.instances.isNotEmpty()
    val hasMainSlot = providerConfig.slots.main.isNotEmpty()
    // [T-android-startup-config-stall] Provider config now loads off-thread, so
    // for a brief startup window `providerConfig` is the empty placeholder.
    // Gate the onboarding/list render on this too (alongside the sessions
    // initial-load flag) so an existing user with providers but zero sessions
    // doesn't flash the "add a provider" onboarding before the real config emits.
    val configLoaded by providerRepository.configLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val isDark = ChatColors.isDark

    // [T-android-search-focus-sticky] When the user opens search but types
    // nothing (or only whitespace) and then navigates into a chat, the
    // VM-backed search state survives the navigation, so on return the search
    // bar is still open and focused — the user has to manually tap the X to
    // close it. Collapse search BEFORE navigating when the query is blank;
    // keep it (query + results) when there's a real query so returning lands
    // back on the same search. Collapsing flips isSearchActive false, which
    // removes the search TextField from composition and releases its focus.
    fun exitSearchIfQueryBlank() {
        if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
            viewModel.searchQuery.value = ""
            viewModel.isSearchActive.value = false
        }
    }
    // Wrapped navigation callbacks: run the search-collapse check first, then
    // navigate. Used everywhere a session tap / new-chat creation navigates.
    // [T-session-paused-badge-active-false-positive] Do NOT clear the PAUSED
    // badge on open: merely opening an interrupted session does not resolve it.
    // The badge is now driven by ChatViewModel's canResume flow — it clears only
    // when the interruption is actually resolved (Resume tapped / new message
    // sent / loop completed), and the ChatViewModel re-asserts it on load if the
    // session is still interrupted. Clearing here just caused a flicker.
    val searchRequests = com.openminis.app.ui.navigation.SessionSearchRequest.requests
    LaunchedEffect(Unit) {
        searchRequests.drop(1).collect {
            viewModel.isSearchActive.value = true
        }
    }
    val onSessionClickGuarded: (String) -> Unit = { id ->
        exitSearchIfQueryBlank()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id -> exitSearchIfQueryBlank(); onNewChat(id) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    // [T-eta-character-cards] The session whose character is being chosen.
    var characterTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    // [T-android-session-grouping] Group management dialogs.
    val folderDialogs = rememberFolderDialogState()
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showBrowserSheet by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    val browserTabPool = remember { com.openminis.app.browser.BrowserTabPool(context) }

    // [T-android-session-grouping] Groups are pulled out FIRST; only the
    // leftovers go through date bucketing. Assembly order below is
    // Pinned → group block → date buckets, matching iOS.
    val folders by viewModel.folders.collectAsState()
    val collapsedFolderIds by viewModel.collapsedFolderIds.collectAsState()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsState()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsState()
    // While searching, group cards are suppressed: padding a result set with
    // every non-matching group is noise, not structure.
    val showFolderBlock = !isSearchActive || searchQuery.isBlank()
    val sessionBadges by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
    val badgeRevision by com.openminis.app.service.SessionBadgeStore.revision.collectAsState()
    val freshBadgedIds = remember(sessionBadges, badgeRevision) {
        com.openminis.app.service.SessionBadgeStore
            .freshCornerBadgeSessionIds(GROUP_BADGE_FRESH_WINDOW_MS)
    }
    val activeSessionIds by SessionActivityTracker.activeSessions.collectAsState()
    val folderPartition = remember(
        sessions, folders, collapsedFolderIds, showFolderBlock, freshBadgedIds, activeSessionIds,
    ) {
        if (showFolderBlock) {
            partitionByFolder(
                sessions, folders, collapsedFolderIds, freshBadgedIds, activeSessionIds,
            )
        } else emptyList<FolderGroupBlock>() to sessions
    }
    val folderBlocks = folderPartition.first
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.second) }

    // [T-android-folder-accordion-anchor] Assembly order (Pinned → groups →
    // date buckets) hoisted OUT of the LazyColumn body so the scroll-anchor
    // effect and the mini-bar can reconstruct each folder header's flat item
    // index. MUST stay in lockstep with the item builder below — same data,
    // same order, one item per header/row.
    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1 // the "分组" section header item
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size
                }
            }
        }
    }

    // [T-android-newchat-list-autoscroll] Hoisted scroll state so the VM's
    // new-session signal can drive it. The list is ORDER BY updated_at DESC,
    // so a freshly-used session lands at index 0 (top of the TODAY bucket).
    // But the LazyColumn retains its scroll offset across navigation (open
    // chat → back), so if the user had scrolled down, the new session sits
    // above the viewport and they have to scroll up to find it (Jackson 41429).
    //
    // The new-session DETECTION lives in the VM (retained across navigation)
    // because the list composable is disposed during the chat-detail push — a
    // composable-scoped tracker would reset its baseline on pop-back and miss
    // the new session that appeared while we were in the chat. Here we just
    // collect the one-shot event and scroll, skipping it during search (which
    // reorders the list) and selection mode (so it can't fight #765 multi-select).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value && !viewModel.isSelecting.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // [T-android-folder-accordion-anchor] Port of iOS toggleFolderCollapsed's
    // scroll correction. Opening folder B closes folder A (accordion); when A
    // sat ABOVE B with a long member list, A's rows vanish and B slides up —
    // often clean off the top edge, leaving the user staring at the wrong part
    // of the list. MINIMAL correction, not "scroll to top": after the
    // structural change lands (two frames — same reason iOS defers a runloop
    // turn: measuring now would read pre-collapse geometry), scroll B's header
    // back only if it actually LEFT the viewport. Expand-only — collapsing is
    // self-anchoring (the tapped header stays under the finger).
    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        // 8dp slack so a header sitting exactly on the boundary isn't judged
        // off-screen by a sub-pixel rounding difference.
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    // [T-android-folder-minibar] Port of iOS folderMiniBar: when an EXPANDED
    // group's header scrolls off the TOP, float a capsule with the group's
    // icon + name (tap → jump back to the header) and a circled chevron (tap
    // → collapse). Derived straight from LazyListState — no visibility probes
    // needed: header offscreen-above ⇔ the first visible item's index is past
    // the header's reconstructed index. Suppressed in select mode (iOS guard).
    val miniBarBlock by remember(folderBlocks, folderHeaderIndices, isSelecting) {
        derivedStateOf {
            if (isSelecting) return@derivedStateOf null
            val block = folderBlocks.firstOrNull { !it.isCollapsed && it.ids.isNotEmpty() }
                ?: return@derivedStateOf null
            val headerIdx = folderHeaderIndices[block.folder.id] ?: return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            when {
                infos.isEmpty() -> null
                infos.any { it.key == "folder_${block.folder.id}" } -> null
                infos.first().index > headerIdx -> block
                else -> null
            }
        }
    }

    // [T-android-scheduled-tasks-full] Live count of scheduled tasks for the
    // toolbar clock-icon badge. Observes the SharedPreferences-backed store so
    // the badge updates when tasks are added / removed without a manual refresh.
    // [T-android-scheduled-badge-enabled-only] Count only enabled tasks so the
    // badge reflects what's actually active — disabled tasks don't contribute,
    // and with none enabled the count is 0 (badge hidden by the >0 gate below).
    val scheduledTaskCount by remember {
        com.openminis.app.scheduled.ScheduledTaskStore(context).observe()
            .map { list -> list.count { it.enabled } }
    }.collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelecting) {
                        Text(
                            if (selectedIds.isEmpty())
                                stringResource(R.string.sessionlist_select_title)
                            else
                                stringResource(R.string.sessionlist_n_selected, selectedIds.size),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    } else {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }
                },
                navigationIcon = {
                    if (isSelecting) {
                        MinisTextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    } else {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.sessionlist_settings))
                        }
                    }
                },
                actions = {
                    if (isSelecting) {
                        MinisTextButton(onClick = { viewModel.toggleSelectAll() }) {
                            Text(
                                stringResource(
                                    if (persistedSessions.isNotEmpty() && persistedSessions.all { it.id in selectedIds }) R.string.sessionlist_deselect_all
                                    else R.string.sessionlist_select_all
                                )
                            )
                        }
                    } else {
                        // [T-android-scheduled-tasks-design] Scheduled-tasks entry,
                        // sits to the left of the Shell button on the home toolbar.
                        // [T-android-scheduled-tasks-full] Badge shows the count of
                        // scheduled tasks so the user can see at a glance how many
                        // are configured without opening the list.
                        IconButton(onClick = onBotsClick) {
                            Icon(Icons.Outlined.Group, contentDescription = stringResource(R.string.bots_team))
                        }
                        IconButton(onClick = onScheduledTasksClick) {
                            if (scheduledTaskCount > 0) {
                                BadgedBox(badge = { Badge { Text("$scheduledTaskCount") } }) {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = stringResource(R.string.sessionlist_scheduled_tasks),
                                )
                            }
                        }
                        // Shell menu (matching iOS trailing shell button: Terminal, Rootfs, Browser)
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Outlined.Terminal, contentDescription = stringResource(R.string.sessionlist_shell))
                            }
                            MinisMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                offset = DpOffset(0.dp, 0.dp),
                            ) {
                                if (sessions.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sessionlist_select_action)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.isSelecting.value = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                                        },
                                    )
                                    MinisMenuDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_shell_terminal)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onTerminalClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Terminal, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_rootfs_management)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onRootfsClick()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                                MinisMenuDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_open_browser)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        browserTabPool.ensureTabForUI()
                                        showBrowserSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Language, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_browser_settings)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showBrowserSettings = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        // No default FAB — we draw dual FABs manually at bottom
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Main content — render an empty frame until the first DB
            // emission lands. Otherwise `sessions.isEmpty()` reads true for
            // the brief window before Room delivers real data and the
            // onboarding flashes on top of existing user history. Mirrors
            // iOS `didInitialLoad` on ContentView. The transition is usually
            // sub-200ms, so no spinner.
            if (isInitialLoadComplete) Column(modifier = Modifier.fillMaxSize()) {
                // [T-android-assistant-home] The home-page entry sits at the top
                // of the session list: the list stays the primary surface, the
                // home page hangs off its first row.
                com.openminis.app.ui.home.AssistantHomeEntryRow(onClick = onOpenAssistantHome)
                if (sessions.isEmpty()) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (configLoaded) {
                        // Show the 3-step onboarding whenever there are no sessions —
                        // Step 3 (Start a Conversation) is the call-to-action after the
                        // user finishes Steps 1 and 2, so we must keep the landing
                        // visible even when hasProviders && hasMainSlot. Mirrors iOS
                        // ContentView.emptyState.
                        // [T-android-startup-config-stall] Gated on configLoaded so
                        // hasProviders/hasMainSlot reflect the real persisted config —
                        // otherwise a returning user with providers but no sessions
                        // would briefly see the "add a provider" step before the
                        // async config load emits. The list branch (sessions present)
                        // is intentionally NOT gated, so users with history still see
                        // it immediately without waiting on the config decode.
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasMainSlot = hasMainSlot,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        // [T-android-newchat-list-autoscroll] Hoisted state so
                        // the new-session autoscroll effect above can drive it.
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Leave space for bottom FAB row
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {
                        // T25: search-active path used to flatten the list and skip
                        // section headers entirely. Now reuses the same grouped
                        // rendering — `displayedSessions` is already filtered by
                        // the VM when active && query.isNotBlank, so the same
                        // groupSessionsByDate(sessions) computation produces
                        // header buckets over the filtered set.
                        // [T-android-session-grouping] Assembly: Pinned →
                        // groups → date buckets — pinnedFirst / leading /
                        // trailing are hoisted above the LazyColumn so the
                        // accordion anchor + mini-bar share them; keep the
                        // builder and folderHeaderIndices in lockstep.

                        // [T-android-session-grouping] Membership is "points at a
                        // group that exists", matching partitionByFolder. Hoisted
                        // out of the row so it is not rebuilt per item.
                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionRows(
                            rows: List<ChatSessionEntity>,
                            // [T-android-folder-card-ios-parity] Folder members
                            // render as MIDDLE/BOTTOM segments of the group's
                            // welded container (iOS FolderMemberRowBackground);
                            // ungrouped rows stay full-bleed.
                            inFolder: Boolean = false,
                        ) {
                            items(rows, key = { it.id }) { session ->
                                val activeQuery =
                                    if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                                val rowModifier = if (inFolder) {
                                    val isLast = session.id == rows.last().id
                                    Modifier
                                        .padding(
                                            start = 6.dp, end = 6.dp,
                                            bottom = if (isLast) 4.dp else 0.dp,
                                        )
                                        .folderSurface(
                                            segment = if (isLast) FolderSegment.BOTTOM
                                            else FolderSegment.MIDDLE,
                                            fill = folderFillColor(),
                                            edge = folderEdgeColor(),
                                        )
                                        // AFTER folderSurface so the drawn
                                        // fill/border stay outside the clip;
                                        // inside it, the row's press ripple is
                                        // shaped to the segment — square for
                                        // middles, bottom-rounded on the last
                                        // row so the highlight can't poke out
                                        // of the container's corners.
                                        .clip(
                                            if (isLast) {
                                                RoundedCornerShape(
                                                    bottomStart = 16.dp, bottomEnd = 16.dp,
                                                )
                                            } else {
                                                RoundedCornerShape(0.dp)
                                            },
                                        )
                                } else {
                                    Modifier
                                }
                                // animateItem gives the accordion its motion:
                                // member rows fade+slide over 250ms instead of
                                // popping — the iOS easeInOut(0.25) equivalent
                                // (monotonic tween on purpose; a spring's
                                // oscillation read as jitter on iOS).
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .then(rowModifier),
                                ) {
                                SessionItemContent(
                                    session = session,
                                    isSelecting = isSelecting,
                                    selectedIds = selectedIds,
                                    onSessionClick = onSessionClickGuarded,
                                    onToggleSelect = { viewModel.toggleSelect(it) },
                                    onEnterSelect = { viewModel.enterSelection(it) },
                                    onPinToggle = { viewModel.togglePin(it) },
                                    onEditRequest = { editSession = it },
                                   onExportRequest = { s, fmt ->
                                       exportSession(context, s, chatRepository, scope, fmt)
                                   },
                                    onCharacterRequest = { characterTarget = it },
                                    onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                    onDuplicate = { viewModel.duplicateSession(it) },
                                    onDeleteRequest = { id ->
                                        deleteTargetId = id
                                        showDeleteDialog = true
                                    },
                                    onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                    isFiled = session.folderId != null &&
                                        session.folderId in existingFolderIds,
                                    isRegenerating = session.id in regeneratingIds,
                                    searchQuery = activeQuery,
                                    searchSnippet = searchSnippets[session.id],
                                    // Transparent so the folder container's
                                    // surface shows through member rows.
                                    rowBackground = when {
                                        session.id == selectedSessionId ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        inFolder -> Color.Transparent
                                        else -> null
                                    },
                                )
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionRows(periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                    FolderCard(
                                        block = block,
                                        onToggle = {
                                            // Capture BEFORE the toggle — after it
                                            // the block still holds the old state.
                                            val willExpand = block.isCollapsed
                                            viewModel.toggleFolderCollapsed(block.folder.id)
                                            if (willExpand) {
                                                pendingExpandFolderId = block.folder.id
                                            }
                                        },
                                        onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                        onRename = { folderDialogs.rename = block.folder },
                                        onDissolve = { folderDialogs.dissolve = block.folder },
                                        onNewChatInGroup = {
                                            // iOS newChatInFolder: auto-expand
                                            // first so the new session doesn't
                                            // vanish into a collapsed group.
                                            if (block.isCollapsed) {
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                            }
                                            val sessionId = viewModel.createNewSession(
                                                folderId = block.folder.id,
                                            )
                                            if (sessionId != null) onNewChatGuarded(sessionId)
                                        },
                                        onDeleteWithSessions = {
                                            folderDialogs.delete = block.folder to block.totalCount
                                        },
                                    )
                                    }
                                }
                                // Collapsed groups contribute no rows; the card
                                // still reports the real member count.
                                renderSessionRows(
                                    block.ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
                                    inFolder = true,
                                )
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.YESTERDAY -> R.string.sessionlist_section_yesterday
                                    DatePeriod.THIS_WEEK -> R.string.sessionlist_section_this_week
                                    DatePeriod.THIS_MONTH -> R.string.sessionlist_section_this_month
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionRows(periodSessions)
                        }
                    }
                }
            }

            // [T-android-folder-minibar] Floating quick-nav capsule (iOS
            // folderMiniBar): appears when the expanded group's header scrolls
            // off the top. Two interaction zones — icon+name jumps back to the
            // header, the circled chevron collapses the group. Split on
            // purpose: whole-bar-collapses made "where am I" and "close this"
            // the same target.
            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(start = 10.dp, end = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = headerIdx != null) {
                                        scope.launch {
                                            headerIdx?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                            ) {
                                FolderComposedIcon(
                                    category = bar.firstCategory,
                                    diameter = 30.dp,
                                )
                                // Folder names are user data — verbatim.
                                Text(
                                    bar.folder.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                    .clickable {
                                        viewModel.toggleFolderCollapsed(bar.folder.id)
                                    },
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.group_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom area: dual FABs or selection toolbar (matching iOS fabRow / selectionToolbar)
            if (isSelecting) {
                // Selection toolbar at bottom (matching iOS: Export + Delete)
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onExport = { exportSessions(context, selectedIds.toList(), chatRepository, scope) },
                    onMove = { viewModel.requestGroupPickerForSelection() },
                    onDelete = { showBulkDeleteDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {
                // Dual FAB row (matching iOS: New Chat left + Search right, or vice versa).
                // Hidden while the onboarding landing is showing — Step 3 provides the CTA.
                // T46: stay visible while search is active even when the result
                // set is empty, so the user can edit / clear the query without
                // having to rediscover the search FAB after a 0-hit query.
                DualFabRow(
                    isDark = isDark,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    isSearching = isSearching,
                    hasSessions = sessions.isNotEmpty() || isSearchActive,
                    onNewChat = {
                        scope.launch {
                            val sessionId = viewModel.createNewSession()
                            if (sessionId != null) onNewChatGuarded(sessionId)
                        }
                    },
                    onSearchToggle = {
                        if (isSearchActive) {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        } else {
                            viewModel.isSearchActive.value = true
                        }
                    },
                    onSearchQueryChange = { viewModel.searchQuery.value = it },
                    onSearchDismiss = {
                        viewModel.searchQuery.value = ""
                        viewModel.isSearchActive.value = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // Single delete confirmation
    // [T-eta-character-cards] Binding a character to a session is a picker, not a new screen:
    // the choice is one row of one session.
    characterTarget?.let { target ->
        com.openminis.app.ui.settings.CharacterPickerDialog(
            sessionId = target.id,
            onDismiss = { characterTarget = null },
        )
    }

    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    // Bulk delete confirmation
    if (showBulkDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, selectedIds.size),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────
    // [T-android-session-grouping]

    groupPickerRequest?.let { request ->
        // [T-android-group-ai-suggest] Suggestion state lives on the VM, not
        // in the sheet, so an in-flight request survives recomposition (and
        // the sheet's own remembered state being torn down).
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

    FolderActionDialogs(
        state = folderDialogs,
        memberCounts = folderMemberCounts,
        onRename = { id, name, desc -> viewModel.renameFolder(id, name, desc) },
        onDissolve = { viewModel.dissolveFolder(it) },
        onDeleteWithSessions = { viewModel.deleteFolderWithSessions(it) },
    )

    // Edit Title & Category sheet (matching iOS SessionEditSheet)
    editSession?.let { session ->
        // Track the live DB-backed row for this session so a Regenerate-Title
        // run (which writes title/category to the DB) flows back into the sheet
        // without the user reopening it. displayedSessions observes the DB.
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

    // Browser sheet
    if (showBrowserSheet) {
        com.openminis.app.ui.browser.BrowserSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSheet = false },
        )
    }

    // Browser Settings sheet
    if (showBrowserSettings) {
        com.openminis.app.ui.browser.BrowserSettingsSheet(
            tabPool = browserTabPool,
            onDismiss = { showBrowserSettings = false },
        )
    }
}





















