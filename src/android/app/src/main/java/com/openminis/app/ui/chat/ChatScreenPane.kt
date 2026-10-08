package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.m3.Markdown
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.LocalChatPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.ChatMessagePane(
    sessionId: kotlin.String,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    onOpenSetupStep: kotlin.Function1<kotlin.Int, kotlin.Unit>,
    onOpenTerminalWithCommand: kotlin.Function1<kotlin.String, kotlin.Unit>,
    onMoveToSession: kotlin.Function1<kotlin.String, kotlin.Unit>,
    onPreviewAttachment: kotlin.Function1<com.openminis.app.ui.sandbox.FileItem, kotlin.Unit>,
    context: android.content.Context,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messages_st: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    hasOlderMessages_st: androidx.compose.runtime.State<kotlin.Boolean>,
    isStreaming_st: androidx.compose.runtime.State<kotlin.Boolean>,
    isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>,
    generatingMessageId_st: androidx.compose.runtime.State<kotlin.String?>,
    replySpeechState_st: androidx.compose.runtime.State<com.openminis.app.speech.ReplySpeechState>,
    replySpeechStateState: androidx.compose.runtime.State<com.openminis.app.speech.ReplySpeechState>,
    canResume_st: androidx.compose.runtime.State<kotlin.Boolean>,
    compactProgress_st: androidx.compose.runtime.State<com.openminis.app.ui.chat.ChatViewModel.CompactProgress?>,
    error_st: androidx.compose.runtime.State<kotlin.String?>,
    sessionTitle_st: androidx.compose.runtime.State<kotlin.String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    inputText_st: androidx.compose.runtime.State<kotlin.String>,
    inputTextState: androidx.compose.runtime.State<kotlin.String>,
    lastUserAppendMs_st: androidx.compose.runtime.MutableState<kotlin.Long>,
    lastUserAppendMsState: androidx.compose.runtime.MutableState<kotlin.Long>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    showAttachMenu_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    deleteSingleMessageTargetId_st: androidx.compose.runtime.MutableState<kotlin.String?>,
    regenerateTarget_st: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.String, kotlin.Int>?>,
    messageMenuTargetState: androidx.compose.runtime.MutableState<kotlin.String?>,
    deleteFromHereTargetId_st: androidx.compose.runtime.MutableState<kotlin.String?>,
    compactAboveTargetId_st: androidx.compose.runtime.MutableState<kotlin.String?>,
    tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit,
    isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>,
    contentOverflows: androidx.compose.runtime.State<kotlin.Boolean>,
    userScrolledAway_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    lastJumpedUserId_st: androidx.compose.runtime.MutableState<kotlin.String?>,
    lastInterruptMs_st: androidx.compose.runtime.MutableState<kotlin.Long>,
    toolStatusBarEnabledState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    previewImageGallery_st: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.collections.List<com.openminis.app.ui.components.ImageGalleryItem>, kotlin.Int>?>,
    urlClickHandler: kotlin.Function1<kotlin.String, kotlin.Unit>,
    chatPaneWidthPxState: androidx.compose.runtime.MutableState<kotlin.Int>,
) {
    val messages by messagesState
    val isStreaming by isStreamingState
    val replySpeechState by replySpeechStateState
    val inputText by inputTextState
    var lastUserAppendMs by lastUserAppendMsState
    var messageMenuTarget by messageMenuTargetState
    var userScrolledAway by userScrolledAwayState
    var toolStatusBarEnabled by toolStatusBarEnabledState
    var chatPaneWidthPx by chatPaneWidthPxState
    Box(modifier = Modifier.weight(1f)) {
        CompositionLocalProvider(LocalChatPalette provides LocalChatPalette.current) {
        // Empty conversation: greeting + quick actions (board). Held back briefly so an
        // existing session whose history is still loading does not flash it.
        var emptyStateSettled by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(sessionId) {
            kotlinx.coroutines.delay(400)
            emptyStateSettled = true
        }
        if (emptyStateSettled && messages.isEmpty() && !isStreaming) {
            val setupConfig by providerRepository.config.collectAsState()
            val activeEntry by viewModel.activeEntryId.collectAsState()
            val providerDone = setupConfig.instances.any { it.isEnabled } && setupConfig.modelEntries.isNotEmpty()
            val modelDone = activeEntry != null || setupConfig.slots.main.isNotEmpty()
            // zIndex: the (empty) message list is declared after this and its gesture handlers
            // would otherwise block every tap on the cards, Shuffle and Edit.
            ChatEmptyState(
                modifier = Modifier.zIndex(1f),
                onPick = { viewModel.sendMessage(it) },
                setup = if (providerDone && modelDone) null else FirstRunSetup(
                    providerDone = providerDone,
                    modelDone = modelDone,
                    onOpenProvider = { onOpenSetupStep(1) },
                    onOpenModel = { onOpenSetupStep(2) },
                ),
            )
        }
        var toolBarHeightPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val toolBarHeightDp = with(density) { toolBarHeightPx.toDp() }
        // T166 / T170 / T173: bottomReserve must clear the visible
        // top of the floating tool-status overlay. Layout primitives
        // come from FloatingToolStatusBar:
        //   - status bar height = 38 dp
        //   - thumbnail floats over the bar with overhang = 27 dp
        //   - thumbnail TOP = bar top - overhang = 65 dp above the
        //     input bar's upper edge (which is also the LazyColumn
        //     bottom edge under reverseLayout).
        //
        // `onGloballyPositioned` on the wrapper Box reports ~98 dp
        // because it includes wrapper padding(bottom=6) + horizontal
        // padding insets + shadow allowance — none of which are
        // *visually occluding* the LazyColumn. Using the measured
        // value + 8 dp left a ~25 dp gap above the thumbnail (red
        // box in the user's report).
        //
        // Pin to the visual constant: thumbnail height (65 dp) + a
        // visual buffer (18 dp) so the latest row's bottom has clear
        // breathing room above the thumbnail top.
        //
        // T174: an earlier version gated reserve on `toolBarHeightPx
        // > 0`, but `onGloballyPositioned` fires asynchronously after
        // the first floating-bar layout pass; for one frame after
        // toolBlocks appeared the reserve evaluated the small
        // default (28 dp) and the just-arrived user bubble landed
        // beneath the bar. Logcat showed the inverse glitch too:
        // `toolBarHeightPx=258 toolBarHeightDp=0 reserve=28` — the
        // px state and the dp/reserve values come from different
        // recomposition snapshots. Drive the reserve directly off
        // the same predicate used for *whether* the floating bar is
        // emitted (`hasFloatingTools` below) so reserve and bar
        // visibility flip on the same frame.
        // [T-android-chat-cannot-scroll-bottom-many-tools]
        // Bug 𝙓𝙄𝙉 TG36286: with 7+ tools the user couldn't scroll the
        // last messages above the floating tool status bar.
        //
        // Asymmetry between the bar's render condition and its
        // bottomReserve gate: [lastToolBlocks] (drives whether to
        // mount FloatingToolStatusBar) merges `messages` with the
        // streaming-side-channel `streamingById`, so during a live
        // turn the in-flight tool's toolStatus shows up there →
        // bar renders. [hasFloatingTools] (drives bottomReserve)
        // only read `messages`, which the streaming architecture
        // intentionally leaves stable during a turn — so the
        // in-flight tool is invisible to this predicate → reserve
        // collapsed to 20dp while a 65dp+6dp floating bar covered
        // the bottom of the LazyColumn. The new arrivals (status
        // pill, "Minis is thinking" indicator, inline retry banner) landed
        // behind the bar with no way to scroll them into view.
        //
        // Fix: also subscribe to streamingById so the predicate
        // matches the bar's actual mount condition. The bar's
        // mount uses `lastToolBlocks.isNotEmpty()` over the merged
        // view; we mirror that semantically by checking the same
        // filter on both sources.
        val streamingById_st = viewModel.streamingById.collectAsState()
        val streamingById by streamingById_st
        val hasFloatingTools = remember(messages, streamingById, toolStatusBarEnabled) {
            if (!toolStatusBarEnabled) return@remember false
            val merged = if (streamingById.isEmpty()) messages
                         else mergeStreamingOverlay(messages, streamingById)
            merged.any { msg ->
                msg.role == "assistant" && msg.toolBlocks.any { tb ->
                    tb.toolStatus != null && tb.kind != "thinking" && tb.kind != "info"
                }
            }
        }
        val visualOverlayHeight = 65.dp  // thumbnailHeight in FloatingToolStatusBar
        // Halve the breathing room above the input bar in both
        // states — felt too sparse before. The thumbnail's 65dp
        // physical height is preserved (it has to clear the
        // floating overlay).
        //
        // T245: buffer raised 9dp → 14dp so the gap between the
        // last LazyColumn tool row and the floating thumbnail's
        // top reads at least as loose as the inter-tool spacing
        // (each ToolCallPill carries padding(vertical = 3.dp) +
        // LazyColumn spacedBy(2.dp) = ~8dp inter-tool gap; the
        // 9dp buffer combined with the floating bar's internal
        // overhang was visually tighter than 8dp). 14dp also
        // matches the no-tool branch — single visual constant
        // for "row-bottom → bottom chrome" breathing room.
        // [T-bottom-occluded 0a6d3c92] No-tools branch bumped from
        // 14dp → 20dp to give the last message bubble a comfortable
        // gap above the composer's top edge. With 14dp the trailing
        // line sat too close to the composer shadow / rounded edge
        // (user reported "the bottom of the text is slightly clipped"). The floating-tools branch
        // already reserves visualOverlayHeight (65dp) + buffer and
        // was not part of the report; keep its +14 buffer.
        val bottomReserve =
            if (hasFloatingTools) visualOverlayHeight + 34.dp else 20.dp
        // T174: when bottomReserve changes (toolbar appearing /
        // disappearing or thumbnail height shift), re-pin to bottom
        // if we are currently following. Without this, the new
        // contentPadding is honoured for layout but reverseLayout
        // won't actively scroll the list — items can wind up below
        // the viewport's new bottom edge until the next streaming
        // delta triggers a follow. iOS does the same in V3:54-68
        // when contentInset.bottom changes.
        LaunchedEffect(bottomReserve) {
            // [T-android-send-no-autoscroll-behind-preview] Send-grace
            // bypass: within SEND_FOLLOW_GRACE_MS of a user message
            // append the isNearBottom gate is waived — the user JUST
            // sent (userScrolledAway was explicitly reset), but the
            // freshly-inserted user/thinking rows leave the live
            // anchor transiently "not at bottom", which used to skip
            // this pin and strand the new rows behind the floating
            // tool bar when the reserve grew. All gates stay in force
            // outside the grace window, so the C2 stream-end
            // protections are untouched (a stream end is never
            // within 2s of the user's send).
            val sinceSendMs = System.currentTimeMillis() - lastUserAppendMs
            val sendGrace = lastUserAppendMs > 0L && sinceSendMs in 0..SEND_FOLLOW_GRACE_MS
            if (!userScrolledAway && (isNearBottom.value || sendGrace)) {
                val reason = if (!isNearBottom.value) "send-grace" else "near-bottom"
                tracedScrollToItem("reserve-change/$reason", 0, 0)
            }
        }
        // T120: removed three streaming-time LaunchedEffects that
        // each called scrollToItem(0) on every chunk:
        //   - LE(bottomReserve): toolbar resize re-snap
        //   - LE(lastToolCount): per-tool-pill follow
        //   - LE(lastAwaiting):  thinking-indicator follow
        // They fired several times per second during streaming and
        // turned every layout pass into a scroll command, fighting
        // reverseLayout's native bottom-anchor behavior. With
        // reverseLayout=true Compose already keeps the visual
        // bottom pinned when the list is at offset 0; if the user
        // scrolled away, the JumpToBottom FAB is the explicit
        // affordance to return.

        // Flatten each message into multiple LazyColumn items so that older blocks
        // (text / tool pills / thinking) are frozen LazyList items while only the
        // last streaming block changes height. This keeps scroll-hovering stable:
        // LazyListState anchors on a stable item key + pixel offset, and inserting
        // or growing the trailing item never disturbs earlier items.
        //
        // T94: long sessions (hundreds of messages, deep tool chains) made the
        // flatten step expensive enough to stall composition on the main thread —
        // every streaming token recomposed the parent and re-ran the O(N · blocks)
        // walk inside `remember`, producing visible jank and ANRs on slower
        // devices. Run the flatten on Dispatchers.Default and publish the result
        // through a snapshot-state field so the LazyColumn renders the previous
        // frame's list while the next one computes. Keyed on `sessionId` so a
        // chat-switch resets the cache; LaunchedEffect(messages) reruns the
        // computation on every new emission.
        val flatItems_st = remember(sessionId) {
            mutableStateOf<List<FlatChatItem>>(emptyList())
        }
        var flatItems by flatItems_st
        // [T-android-coldload-offmain-parse] Composition-snapshot
        // prewarmer (captures the markdown palette) used by the
        // flatten effect below to warm the parse caches for the
        // viewport-candidate fragments off-main.
        val prewarmMarkdown = rememberMarkdownPrewarmer()
        // [T-android-jank-diag-logging] Cold-open one-line summary
        // state: emitted ONCE per session open at the first
        // firstItem.placed; prewarmMs is filled by the parallel
        // prewarm when (if) it has finished by then, else -1.
        val lastColdPrewarmMs_st = remember(sessionId) { mutableStateOf(-1L) }
        var lastColdPrewarmMs by lastColdPrewarmMs_st
        val coldOpenSummaryEmitted_st = remember(sessionId) { mutableStateOf(false) }
        var coldOpenSummaryEmitted by coldOpenSummaryEmitted_st
        val screenMountAtMs = remember(sessionId) { System.currentTimeMillis() }
        // [T-android-work-process] Presentation mode + the chat's
        // Deep Thinking level. They decide whether the flatten folds
        // each run of thinking / tool blocks into one WorkProcessRow,
        // and whether a hidden thinking block exists at all. Collected
        // rather than read once so flipping the setting in Appearance
        // re-flattens instead of waiting for the next session.
        val stepsPresentationState_st =
            com.openminis.app.data.StepsPresentationPrefs.value.collectAsState()
        val stepsPresentationState by stepsPresentationState_st
        val flattenThinkingEnabled =
            viewModel.thinkingLevel.collectAsState().value.isEnabled
        // T-streaming-side-channel: messages-level changes (new
        // message, retry, etc.) AND streamingById deltas both feed
        // buildFlatChatItems, but we subscribe to streamingById
        // INSIDE LaunchedEffect (not at top-level) so per-token
        // emissions don't recompose the surrounding ChatScreen
        // scope. The flatten still runs per token (cheap-ish; ran
        // before too), but the rebuild stays off the main UI
        // composable's invalidation list.
        FlatItemsBuildEffect(
            sessionId = sessionId,
            viewModel = viewModel,
            messagesState = messages_st,
            streamingByIdState = streamingById_st,
            flatItemsState = flatItems_st,
            prewarmMarkdown = prewarmMarkdown,
            lastColdPrewarmMsState = lastColdPrewarmMs_st,
            stepsPresentationStateState = stepsPresentationState_st,
            flattenThinkingEnabled = flattenThinkingEnabled,
        )
        // T304: when a new tool-use item appears at the trailing
        // edge (head of flatItems with reverseLayout=true), pin
        // back to the bottom so the just-arrived tool card is
        // visible above the floating Computer overlay + composer.
        //
        // The streaming-content snapshotFlow (LE around L798) does
        // detect `m.toolBlocks.size` growth, but it fires on the
        // raw `messages` model — and the LazyColumn renders the
        // async-flattened `flatItems`. The scroll can run BEFORE
        // flatItems repopulates with the new tool item, so item 0
        // is still the previous trailing item; the new tool block
        // ends up appended below the visible viewport. Pinning
        // again keyed on `flatItems` head fixes the race without
        // disturbing T281/T282 (those still own user-send and
        // resume scroll). userScrolledAway is honoured so users
        // reading history aren't yanked back.
        // [T-android-send-no-autoscroll-behind-preview] Key of the
        // trailing tool/typing row we last pinned for — dedupes the
        // pin to once per new row across flatten publishes.
        val lastTrailingPinKey_st = remember(sessionId) { mutableStateOf<String?>(null) }
        var lastTrailingPinKey by lastTrailingPinKey_st
        TrailingPinEffect(
            viewModel = viewModel,
            messagesState = messages_st,
            listState = listState,
            lastUserAppendMsState = lastUserAppendMs_st,
            tracedScrollToItem = tracedScrollToItem,
            userScrolledAwayState = userScrolledAway_st,
            lastInterruptMsState = lastInterruptMs_st,
            hasFloatingTools = hasFloatingTools,
            bottomReserve = bottomReserve,
            flatItemsState = flatItems_st,
            lastTrailingPinKeyState = lastTrailingPinKey_st,
        )
        // messageId → isCompactedHistory map. Used to fade entire
        // assistant-row clusters (header + text + tool pills) at
        // render time — mirrors iOS isCompactedHistory opacity(0.5).
        // The lookup uses the underlying message id stripped of any
        // dedupe suffix (`id#2`) added by buildFlatChatItems.
        val grayedMap = remember(messages) {
            messages.associate { it.id to it.isCompactedHistory }
        }
         // SelectionContainer must wrap the WHOLE LazyColumn — placing
        // it per-item breaks long-press because items get disposed
        // when scrolled out and the selection registrar/detector goes
        // with them. One outer SelectionContainer registers each Text
        // child as it enters composition, and the long-press gesture
        // detector lives at this stable scope. Mirrors Compose's
        // recommended LazyColumn + selection pattern.
        //
        // The custom LocalTextToolbar replaces the system Copy bar
        // with a 3-button popup (Copy / Copy Markdown / Copy Rich
        // Text); the latter two read from the bounds registry which
        // each AssistantMessageView updates via onGloballyPositioned.
        val messageBounds = remember { MessageBoundsRegistry() }
        // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
        // action funnels the selected substring back into the
        // composer via the same StateFlow that the TextField is
        // bound to. Capture `viewModel` by reference so the
        // toolbar instance survives recomposition without
        // re-creation.
        // [T-add-to-input-focus] After append, request focus on the
        // composer + pop the soft keyboard so the user can keep
        // typing without an extra tap. Keyboard `show()` is best-effort
        // (controller may be null pre-attach); focus is guarded against
        // FocusRequester-not-attached the same way the auto-focus path
        // elsewhere in this file is.
        // MinisTextKit selection controller — declared BEFORE the
        // markdown toolbar so the toolbar can read table actions off it
        // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
        // LazyColumn so item dispose can't kill the selection: when a
        // shard scrolls out of viewport it deregisters its TextShard,
        // but the (messageId, shardId, charOffset) endpoints stay valid;
        // scrolling back in re-registers the shard and the highlight
        // redraws automatically.
        val selectionController = remember { SelectionController() }
        val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController) {
            MinisMarkdownTextToolbar(
                context = context,
                registry = messageBounds,
                onAddToInput = { snippet ->
                    viewModel.appendToInputText(snippet)
                    try {
                        inputFocusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // FocusRequester not yet attached — composer
                        // will gain focus on next user tap.
                    }
                    keyboardController?.show()
                },
                 onReadAloud = { snippet -> viewModel.readSelectionAloud(snippet) },
                  onReadFromStart = { fullText -> viewModel.readSelectionAloud(fullText) },
                  isStreamingNow = { viewModel.isStreaming.value },
                 selectionController = selectionController,
            )
        }
        // Wrap any callback that truncates / replaces / removes rows from
        // the message list. Hiding the toolbar + clearing focus tears down
        // the SelectionManager's pending toolbar update before the
        // SelectionContainer subtree gets reshuffled — without this,
        // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
        // → sort hits stale LayoutCoordinates and crashes with
        // "layouts are not part of the same hierarchy".
        val safeMutate: (() -> Unit) -> Unit = { block ->
            markdownToolbar.hide()
            focusManager.clearFocus()
            block()
        }
        // Hoist slash-menu state up so the LazyColumn pointerInput
        // tap-spy below can react to it. The popup itself, declared
        // further down near the composer, reads viewModel.showSlashMenu
        // again — both subscriptions snap to the same StateFlow.
        val slashMenuOpen_st = viewModel.showSlashMenu.collectAsState()
        val slashMenuOpen by slashMenuOpen_st
        // T4: mirror state hoist for the mention picker so the chat-list
        // tap-spy can dismiss it the same way as the slash popup.
        val mentionMenuOpenForSpy_st = viewModel.showMentionMenu.collectAsState()
        val mentionMenuOpenForSpy by mentionMenuOpenForSpy_st
        // Intercept back press to dismiss slash/mention menus before
        // navigating away from the chat screen.
        androidx.activity.compose.BackHandler(
            enabled = slashMenuOpen || mentionMenuOpenForSpy
        ) {
            if (slashMenuOpen) {
                viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
            }
            if (mentionMenuOpenForSpy) {
                viewModel.dismissMentionMenu()
            }
        }
        // (selectionController declared above, before markdownToolbar.)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalMessageBoundsRegistry provides messageBounds,
            androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
            LocalMinisSelectionController provides selectionController,
        ) {
        // Hoisted out of AlwaysStretchOverscrollBox lambda so
        // SelectionDragTracker (which lives outside the lambda) can
        // read the LazyColumn's window-space root coords for edge
        // auto-scroll calculations.
        val listRootCoords_st = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        var listRootCoords by listRootCoords_st
        // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
        // fires once per session when the LazyColumn first reports
        // a layout. Combined with `buildFlatChatItems.firstBuild`
        // (above) and `lazyColumn.firstItem.placed` (below) this
        // tells us whether the bottleneck is row-list build,
        // initial list measure, or per-row composition.
        val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
        Box {
        AlwaysStretchOverscrollBox { sharedEffect ->
        val lastAssistantMessageId = remember(messages) { messages.lastOrNull { it.role == "assistant" }?.id }
        // What a reply can do. The newest reply's action row and every reply's long-press menu share it.
        val actionsFor = rememberActionsFor(
            onMoveToSession = onMoveToSession,
            context = context,
            viewModel = viewModel,
            messagesState = messages_st,
            sessionTitleState = sessionTitle_st,
            deleteSingleMessageTargetIdState = deleteSingleMessageTargetId_st,
            regenerateTargetState = regenerateTarget_st,
            selectionController = selectionController,
        )
        messageMenuTarget?.let { targetId ->
            val message = messages.firstOrNull { it.id == targetId }
            if (message == null) {
                messageMenuTarget = null
            } else {
                val markdown = message.toolBlocks.filter { it.kind == "text" && it.content.isNotEmpty() }
                    .joinToString("\n\n") { it.content }.ifEmpty { message.content }
                // The reply's token usage, read when the menu opens (null until it arrives or when there is none).
                val replyUsage by androidx.compose.runtime.produceState<ReplyUsage?>(null, targetId) {
                    value = viewModel.replyUsage(targetId)
                }
                AssistantMessageMenu(
                    previewText = MarkdownClipboard.markdownToPlainText(markdown),
                    usage = replyUsage,
                    isSpeaking = replySpeechState.activeMessageId == targetId &&
                        replySpeechState.status == com.openminis.app.speech.ReplySpeechState.Status.READING,
                    actions = actionsFor(targetId, markdown),
                    onDismiss = { messageMenuTarget = null },
                )
            }
        }
        ChatMessageList(
            grayedMap = grayedMap,
            sessionId = sessionId,
            onOpenTerminalWithCommand = onOpenTerminalWithCommand,
            onPreviewAttachment = onPreviewAttachment,
            context = context,
            keyboardController = keyboardController,
            focusManager = focusManager,
            viewModel = viewModel,
            messagesState = messages_st,
            hasOlderMessagesState = hasOlderMessages_st,
            isStreamingState = isStreaming_st,
            generatingMessageIdState = generatingMessageId_st,
            replySpeechStateState = replySpeechState_st,
            canResumeState = canResume_st,
            compactProgressState = compactProgress_st,
            errorState = error_st,
            listState = listState,
            inputTextState = inputText_st,
            inputFocusRequester = inputFocusRequester,
            coroutineScope = coroutineScope,
            showAttachMenuState = showAttachMenu_st,
            deleteFromHereTargetIdState = deleteFromHereTargetId_st,
            compactAboveTargetIdState = compactAboveTargetId_st,
            tracedScrollToItem = tracedScrollToItem,
            userScrolledAwayState = userScrolledAway_st,
            previewImageGalleryState = previewImageGallery_st,
            urlClickHandler = urlClickHandler,
            bottomReserve = bottomReserve,
            flatItemsState = flatItems_st,
            lastColdPrewarmMsState = lastColdPrewarmMs_st,
            coldOpenSummaryEmittedState = coldOpenSummaryEmitted_st,
            screenMountAtMs = screenMountAtMs,
            selectionController = selectionController,
            safeMutate = safeMutate,
            slashMenuOpenState = slashMenuOpen_st,
            mentionMenuOpenForSpyState = mentionMenuOpenForSpy_st,
            listRootCoordsState = listRootCoords_st,
            perfFirstLayoutFired = perfFirstLayoutFired,
            lastAssistantMessageId = lastAssistantMessageId,
            actionsFor = actionsFor,
            sharedEffect = sharedEffect,
        )
        } // AlwaysStretchOverscrollBox
        // SelectionDragTracker bridges gesture-published dragIntent
        // with listState scroll observation — that's what keeps the
        // selection extending across newly-scrolled-in shards when
        // the user's finger is stationary in the edge auto-scroll
        // zone (the inline pointer loop can't see those because
        // it only fires on pointer events).
        SelectionDragTracker(
            controller = selectionController,
            listState = listState,
            listRootCoordinates = { listRootCoords },
            reverseLayout = true,
        )
        MinisMarkdownTextToolbarHost(markdownToolbar)
        // MinisTextKit floating toolbar — driven by selectionController.
        SelectionToolbarOverlay(
            keyboardController = keyboardController,
            viewModel = viewModel,
            inputFocusRequester = inputFocusRequester,
            selectionController = selectionController,
            listRootCoordsState = listRootCoords_st,
        )
        // iOS-style selection handle dots, one at each endpoint.
        MinisSelectionHandlesHost(
            controller = selectionController,
            listState = listState,
            reverseLayout = true,
        )
        } // Box (selection scope)
        } // CompositionLocalProvider

        // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
        // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
        //
        // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
        // from a state that combines messages + streamingById INSIDE
        // a LaunchedEffect (not via a top-level collectAsState read),
        // so streaming-tick churn stays off the ChatScreen invalidation
        // list. Without including streamingById, a tool pill clicked
        // mid-turn is missing from lastToolBlocks → ToolDetailSheet
        // never opens (and its sentinel LaunchedEffect immediately
        // closes the detail state because the id "doesn't exist").
        val lastToolBlocks_st = remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
        var lastToolBlocks by lastToolBlocks_st
        LaunchedEffect(messages) {
            kotlinx.coroutines.flow.combine(
                kotlinx.coroutines.flow.flowOf(messages),
                viewModel.streamingById,
            ) { msgs, stream ->
                val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                merged.filter { it.role == "assistant" }
                    .flatMap { it.toolBlocks }
                    .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }
            }.collect { lastToolBlocks = it }
        }
        val allToolBlocks = lastToolBlocks
        AgentStateBars(
            sessionId = sessionId,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp),
        )
       // [T-android-tool-status-bar-toggle] The switch turns the whole strip off, not just
       // its thumbnail: a user who does not want a running command pinned above the
       // composer should not have to keep the row that reports it.
       if (lastToolBlocks.isNotEmpty() && toolStatusBarEnabled) {
           Box(
               modifier = Modifier
                   .align(Alignment.BottomCenter)
                    .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
                   .fillMaxWidth()
                   .onGloballyPositioned { toolBarHeightPx = it.size.height }
                   .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
            ) {
                FloatingToolStatusBar(
                    toolBlocks = lastToolBlocks,
                    // T14: per-card stop on the floating bar — same
                    // global cancel as the message-list pill button.
                    onStop = { viewModel.cancelStream() },
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    // T261: route detail open through the same VM
                    // state as in-list pills so both surfaces share
                    // one always-mounted sheet instance.
                    onOpenDetail = { viewModel.openToolDetail(it) },
                )
            }
        } else {
            SideEffect { toolBarHeightPx = 0 }
        }

        // T261: tool-detail sheet hoisted out of LazyColumn item
        // scope. Visibility driven by ViewModel state so streaming /
        // pill-disposal / new-tool emissions can't snap it shut.
        // existence guard auto-closes the sheet when the underlying
        // block disappears (T258 retry-preserve removes in-flight
        // tools, clearChat, etc.). Reuses lastToolBlocks (already
        // computed above) so we don't traverse messages twice.
        val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
        LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
            val id = selectedToolDetailId ?: return@LaunchedEffect
            if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
        }
        val selectedToolBlock = selectedToolDetailId?.let { id ->
            lastToolBlocks.firstOrNull { it.id == id }
        }
        // Stop / Steer / Resume / Open for a sub agent card. Runs through the same tool path the model
        // uses, scoped to this conversation, so the card can only act on this chat's own runs.
        val subAgentCardActions = remember(sessionId) {
            fun act(args: org.json.JSONObject) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching {
                        com.openminis.app.agent.subagents.SubAgents.runtime(context).execute(args.toString(), sessionId)
                    }
                }
            }
            SubAgentCardActions(
                onStop = { id -> act(org.json.JSONObject().put("action", "cancel").put("job_id", id)) },
                onResume = { id -> act(org.json.JSONObject().put("action", "resume").put("job_id", id)) },
                onSteer = { id, msg ->
                    act(org.json.JSONObject().put("action", "steer").put("job_id", id).put("message", msg))
                },
                onOpenSession = { child ->
                    viewModel.closeToolDetail()
                    onMoveToSession(child)
                },
            )
        }
        if (selectedToolBlock != null) {
            val initialIdx = lastToolBlocks
                .indexOfFirst { it.id == selectedToolBlock.id }
                .coerceAtLeast(0)
            ToolDetailSheet(
                toolBlocks = lastToolBlocks,
                initialIndex = initialIdx,
                onDismiss = { viewModel.closeToolDetail() },
                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                onOpenBrowserForUrl = { url ->
                    viewModel.closeToolDetail()
                    viewModel.openBrowserSheetForUrl(url)
                },
                subAgentActions = subAgentCardActions,
            )
        }

        // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
        // T138 phase 2 v3: show on user-scroll intent, not transient
        // layout state. Otherwise the FAB flickers whenever multi-tool
        // emissions briefly bump the bottom item off-screen during
        // re-anchoring.
        //
        // T170: gate also on `contentOverflows` so short sessions
        // (one Q+A on a tall screen) never flash the FAB if an IME
        // animation produces a synthetic drag-stop. iOS gets this
        // for free via `maxOffset > 0`; Compose needs the explicit
        // check.
        // [T-android-scrollbtn-turn-walk] Floating up-button. Visibility
        // is now the SHARED `!isNearBottom` condition (iOS dcdec3c5),
        // replacing the separate isFarFromTop && isFarFromBottom
        // middle-region gate: both floating buttons now appear together
        // on the same signal, which is what the iOS refactor converged
        // on. Sits ABOVE the scroll-to-bottom button (same BottomEnd
        // anchor, extra bottom padding = down-button height 36dp + 10dp
        // spacing). Tapping walks BACK one user turn at a time rather
        // than jumping to the oldest message.
        val fabEndInset = with(LocalDensity.current) {
            val leftover = chatPaneWidthPx.toDp() - CHAT_MAX_CONTENT_WIDTH
            if (leftover > 0.dp) leftover / 2 else 0.dp
        }

        ScrollToBottomFab(
            messagesState = messages_st,
            coroutineScope = coroutineScope,
            tracedScrollToItem = tracedScrollToItem,
            isNearBottom = isNearBottom,
            contentOverflows = contentOverflows,
            userScrolledAwayState = userScrolledAway_st,
            lastJumpedUserIdState = lastJumpedUserId_st,
            lastToolBlocksState = lastToolBlocks_st,
        )

        // T51 / T185: the "Move to…" capsule was previously rendered
        // here, on top of the message list. After the user actually
        // sends the share-injected turn, the capsule was overlapping
        // the user-message bubble area and obscuring attachment chips.
        // Moved to the composer's top-right corner — see the Box
        // overlay around the input Column below, mirroring iOS
        // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

        // T-chat-title-pill: sticky session title overlay. Sits
        // above the LazyColumn (top-center), animates in once the
        } // chat page palette
}
}
