package com.openminis.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatBottomArea(
    sessionId: kotlin.String,
    chatRepository: com.openminis.app.data.repository.ChatRepository,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    onNewChat: kotlin.Function0<kotlin.Unit>,
    onMoveToSession: kotlin.Function1<kotlin.String, kotlin.Unit>,
    onPreviewAttachment: kotlin.Function1<com.openminis.app.ui.sandbox.FileItem, kotlin.Unit>,
    context: android.content.Context,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messages_st: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    isStreaming_st: androidx.compose.runtime.State<kotlin.Boolean>,
    isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>,
    currentBot: com.openminis.app.data.db.BotEntity?,
    replySpeechStateState: androidx.compose.runtime.State<com.openminis.app.speech.ReplySpeechState>,
    modelName_st: androidx.compose.runtime.State<kotlin.String>,
    attachments_st: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.InputAttachment>>,
    attachmentsState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.InputAttachment>>,
    pastedTexts_st: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.PastedText>>,
    ensureMicPermissionFlow: suspend () -> kotlin.Boolean,
    inputText_st: androidx.compose.runtime.State<kotlin.String>,
    inputTextState: androidx.compose.runtime.State<kotlin.String>,
    inputFieldValue_st: androidx.compose.runtime.MutableState<androidx.compose.ui.text.input.TextFieldValue>,
    lastSendTimeMs_st: androidx.compose.runtime.MutableState<kotlin.Long>,
    voiceUsedSinceClear_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    noteSendForInputModePref: kotlin.Function0<kotlin.Unit>,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    inputFocused_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    sendSwipeProgress_st: androidx.compose.runtime.MutableState<kotlin.Float>,
    sendSwipeProgressState: androidx.compose.runtime.MutableState<kotlin.Float>,
    sendSwipeLocation_st: androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Offset>,
    sendSwipeLocationState: androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Offset>,
    swipeThresholdPx: kotlin.Float,
    swipeArmFraction: kotlin.Float,
    swipeHapticOffsetPx: kotlin.Float,
    swipeArrowHalfPx: kotlin.Float,
    swipeHaptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    showModelPicker_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showAttachMenu_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showAgentPresetSheet_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showMoveSheet_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showMoveSheetState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showClearChatDialogState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    deleteSingleMessageTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    regenerateTargetState: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.String, kotlin.Int>?>,
    deleteFromHereTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    compactAboveTargetIdState: androidx.compose.runtime.MutableState<kotlin.String?>,
    showNewChatStopDialogState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    showEnhancedCacheDialogState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    mediaPickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest, kotlin.collections.List<android.net.Uri>>,
    launchCamera: kotlin.Function0<kotlin.Unit>,
    cameraPermissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<kotlin.String, kotlin.Boolean>,
    filePickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<kotlin.Array<kotlin.String>, kotlin.collections.List<android.net.Uri>>,
    tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit,
    userScrolledAway_st: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    releaseComposerAfterSend: kotlin.Function0<kotlin.Unit>,
    performSendOrEnqueue: kotlin.Function1<kotlin.String, kotlin.Unit>,
    editingSessionState: androidx.compose.runtime.MutableState<com.openminis.app.data.db.ChatSessionEntity?>,
    chatInputFontScale: kotlin.Float,
    previewImageGallery_st: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.collections.List<com.openminis.app.ui.components.ImageGalleryItem>, kotlin.Int>?>,
    webAppSheetTarget_st: androidx.compose.runtime.MutableState<com.openminis.app.ui.chat.InputAttachment?>,
) {
    val messages by messagesState
    val isStreaming by isStreamingState
    val replySpeechState by replySpeechStateState
    val attachments by attachmentsState
    val inputText by inputTextState
    var sendSwipeProgress by sendSwipeProgressState
    var sendSwipeLocation by sendSwipeLocationState
    var showMoveSheet by showMoveSheetState
    var showClearChatDialog by showClearChatDialogState
    var deleteSingleMessageTargetId by deleteSingleMessageTargetIdState
    var regenerateTarget by regenerateTargetState
    var deleteFromHereTargetId by deleteFromHereTargetIdState
    var compactAboveTargetId by compactAboveTargetIdState
    var showNewChatStopDialog by showNewChatStopDialogState
    var showEnhancedCacheDialog by showEnhancedCacheDialogState
    var editingSession by editingSessionState
    editingSession?.let { session ->
        com.openminis.app.ui.sessions.SessionEditSheet(
            session = session,
            onDismiss = { editingSession = null },
            onSave = { newTitle, newCategory ->
                viewModel.updateTitleAndCategory(newTitle, newCategory)
                editingSession = null
            },
        )
    }

    // ─── Input area (iOS-style: rounded box with text + buttons below) ───
    ReplySpeechControlBar(
        state = replySpeechState,
        onTogglePause = { viewModel.toggleReplySpeechPause() },
        onClose = { viewModel.stopReplySpeech() },
        modifier = Modifier
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH),
    )
    val composerWidthPx_st = remember { mutableStateOf(0) }
    var composerWidthPx by composerWidthPx_st
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = CHAT_MAX_CONTENT_WIDTH)
            .onGloballyPositioned { composerWidthPx = it.size.width }
            .padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = 8.dp),
    ) {
        // T13 banner moved INSIDE the LazyColumn so it renders at the
        // visual end of the message list (mirrors iOS — see the
        // banner item before items() in the LazyColumn block above).

        // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
        // a Popup so it overlays content (tool status bar, chat list)
        // instead of pushing them up. Anchored above the composer via
        // PopupProperties so its bottom edge sits just above this Column.
        // Tap-outside dismisses via dismissOnClickOutside.
        val showSlashMenu_st = viewModel.showSlashMenu.collectAsState()
        val showSlashMenu by showSlashMenu_st
        val filteredSlashCommands = remember(
            showSlashMenu,
            viewModel.slashFilter.collectAsState().value,
            viewModel.memoryEnabled.collectAsState().value,
            viewModel.thinkingLevel.collectAsState().value,
        ) { viewModel.filteredSlashCommands() }

        SlashCommandPopup(
            keyboardController = keyboardController,
            viewModel = viewModel,
            inputTextState = inputText_st,
            inputFocusRequester = inputFocusRequester,
            composerWidthPxState = composerWidthPx_st,
            showSlashMenuState = showSlashMenu_st,
            filteredSlashCommands = filteredSlashCommands,
        )

        // T4: @ file-mention picker — same anchoring + tap-spy
        // contract as the slash popup (mutually exclusive in the VM,
        // so they never both render). Reuses Popup so the bar over
        // the composer is consistent and respects IME inset.
        val showMentionMenu_st = viewModel.showMentionMenu.collectAsState()
        val showMentionMenu by showMentionMenu_st
        val mentionEntries_st = viewModel.mentionEntries.collectAsState()
        val mentionEntries by mentionEntries_st
        val isMentionScanning_st = viewModel.isMentionScanning.collectAsState()
        val isMentionScanning by isMentionScanning_st
        val mentionSelectedIndex_st = viewModel.mentionSelectedIndex.collectAsState()
        val mentionSelectedIndex by mentionSelectedIndex_st
        MentionPopup(
            viewModel = viewModel,
            inputFieldValueState = inputFieldValue_st,
            composerWidthPxState = composerWidthPx_st,
            showMentionMenuState = showMentionMenu_st,
            mentionEntriesState = mentionEntries_st,
            isMentionScanningState = isMentionScanning_st,
            mentionSelectedIndexState = mentionSelectedIndex_st,
        )

        // Input box: iOS-style floating card — no visible border, separated
        // from the backdrop by a symmetric soft shadow painted by hand
        // (Android's Modifier.shadow only casts downward).
        val inputBgArgb = ChatColors.inputBg.toArgb()
        val cardEdge = ChatColors.inputBorder
        val shadowPaint = remember(inputBgArgb) {
            android.graphics.Paint().apply {
                color = inputBgArgb
                isAntiAlias = true
            }
        }
        // T185: Move-to-session capsule mirrors iOS
        // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
        // on the input card. We render it as the first child of the
        // composer Column, right-aligned, so it visually sits inside
        // the input card's top-right corner — Compose doesn't have a
        // free overlay primitive that doesn't need a Box wrapper,
        // and an in-flow Row at the top with Arrangement.End is the
        // cleanest equivalent.
        val showMoveCapsule_st = viewModel.hasInjectedShareContent.collectAsState()
        val showMoveCapsule by showMoveCapsule_st
        // Mirrors iOS swipe-up-to-send: drag the input bar upward —
        // if it holds text, a floating send-arrow + "Release to send"
        // capsule track the finger; releasing past `swipeArmFraction`
        // sends. With empty text + collapsed keyboard, releasing
        // activates the keyboard instead. Box wraps the existing
        // composer Column so the gesture + overlay live in the same
        // coordinate space without disturbing the bar's own layout.
        ChatComposer(
            providerRepository = providerRepository,
            onPreviewAttachment = onPreviewAttachment,
            context = context,
            keyboardController = keyboardController,
            viewModel = viewModel,
            messagesState = messages_st,
            isStreamingState = isStreaming_st,
            currentBot = currentBot,
            modelNameState = modelName_st,
            attachmentsState = attachments_st,
            pastedTextsState = pastedTexts_st,
            ensureMicPermissionFlow = ensureMicPermissionFlow,
            inputTextState = inputText_st,
            inputFieldValueState = inputFieldValue_st,
            lastSendTimeMsState = lastSendTimeMs_st,
            voiceUsedSinceClearState = voiceUsedSinceClear_st,
            noteSendForInputModePref = noteSendForInputModePref,
            inputFocusRequester = inputFocusRequester,
            inputFocusedState = inputFocused_st,
            sendSwipeProgressState = sendSwipeProgress_st,
            sendSwipeLocationState = sendSwipeLocation_st,
            swipeThresholdPx = swipeThresholdPx,
            swipeArmFraction = swipeArmFraction,
            swipeHaptics = swipeHaptics,
            coroutineScope = coroutineScope,
            showModelPickerState = showModelPicker_st,
            showAttachMenuState = showAttachMenu_st,
            showMoveSheetState = showMoveSheet_st,
            mediaPickerLauncher = mediaPickerLauncher,
            launchCamera = launchCamera,
            cameraPermissionLauncher = cameraPermissionLauncher,
            filePickerLauncher = filePickerLauncher,
            tracedScrollToItem = tracedScrollToItem,
            userScrolledAwayState = userScrolledAway_st,
            releaseComposerAfterSend = releaseComposerAfterSend,
            performSendOrEnqueue = performSendOrEnqueue,
            chatInputFontScale = chatInputFontScale,
            previewImageGalleryState = previewImageGallery_st,
            webAppSheetTargetState = webAppSheetTarget_st,
            showSlashMenuState = showSlashMenu_st,
            filteredSlashCommands = filteredSlashCommands,
            showMentionMenuState = showMentionMenu_st,
            cardEdge = cardEdge,
            shadowPaint = shadowPaint,
            showMoveCapsuleState = showMoveCapsule_st,
        )
        // --- Swipe-to-send floating hint (extracted helper) ---
        SwipeToSendHint(
            progress = sendSwipeProgress,
            armFraction = swipeArmFraction,
            location = sendSwipeLocation,
            hoverAbovePx = swipeHapticOffsetPx,
            arrowHalfPx = swipeArrowHalfPx,
            // While streaming, sendMessage() routes the prompt
            // through enqueuePrompt() instead — surface that in
            // the hint so the user knows the gesture still works
            // mid-stream (mirrors the send-button's send/enqueue
            // toggle, since on Android there's no separate visual
            // state for the queued case).
            isEnqueue = isStreaming,
        )
    } // end swipe-to-send Box wrapping the composer Column

    if (showMoveSheet) {
        MoveToSessionSheet(
            currentSessionId = sessionId,
            chatRepository = chatRepository,
            onDismiss = { showMoveSheet = false },
            onSelect = { targetId ->
                ChatViewModelStore.stashPendingTransfer(
                    ChatViewModelStore.PendingTransfer(
                        inputText = inputText,
                        attachments = viewModel.attachments.value,
                        // [T-android-moveto-stash-binding] Bind the stash to
                        // the chosen target so no other session can drain it.
                        targetId = targetId,
                    ),
                )
                viewModel.setInputText("")
                viewModel.clearAttachments()
                viewModel.clearShareInjectedFlag()
                showMoveSheet = false
                onMoveToSession(targetId)
            },
        )
    }

    // Pre-send context gate (iOS "Context Near Capacity" alert).
    // Raised when the compact threshold is crossed and auto-compact is
    // OFF; with it on the ViewModel compacts silently and never gets
    // here. Three actions, matching iOS:
    //   Send Anyway                  — skip compaction entirely
    //   Compact & Send               — compact this once, pref untouched
    //   Compact & Enable Auto-Compact— compact AND opt in, so the
    //                                  threshold stops prompting from
    //                                  now on (iOS T-chat-auto-compact-opt-in)
    val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsState()
    if (showCompactBeforeSend) {
        MinisAlertDialog(
            // Back-gesture / scrim dismissal must NOT silently drop the
            // user's text — cancelCompactBeforeSend puts it back in the
            // composer.
            onDismissRequest = { viewModel.cancelCompactBeforeSend() },
            title = stringResource(R.string.context_near_capacity_title),
            text = stringResource(R.string.context_near_capacity_message),
            confirmText = stringResource(R.string.context_compact_and_send),
            onConfirm = { viewModel.compactAndSendPending() },
            dismissText = stringResource(R.string.context_send_anyway),
            onDismiss = { viewModel.sendPendingWithoutCompacting() },
            neutralText = stringResource(R.string.context_compact_and_enable_auto),
            onNeutral = {
                viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
            },
        )
    }

    // Agent Preset 选择（复用 App 现有对话框；与 Web 共用注册表）
    AgentPresetDialog(sessionId = sessionId, context = context, showAgentPresetSheetState = showAgentPresetSheet_st)

    // T137: Clear Chat confirmation. Wipes messages + agent history +
    // compact markers; the session row, workspace files, attachments,
    // and offload payloads are intentionally preserved (iOS parity).
    if (showClearChatDialog) {
        MinisAlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = stringResource(R.string.chat_menu_clear_chat),
            text = stringResource(R.string.chat_clear_dialog_body),
            confirmText = stringResource(R.string.chat_clear_dialog_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.clearChat()
                viewModel.setInputText("")
                showClearChatDialog = false
            },
        )
    }
    regenerateTarget?.let { (targetId, later) ->
        MinisAlertDialog(
            onDismissRequest = { regenerateTarget = null },
            title = stringResource(R.string.assistant_regenerate_confirm_title),
            text = stringResource(R.string.assistant_regenerate_confirm_body, later),
            confirmText = stringResource(R.string.assistant_regenerate_confirm_action),
            isDestructive = true,
            onConfirm = {
                regenerateTarget = null
                if (!viewModel.regenerateAssistantMessage(targetId)) {
                    com.openminis.app.ui.components.MinisToast.show(
                        context,
                        context.getString(R.string.assistant_regenerate_unavailable),
                    )
                }
            },
        )
    }
    deleteSingleMessageTargetId?.let { targetId ->
        MinisAlertDialog(
            onDismissRequest = { deleteSingleMessageTargetId = null },
            title = stringResource(R.string.chat_delete_single_message_title),
            text = stringResource(R.string.chat_delete_single_message_body),
            confirmText = stringResource(R.string.chat_delete_single_message_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSingleAssistantMessage(targetId)
                deleteSingleMessageTargetId = null
            },
        )
    }
    deleteFromHereTargetId?.let { targetId ->
        val affected = remember(targetId, messages) {
            val idx = messages.indexOfFirst { it.id == targetId }
            if (idx < 0) 0 else messages.size - idx
        }
        MinisAlertDialog(
            onDismissRequest = { deleteFromHereTargetId = null },
            title = stringResource(R.string.chat_longpress_delete_from_here),
            text = pluralStringResource(
                R.plurals.chat_delete_from_here_dialog_body,
                affected,
                affected,
            ),
            confirmText = stringResource(R.string.chat_delete_from_here_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFromMessage(targetId)
                deleteFromHereTargetId = null
            },
        )
    }
    // Compaction replaces the history above this point with a generated
    // summary and the original turns stop being sent to the model, so it
    // confirms first the way Delete From Here does.
    compactAboveTargetId?.let { targetId ->
        MinisAlertDialog(
            onDismissRequest = { compactAboveTargetId = null },
            title = stringResource(R.string.chat_longpress_compact_above),
            text = stringResource(R.string.chat_compact_above_dialog_body),
            confirmText = stringResource(R.string.chat_compact_above_confirm),
            isDestructive = true,
            onConfirm = {
                viewModel.compactBefore(targetId)
                compactAboveTargetId = null
            },
        )
    }
    // [T-new-chat-menu-entry] Streaming guard for the menu's New Chat:
    // confirm → stop the running task, then navigate to a fresh draft;
    // dismiss → stay in the current chat.
    if (showNewChatStopDialog) {
        MinisAlertDialog(
            onDismissRequest = { showNewChatStopDialog = false },
            title = stringResource(R.string.chat_menu_new_chat),
            text = stringResource(R.string.chat_new_chat_stop_dialog_body),
            confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
            isDestructive = true,
            onConfirm = {
                showNewChatStopDialog = false
                viewModel.cancelStream()
                onNewChat()
            },
        )
    }
    // [T-android-enhanced-cache] One-time extra-billing confirmation
    // before the first enable. Accepting records the durable ack and
    // turns the toggle on; subsequent enables skip the dialog.
    if (showEnhancedCacheDialog) {
        MinisAlertDialog(
            onDismissRequest = { showEnhancedCacheDialog = false },
            title = stringResource(R.string.chat_menu_enhanced_cache),
            text = stringResource(R.string.enhanced_cache_dialog_body),
            confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
            onConfirm = {
                viewModel.confirmAndEnableEnhancedCache()
                showEnhancedCacheDialog = false
            },
        )
    }
}
