package com.openminis.app.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.glass.glassSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun rememberActionsFor(
    onMoveToSession: kotlin.Function1<kotlin.String, kotlin.Unit>,
    context: android.content.Context,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    sessionTitleState: androidx.compose.runtime.State<kotlin.String>,
    deleteSingleMessageTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    regenerateTargetState: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.String, kotlin.Int>?>,
    selectionController: com.openminis.app.ui.chat.SelectionController,
): kotlin.Function2<kotlin.String, kotlin.String, com.openminis.app.ui.chat.AssistantActionSet> {
    val messages by messagesState
    val sessionTitle by sessionTitleState
    var deleteSingleMessageTargetId by deleteSingleMessageTargetIdState
    var regenerateTarget by regenerateTargetState
    return { messageId, markdown ->
        AssistantActionSet(
            onCopy = {
                val plain = MarkdownClipboard.markdownToPlainText(markdown)
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("assistant", plain))
            },
            onCopyMarkdown = { MarkdownClipboard.copyMarkdown(context, markdown) },
            onSelectText = { selectionController.selectMessage(messageId) },
            onToggleSpeak = {
                val index = messages.filter { it.role == "assistant" }.indexOfFirst { it.id == messageId } + 1
                viewModel.toggleReplySpeech(messageId, index, markdown)
            },
            onRegenerate = {
                // Regenerating cuts the chat back to this reply, so anything after it is asked
                // about first instead of vanishing.
                val later = viewModel.messagesAfter(messageId)
                if (later > 0) {
                    regenerateTarget = messageId to later
                } else if (!viewModel.regenerateAssistantMessage(messageId)) {
                    com.openminis.app.ui.components.MinisToast.show(
                        context,
                        context.getString(R.string.assistant_regenerate_unavailable),
                    )
                }
            },
            onBranch = {
                viewModel.forkSessionAtMessage(messageId) { newId ->
                    com.openminis.app.ui.components.MinisToast.show(context, context.getString(R.string.chat_branch_created))
                    onMoveToSession(newId)
                }
            },
            onShare = {
                val plain = MarkdownClipboard.markdownToPlainText(markdown)
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, plain)
                    putExtra(Intent.EXTRA_TITLE, sessionTitle)
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            },
            onDelete = { deleteSingleMessageTargetId = messageId },
        )
    }
}

@Composable
internal fun ChatMessageList(
    grayedMap: Map<String, Boolean>,
    sessionId: kotlin.String,
    onOpenTerminalWithCommand: kotlin.Function1<kotlin.String, kotlin.Unit>,
    onPreviewAttachment: kotlin.Function1<com.openminis.app.ui.sandbox.FileItem, kotlin.Unit>,
    context: android.content.Context,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    hasOlderMessagesState: androidx.compose.runtime.State<kotlin.Boolean>,
    isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>,
    generatingMessageIdState: androidx.compose.runtime.State<kotlin.String?>,
    replySpeechStateState: androidx.compose.runtime.State<com.openminis.app.speech.ReplySpeechState>,
    canResumeState: androidx.compose.runtime.State<kotlin.Boolean>,
    compactProgressState: androidx.compose.runtime.State<com.openminis.app.ui.chat.ChatViewModel.CompactProgress?>,
    errorState: androidx.compose.runtime.State<kotlin.String?>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    inputTextState: androidx.compose.runtime.State<kotlin.String>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    showAttachMenuState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    deleteFromHereTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    compactAboveTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit,
    userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    previewImageGalleryState: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.collections.List<com.openminis.app.ui.components.ImageGalleryItem>, kotlin.Int>?>,
    urlClickHandler: kotlin.Function1<kotlin.String, kotlin.Unit>,
    bottomReserve: androidx.compose.ui.unit.Dp,
    flatItemsState: androidx.compose.runtime.MutableState<kotlin.collections.List<com.openminis.app.ui.chat.FlatChatItem>>,
    lastColdPrewarmMsState: androidx.compose.runtime.MutableState<kotlin.Long>,
    coldOpenSummaryEmittedState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    screenMountAtMs: kotlin.Long,
    selectionController: com.openminis.app.ui.chat.SelectionController,
    safeMutate: kotlin.Function1<kotlin.Function0<kotlin.Unit>, kotlin.Unit>,
    slashMenuOpenState: androidx.compose.runtime.State<kotlin.Boolean>,
    mentionMenuOpenForSpyState: androidx.compose.runtime.State<kotlin.Boolean>,
    listRootCoordsState: androidx.compose.runtime.MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
    perfFirstLayoutFired: java.util.concurrent.atomic.AtomicBoolean,
    lastAssistantMessageId: kotlin.String?,
    actionsFor: kotlin.Function2<kotlin.String, kotlin.String, com.openminis.app.ui.chat.AssistantActionSet>,
    sharedEffect: androidx.compose.foundation.OverscrollEffect?,
) {
    val messages by messagesState
    val hasOlderMessages by hasOlderMessagesState
    val isStreaming by isStreamingState
    val generatingMessageId by generatingMessageIdState
    val replySpeechState by replySpeechStateState
    val canResume by canResumeState
    val resumeAfterCrash by viewModel.resumeAfterCrash.collectAsState()
    val compactProgress by compactProgressState
    val error by errorState
    val inputText by inputTextState
    var showAttachMenu by showAttachMenuState
    var deleteFromHereTargetId by deleteFromHereTargetIdState
    var compactAboveTargetId by compactAboveTargetIdState
    var userScrolledAway by userScrolledAwayState
    var previewImageGallery by previewImageGalleryState
    var flatItems by flatItemsState
    var lastColdPrewarmMs by lastColdPrewarmMsState
    var coldOpenSummaryEmitted by coldOpenSummaryEmittedState
    val slashMenuOpen by slashMenuOpenState
    val mentionMenuOpenForSpy by mentionMenuOpenForSpyState
    var listRootCoords by listRootCoordsState
    LazyColumn(
        state = listState,
        reverseLayout = true,
        // T30: when no tool status bar is rendered, a small bottom
        // padding keeps the latest message off the composer's
        // top edge so the conversation breathes. Reuses the same
        // bottomReserve when the toolbar is present.
        // Tuned so the visible gap to the composer's outer edge is ~18dp.
        //
        // [T-android-chat-first-message-top-padding] top reduced
        // 12dp → 4dp. Under reverseLayout this top padding sits at
        // the VISUAL top, so the first message's gap below the model
        // title bar was top(12) + the first bubble's own top(4) =
        // 16dp (≈44px @ 440dpi) — looser than needed. 4dp here +
        // the bubble's 4dp = 8dp (≈22px), tighter but still a clear
        // breath under the title bar. Bottom padding and inter-
        // message spacing are untouched.
       contentPadding = PaddingValues(
           top = 4.dp,
           bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
       ),
       modifier = Modifier
           .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
           .padding(horizontal = 20.dp)
           .onGloballyPositioned {
                listRootCoords = it
                if (perfFirstLayoutFired.compareAndSet(false, true)) {
                    val info = listState.layoutInfo
                    com.openminis.app.diagnostics.PerfLongCtx.step(
                        sessionId,
                        "lazyColumn.firstLayout",
                        "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                    )
                }
            }
            .minisTextKitSelectionGesture(
                controller = selectionController,
                listState = listState,
                rootCoordinates = { listRootCoords },
                // Chat LazyColumn is reverseLayout=true; auto-
                // scroll sign needs to flip so dragging toward
                // the bottom edge reveals NEWER messages (lower
                // index) rather than jumping backward.
                reverseLayout = true,
            )
            // T29 dismiss-on-tap spy. Only active while the slash
            // popup is showing. awaitFirstDown(requireUnconsumed=false,
            // pass=Initial) lets us see the tap *before* any child
            // gesture (LazyColumn scroll, message long-press) without
            // consuming it — the gesture continues to its real
            // handler. We close the menu on the very first finger
            // down anywhere inside the chat list, exactly like
            // tapping outside an iOS popover.
            .pointerInput(slashMenuOpen, mentionMenuOpenForSpy, showAttachMenu, messages.isEmpty()) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    // Not while the empty-state cards are showing: hiding the keyboard on
                    // finger-down re-lays the page out under the finger, so the tap on a
                    // card / Shuffle / Edit lands on nothing and never fires.
                    if (messages.isNotEmpty()) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                    showAttachMenu = false
                }
            },
        // T303: anchor items to the visual bottom so a newly
        // streamed tool card / typing indicator that arrives
        // before older items have shifted up still lands inside
        // the visible area. Without this, reverseLayout's
        // default arrangement (anchored to viewportStart) leaves
        // a gap at the bottom of the list when the bottom item
        // is just-inserted with offset=0 — the new card renders
        // behind the composer and `gap = vpEnd - itemBottom`
        // hits ~1300 px while listState still reports
        // firstVisible=0, firstOffset=0 (logged as the "tool on
        // screen but not pushed into view" repro on Pixel 4a).
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Bottom),
        overscrollEffect = sharedEffect,
    ) {
        // T13 Resume banner — placed BEFORE items() so reverseLayout
        // renders it at the visual bottom of the list (just below
        // the last assistant message). Mirrors iOS resumeBanner in
        // CollectionViewMessageListV3.swift:360. Hidden while
        // streaming or when an error banner is showing.
        //
        // T114: also hide when the last assistant message carries
        // a message-level error — the inline Retry banner already
        // covers that turn, and showing both at once is confusing
        // (Resume on a turn that hit rate-limit would just retrace
        // into the same failure).
        val lastAssistantHasError = messages
            .lastOrNull { it.role == "assistant" }
            ?.error
            ?.isNotBlank() == true
        compactProgress?.let { progress ->
            item(key = "__compact_progress__", contentType = "compact_progress") {
                CompactProgressIndicator(
                    progress = progress,
                    onCancel = { viewModel.cancelCompact() },
                )
            }
        }
        if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
            item(key = "__resume_banner__", contentType = "resume_banner") {
                ResumeBanner(showCrashWarning = resumeAfterCrash, onResume = {
                    viewModel.resume()
                    // T282: same dual-scroll trick as the regular
                    // send paths (T281). Resume kicks off a fresh
                    // stream, so the "Minis is thinking" indicator
                    // mounts a frame or two later — pin once now,
                    // then again after 100ms so the indicator
                    // doesn't land below the fold.
                    userScrolledAway = false
                    coroutineScope.launch {
                        tracedScrollToItem("RESUME-BANNER/initial", 0, 0)
                        kotlinx.coroutines.delay(100)
                        tracedScrollToItem("RESUME-BANNER/settle", 0, 0)
                    }
                })
            }
        }
        items(
            items = flatItems.asReversed(),
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
            // the newest message (index 0 in reverseLayout) is
            // the first row painted in the viewport — its
            // onPlaced is the moment the user actually sees
            // content. SideEffect fires on first composition
            // (before measure); onPlaced fires after layout.
            if (item == flatItems.lastOrNull()) {
                androidx.compose.runtime.SideEffect {
                    com.openminis.app.diagnostics.PerfLongCtx.step(
                        sessionId,
                        "lazyColumn.firstItem.compose",
                    )
                }
            }
            // [Perf][LongCtx] aggregate compose-count tracker.
            // Each row that enters composition during the reentry
            // burst increments the per-session counter. When the
            // 10th and 50th rows hit, emit one line each carrying
            // the wall-time since `lazyColumn.firstLayout` plus
            // the row's class — gives a "per-N-rows compose
            // budget" signal without per-row log spam.
            com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                sessionId,
                item::class.java.simpleName,
            )
            // 0.4f matches iOS .opacity(0.5) closely once Compose's
            // sRGB compositing is factored in. Renders below normal
            // intensity but the message stays selectable + readable.
            val rowAlpha = if (item.isCompacted(grayedMap)) 0.4f else 1f
            // [T-HANG-DIAG] log on first composition of any item
            // whose content is large enough to be a likely hang
            // suspect. SideEffect runs after the first successful
            // composition; if rendering stalls on the way to that
            // SideEffect, we'll see the LAUNCH-RENDER line for it
            // immediately followed by the watchdog's HANG dump
            // and the missing FINISH-RENDER tells us this is the
            // item that locked up the layout pass. Gated on size
            // so normal turns don't spam the log.
            val tHangDiagLen = remember(item.key) {
                when (item) {
                    is FlatChatItem.UserBubble -> item.message.content.length
                    is FlatChatItem.AssistantText -> item.messageMarkdown.length
                    else -> 0
                }
            }
            if (tHangDiagLen >= 50_000) {
                androidx.compose.runtime.SideEffect {
                    println(
                        "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                            "type=${item::class.java.simpleName} len=$tHangDiagLen",
                    )
                }
                androidx.compose.runtime.DisposableEffect(item.key) {
                    onDispose {
                        println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                    }
                }
            }
            val isNewestItem = item == flatItems.lastOrNull()
            Box(
                modifier = Modifier
                    .alpha(rowAlpha)
                    .then(
                        if (isNewestItem) {
                            Modifier.onPlaced {
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstItem.placed",
                                    "size=${it.size.width}x${it.size.height}",
                                )
                                // [T-android-jank-diag-logging]
                                // One quotable line per session
                                // open, after the first frame's
                                // newest row has laid out.
                                if (!coldOpenSummaryEmitted) {
                                    coldOpenSummaryEmitted = true
                                    val totalChars = messages.sumOf { m -> m.content.length }
                                    val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                    AppLogger.info(
                                        "JankDiag",
                                        "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                            "totalChars=$totalChars maxChars=$maxChars prewarmMs=$lastColdPrewarmMs " +
                                            "sinceMountMs=${System.currentTimeMillis() - screenMountAtMs} " +
                                            "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                    )
                                    // [T-android-content-perf-diag] Per-large-message structural
                                    // fingerprint so a future hang report maps straight to "which
                                    // message, what structure" without re-querying the DB. Gated at
                                    // 5000 chars — small messages never drive a render hang.
                                    messages.forEachIndexed { idx, m ->
                                        if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                            val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                            AppLogger.info(
                                                "Perf",
                                                "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                    "streaming=${m.isStreaming} ${s.asLogFields()}",
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            when (item) {
                is FlatChatItem.UserBubble -> {
                    // User bubbles intentionally don't register
                    // MinisTextKit shards — long-press on a user
                    // bubble shows its own action menu (Copy /
                    // Retry / Edit) instead of starting text
                    // selection, matching iOS UX.
                    UserMessageBubble(
                    message = item.message,
                    // [T-android-candidate-bubble-gap] extra top
                    // gap when this bubble directly follows another
                    // user bubble (back-to-back candidate sends).
                    precededByUser = item.precededByUser,
                    onCopy = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
                    },
                    onQuote = if (item.message.isQueued) null else ({
                        viewModel.quoteMessage(item.message)
                        try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                        keyboardController?.show()
                    }),
                    // T119: pass null while a turn is in flight so
                    // the long-press menu hides Retry; once the
                    // stream stops (cancel or natural end) the
                    // option reappears. Gating execution alone
                    // wasn't enough — users still saw a tappable
                    // Retry that silently no-op'd.
                    onRetry = if (isStreaming) null else ({
                        coroutineScope.launch {
                            tracedScrollToItem("RETRY-FROM-MSG", 0, 0)
                        }
                        safeMutate { viewModel.retryFromMessage(item.message.id) }
                    }),
                    onDeleteFromHere = if (isStreaming || item.message.isQueued) null else ({
                        deleteFromHereTargetId = item.message.id
                    }),
                    // Long-press a user bubble to summarize everything above it.
                    // Gated like the actions above: compaction refuses mid-turn,
                    // so offering it there would only produce an error notice.
                    onCompactAbove = if (isStreaming || item.message.isQueued) null else ({
                        compactAboveTargetId = item.message.id
                    }),
                    // T187: long-press → Edit pulls the user message
                    // text into the composer; the next send truncates
                    // from this turn (inclusive) before persisting
                    // the edited content. Gated on isStreaming the
                    // same way Retry is.
                    onEdit = if (isStreaming || item.message.isQueued) null else ({
                        val prefill = viewModel.editMessage(item.message.id)
                        if (prefill != null) {
                            viewModel.setInputText(prefill)
                            coroutineScope.launch {
                                tracedScrollToItem("EDIT-MSG", 0, 0)
                            }
                            inputFocusRequester.requestFocus()
                        }
                    }),
                    onWithdraw = if (item.message.isQueued) {
                        { safeMutate { viewModel.withdrawQueuedMessage(item.message.id) } }
                    } else null,
                    onChangeDelivery = if (item.message.isQueued) {
                        { delivery -> safeMutate { viewModel.setQueuedDelivery(item.message.id, delivery) } }
                    } else null,
                    onPreviewFile = { uri, name ->
                        // T150: turn the persisted file:// URI back
                        // into a FileItem and hand off to the host
                        // navigator (FilePreviewScreen). Mirrors
                        // FileBrowser's onPreviewFile contract so
                        // both entry points share one screen.
                        val file = uri.path?.let { java.io.File(it) }
                        if (file != null && file.exists()) {
                            onPreviewAttachment(
                                com.openminis.app.ui.sandbox.FileItem(
                                    file = file,
                                    name = name,
                                    isDirectory = false,
                                    isSymlink = false,
                                    size = file.length(),
                                    modifiedMs = file.lastModified(),
                                )
                            )
                        }
                    },
                )
                } // close UserBubble SideEffect + UserMessageBubble block
                is FlatChatItem.AssistantText -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "text:${item.block.id}",
                    markdown = item.messageMarkdown,
                ) {
                    // T-android-gc-storm-issue17: collapse oversized frozen
                    // assistant text before feeding the markdown parser, which
                    // is the GC-storm hotspot for legacy sessions.
                    LargeContentGuard(
                        content = item.block.content,
                        isStreaming = item.isStreaming,
                        stableKey = "text:${item.messageId}:${item.block.id}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        StreamingMarkdownText(
                            content = item.block.content,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "text:${item.block.id}",
                            ),
                        )
                    }
                }
                is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                    markdown = item.messageMarkdown,
                ) {
                    LargeContentGuard(
                        content = item.rawText,
                        isStreaming = item.isStreaming,
                        stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        MarkdownBlock(
                            rawText = item.rawText,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                            ),
                        )
                    }
                }
                is FlatChatItem.AssistantThinking -> {
                    // T300: hide Deep Thinking block when the user
                    // currently has thinking turned off — even if
                    // a forced-reasoning model (e.g. xAI Grok 4.x
                    // via OpenRouter) still streams reasoning_-
                    // content. Snapshot on the message wins so
                    // toggling the level after a turn finishes
                    // doesn't retro-hide an already-visible block;
                    // legacy DB-restored messages (snapshot=null)
                    // follow the chat's current level.
                    val effectiveLevel = item.messageThinkingLevel
                        ?: viewModel.thinkingLevel.value
                    if (effectiveLevel.isEnabled) {
                        // [T-android-thinking-auto-collapse] Use
                        // `isLastBlockOverall` (not `isLast` =
                        // last-thinking-only) so the block flips
                        // to !isStreaming the moment a sibling
                        // text/tool_use arrives — that's the
                        // edge ThinkingBlock's LaunchedEffect
                        // hooks for auto-collapse, matching iOS
                        // ThinkingBlockView semantics.
                        ThinkingBlock(
                            block = item.block,
                            isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                            isLast = item.isLast,
                        )
                    }
                }
                is FlatChatItem.AssistantMedia -> {
                    val path = item.block.imageFilePath
                    if (path != null && item.block.mediaRef?.mimeType?.startsWith("image/") == true) {
                        AsyncImage(
                            model = java.io.File(path),
                            contentDescription = item.block.toolTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val siblings = messages.flatMap { it.toolBlocks }
                                        .filter { it.kind == "media" && it.mediaRef?.mimeType?.startsWith("image/") == true && it.imageFilePath != null }
                                    val media = if (siblings.any { it.id == item.block.id }) siblings else siblings + item.block
                                    val items = media.map {
                                        com.openminis.app.ui.components.ImageGalleryItem(java.io.File(it.imageFilePath!!), it.toolTitle)
                                    }
                                    previewImageGallery = items to media.indexOfFirst { it.id == item.block.id }.coerceAtLeast(0)
                                },
                        )
                    } else if (path != null) {
                        UserAttachmentList(
                            imageUris = emptyList(), allFileNames = listOf(item.block.toolTitle),
                            nonImageUris = listOf(android.net.Uri.fromFile(java.io.File(path))),
                            onPreviewFile = { uri, _ -> urlClickHandler(uri.toString()) },
                        )
                    }
                }
                is FlatChatItem.AssistantToolUse -> ToolCallPill(
                    block = item.block,
                    allToolBlocks = item.allToolBlocks,
                    onRetry = if (item.isLastCancelled && !isStreaming && !canResume) ({ safeMutate { viewModel.retryLast() } }) else null,
                    // T14: route per-card stop to the global
                    // cancelStream(). The button only renders
                    // when the block is RUNNING/STREAMING — see
                    // ToolCallPill `isRunning && onStop != null`
                    // — so passing it unconditionally is safe.
                    onStop = { viewModel.cancelStream() },
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    // T261: route detail open through ViewModel so
                    // the sheet is hoisted out of LazyColumn item
                    // scope (otherwise the sheet snaps shut when
                    // the pill scrolls off-screen and Compose
                    // disposes the item).
                    onOpenDetail = { viewModel.openToolDetail(it) },
                    // [T-android-rerun-from-tool-block-position]
                    // Re-run cuts at THIS tool_use block: keep the
                    // blocks before it in the same turn, drop it +
                    // everything after, then regenerate. The block
                    // id (== tool_use id for a tool_use block) is
                    // the stable anchor. Gated off while streaming
                    // (mutating an in-flight turn corrupts agent
                    // state, same rule as Retry on the user bubble).
                    // safeMutate tears down the selection toolbar
                    // before the truncation reshuffles the list.
                    onRerunFromHere = if (!isStreaming) ({
                        coroutineScope.launch {
                            tracedScrollToItem("RERUN-FROM-TOOL", 0, 0)
                        }
                        safeMutate { viewModel.rerunFromToolBlock(item.messageId, item.block.id) }
                    }) else null,
                    onCopyDetails = {
                        val text = formatToolDetailsForClipboard(item.block)
                        val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.tool_longpress_copied_toast),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                // [T-android-work-process] One collapsible row for a
                // whole run of thinking + tool steps. Header = state
                // line (running / failed reason / completed count);
                // the panel renders the same ThinkingBlock and
                // ToolCallPill the perTool layout uses, so the detail
                // sheet, stop button and copy menu stay identical.
                is FlatChatItem.WorkProcessRow -> WorkProcessRowView(
                    process = item.process,
                    allToolBlocks = item.allToolBlocks,
                    onStop = { viewModel.cancelStream() },
                    onOpenDetail = { viewModel.openToolDetail(it) },
                    onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                    onCopyDetails = { block ->
                        val text = formatToolDetailsForClipboard(block)
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.tool_longpress_copied_toast),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    // Same re-run gate as the per-tool pill: off while
                    // a turn is in flight (truncating a live turn
                    // corrupts agent state). The cut anchor is this
                    // process's last tool call.
                    onRerunFromHere = if (!isStreaming) ({
                        val anchor = item.process.toolBlocks.lastOrNull()?.id
                        if (anchor != null) {
                            coroutineScope.launch { tracedScrollToItem("RERUN-FROM-WORK", 0, 0) }
                            safeMutate { viewModel.rerunFromToolBlock(item.messageId, anchor) }
                        }
                    }) else null,
                )
                is FlatChatItem.AssistantInfo -> FallbackInfoBlock(
                    block = item.block,
                    // Only the compact-divider info block should
                    // surface a "Revert Compact" button on its
                    // detail sheet — other info rows (slash
                    // notices, fallback notices) have nothing
                    // to revert.
                    onRevert = if (item.block.toolName == "compact") {
                        { viewModel.revertCompact() }
                    } else null,
                )
                is FlatChatItem.AssistantTyping -> WorkingStatusLine()
                is FlatChatItem.TimeDivider -> TimeDividerLine(item.epochMs)
                is FlatChatItem.AssistantError -> InlineErrorBanner(
                    error = item.error,
                    onRetry = {
                        coroutineScope.launch { tracedScrollToItem("INLINE-RETRY-LAST", 0, 0) }
                        safeMutate { viewModel.retryLast() }
                    },
                    isRetrying = isStreaming,
                )
                is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
                    messageId = item.messageId,
                    slotKey = "legacy",
                    markdown = item.messageMarkdown,
                ) {
                    LargeContentGuard(
                        content = item.content,
                        isStreaming = item.isStreaming,
                        stableKey = "legacy:${item.messageId}",
                    ) {
                        SideEffect {
                            selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                        }
                        StreamingMarkdownText(
                            content = item.content,
                            isStreaming = item.isStreaming,
                            shardId = TextShardId(
                                messageId = item.messageId,
                                shardId = "legacy",
                            ),
                        )
                    }
                }
                is FlatChatItem.AssistantActions -> {
                    // Only the newest reply carries the action row (the redesign); every earlier
                    // reply gets the same actions from a long press on it.
                    if (item.messageId != lastAssistantMessageId) return@Box
                    val isSpeakingThis = replySpeechState.activeMessageId == item.messageId &&
                        replySpeechState.status == com.openminis.app.speech.ReplySpeechState.Status.READING
                    val actions = actionsFor(item.messageId, item.messageMarkdown)
                    AssistantMessageActionBar(
                        messageId = item.messageId,
                        rawText = item.messageMarkdown,
                        isStreaming = item.isStreaming,
                        isGenerating = generatingMessageId == item.messageId,
                        isSpeaking = isSpeakingThis,
                        onCopy = actions.onCopy,
                        onRegenerate = actions.onRegenerate,
                        onToggleSpeak = actions.onToggleSpeak,
                        onCopyMarkdown = actions.onCopyMarkdown,
                        onBranch = actions.onBranch,
                        onSelectText = actions.onSelectText,
                        onShare = actions.onShare,
                        onDelete = actions.onDelete,
                    )
                }
            }
            } // Box (alpha wrapper)
        }
        // [T-android-larky-longsession-followup] "Load older
        // messages" header — placed AFTER items() so under
        // reverseLayout it sits at the VISUAL TOP of the list.
        // Only emitted when the session has trimmed older messages
        // behind the window; tapping bumps the cap by
        // VISIBLE_MESSAGE_CAP_STEP and the FlatChat pipeline
        // rebuilds with the wider slice. The item key is stable so
        // LazyListState's anchor (firstVisibleItem) survives the
        // re-emission and the user keeps their scroll position
        // relative to the message they were reading.
        if (hasOlderMessages) {
            item(key = "__load_older_messages__", contentType = "load_older") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { viewModel.loadOlderMessages() }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_load_older_messages),
                        color = ChatColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SelectionToolbarOverlay(
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    selectionController: com.openminis.app.ui.chat.SelectionController,
    listRootCoordsState: androidx.compose.runtime.MutableState<androidx.compose.ui.layout.LayoutCoordinates?>,
) {
    var listRootCoords by listRootCoordsState
    MinisSelectionToolbarHost(
        controller = selectionController,
        // Clamp the menu's vertical position inside the
        // LazyColumn's viewport in window coords, so it can't
        // float above the chat header or below the composer /
        // navigation bar. Computed lazily so the menu picks up
        // re-layout (rotation, IME show/hide, etc.) without us
        // having to recompose this composable.
        contentViewportBounds = {
            val coords = listRootCoords
            if (coords != null && coords.isAttached) {
                val origin = coords.positionInWindow()
                androidx.compose.ui.geometry.Rect(
                    left = origin.x,
                    top = origin.y,
                    right = origin.x + coords.size.width,
                    bottom = origin.y + coords.size.height,
                )
            } else null
        },
        actions = SelectionToolbarActions(
            // Resolve the parent message's joined markdown via
            // the bounds registry — only when the selection sits
            // within a single message (cross-message selections
            // return null and the markdown / rich-text buttons
            // are hidden).
            resolveSelectionMarkdown = {
                // Use the controller's own cached
                // message-markdown — survives both endpoint
                // shards scrolling off-screen, unlike the rect-
                // based MessageBoundsRegistry lookup whose
                // entries are removed on shard dispose.
                selectionController.selectionMessageMarkdown()
            },
            // Quote: the selection goes into the composer as a Markdown quote, ready to be answered under.
            onQuote = { snippet ->
                viewModel.quoteIntoInput(snippet)
                try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                keyboardController?.show()
            },
            // The same player, state and control bar as reading a whole reply.
            onReadAloud = { snippet -> viewModel.readSelectionAloud(snippet) },
        ),
    )
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ScrollToBottomFab(
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit,
    isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>,
    contentOverflows: androidx.compose.runtime.State<kotlin.Boolean>,
    userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    lastJumpedUserIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    lastToolBlocksState: androidx.compose.runtime.MutableState<kotlin.collections.List<com.openminis.app.ui.chat.AssistantBlock>>,
) {
    val messages by messagesState
    var userScrolledAway by userScrolledAwayState
    var lastJumpedUserId by lastJumpedUserIdState
    var lastToolBlocks by lastToolBlocksState
    if (userScrolledAway && contentOverflows.value && messages.isNotEmpty()) {
        val fabBottomPadding = if (lastToolBlocks.isNotEmpty()) 80.dp else 8.dp
        androidx.compose.material3.FilledIconButton(
            onClick = {
                // [T-android-scroll-fab-down-stuck] Clear the
                // scrolled-away intent SYNCHRONOUSLY on tap — that
                // alone hides the FAB (its gate is userScrolledAway).
                // Don't rely on the at-bottom auto-reset LE
                // (`isNearBottom && userScrolledAway → false`): on a
                // long reverseLayout session scrollToItem(0,0) can
                // settle on a non-zero firstVisibleItemIndex while
                // unmeasured items resolve (logged: FAB-DOWN tap left
                // firstIdx=41/60, canBwd=false), so isNearBottom stays
                // false, the auto-reset never fires, and the FAB was
                // stuck visible. The user tapped "go to bottom" — the
                // intent is unambiguous, so reset directly.
                userScrolledAway = false
                // [T-android-scrollbtn-turn-walk] Jumping to the
                // bottom resets the up-button's turn-walk (iOS does
                // the same in its forceScrollToBottom handler).
                lastJumpedUserId = null
                coroutineScope.launch {
                    tracedScrollToItem("FAB-DOWN", 0, 0)
                    // Second pin after a frame: the first scroll may
                    // land short while late-measuring items shift the
                    // true bottom; re-issue once layout settles.
                    kotlinx.coroutines.delay(100)
                    tracedScrollToItem("FAB-DOWN/settle", 0, 0)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = fabBottomPadding)
                .then(
                    if (LocalUiStyle.current == UiStyle.GLASS) Modifier.glassSurface(
                        shape = CircleShape,
                        glassScrim = if (ChatColors.isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.5f),
                        fallbackScrim = ChatColors.inputBg,
                        blurRadius = 16.dp,
                        refraction = 12.dp,
                    ) else Modifier.shadow(3.dp, CircleShape).border(0.8.dp, ChatColors.inputBorder, CircleShape),
                )
                .size(36.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = if (LocalUiStyle.current == UiStyle.GLASS) Color.Transparent else ChatColors.inputBg,
                contentColor = ChatColors.primaryText,
            ),
        ) {
            Icon(
                imageVector = com.openminis.app.ui.components.MinisIcons.ArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun originalMessageId(id: String): String =
    id.substringBefore('#')

/** Whether the row belongs to a message that a compaction has already folded into the summary. */
internal fun FlatChatItem.isCompacted(grayedMap: Map<String, Boolean>): Boolean = when (this) {
    is FlatChatItem.UserBubble -> grayedMap[originalMessageId(message.id)] == true
    is FlatChatItem.AssistantText -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantMarkdownBlock -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantThinking -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantToolUse -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.WorkProcessRow -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantMedia -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantInfo -> false  // system rows never grayed
    is FlatChatItem.AssistantTyping -> false
    is FlatChatItem.TimeDivider -> false
    is FlatChatItem.AssistantError -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantLegacyContent -> grayedMap[originalMessageId(messageId)] == true
    is FlatChatItem.AssistantActions -> grayedMap[originalMessageId(messageId)] == true
}
