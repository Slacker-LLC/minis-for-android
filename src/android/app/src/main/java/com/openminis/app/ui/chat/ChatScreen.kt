package com.openminis.app.ui.chat

import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.sp
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.theme.LocalChatPalette










@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    botRepository: com.openminis.app.data.repository.BotRepository? = null,
    onBotDetails: (String) -> Unit = {},
    onBack: () -> Unit,
    isTwoPane: Boolean = false,
    onToggleSidebar: (() -> Unit)? = null,
    sidebarCollapsed: Boolean = false,
    onOpenDrawer: (() -> Unit)? = null,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: () -> Unit = {},
    /**
     * Lets the host ask "is this chat a blank draft?" (see [ChatViewModel.isBlankDraft]) so its own
     * New chat entry points, such as the drawer's, can skip opening yet another empty draft.
     */
    onProbeBlankDraft: ((() -> Boolean)) -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** First-run card: 1 = add a provider, 2 = choose a model. */
    onOpenSetupStep: (step: Int) -> Unit = {},
    /** Open the in-app terminal with [command] pre-filled at the prompt
     *  (no trailing newline — the user reviews and presses Enter manually).
     *  Wired to the top-right Terminal button on a shell_execute ToolDetailSheet. */
    onOpenTerminalWithCommand: (command: String) -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onBrowseChatFiles: () -> Unit = {},
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
            botRepository = botRepository,
        ),
    )
    // [T-android-larky-longsession-followup] Consume the tail-windowed
    // view instead of the canonical full list. For sessions with ≤300
    // messages this is the SAME reference (zero overhead); for longer
    // sessions (Larky's 600+) it caps at INITIAL_VISIBLE_MESSAGE_CAP and
    // grows in steps when the user reaches the top via [viewModel.loadOlderMessages].
    // Callers needing the full history (compact / fork / regenerate / send)
    // continue to read viewModel.messages directly inside the VM.
    val messages_st = viewModel.uiMessages.collectAsState()
    val messages by messages_st
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onProbeBlankDraft { viewModel.isBlankDraft }
        onDispose { }
    }
    val hasOlderMessages_st = viewModel.hasOlderMessages.collectAsState()
    val hasOlderMessages by hasOlderMessages_st
    val isStreaming_st = viewModel.isStreaming.collectAsState()
    val isStreaming by isStreaming_st
    val currentSession by remember(sessionId, chatRepository) {
        chatRepository.observeSession(sessionId)
    }.collectAsState(initial = null)
    val team_st = remember(botRepository) {
        botRepository?.observeBots() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val team by team_st
    val currentBot = team.firstOrNull { it.id == currentSession?.botId }
    val generatingMessageId_st = viewModel.generatingMessageId.collectAsState()
    val generatingMessageId by generatingMessageId_st
    val replySpeechState_st = viewModel.replySpeechState.collectAsState()
    val replySpeechState by replySpeechState_st
    val canResume_st = viewModel.canResume.collectAsState()
    val canResume by canResume_st
    val compactProgress_st = viewModel.compactProgress.collectAsState()
    val compactProgress by compactProgress_st
    val error_st = viewModel.error.collectAsState()
    val error by error_st
    val modelName_st = viewModel.modelName.collectAsState()
    val modelName by modelName_st
   val sessionTitle_st = viewModel.sessionTitle.collectAsState()
   val sessionTitle by sessionTitle_st
   val sessionCategory by viewModel.sessionCategory.collectAsState()
   val attachments_st = viewModel.attachments.collectAsState()
   val attachments by attachments_st
    val pastedTexts_st = viewModel.pastedTexts.collectAsState()
    val pastedTexts by pastedTexts_st
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsState()
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // [T-android-voice-panel] Shared 3-stage RECORD_AUDIO permission flow
    // (system dialog → post-DENY poll → in-app settings gate). Extracted from
    // the mic button's triggerVoiceInput so the inline voice panel can request
    // the same way. Returns true when granted.
    val ensureMicPermissionFlow = rememberMicPermissionFlow(context = context)
    val listState = viewModel.listState
    // T325: draft persists on the VM so navigation (e.g. push EnvVars and
    // pop back) doesn't wipe what the user has typed. Mirrors iOS
    // `AIChatView` which binds the composer against `vm.inputText`.
    val inputText_st = viewModel.inputText.collectAsState()
    val inputText by inputText_st

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion_st = com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    val shareBufferVersion by shareBufferVersion_st
    ShareBufferEffect(
        sessionId = sessionId,
        context = context,
        viewModel = viewModel,
        inputTextState = inputText_st,
        shareBufferVersionState = shareBufferVersion_st,
    )

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    SessionDisposableEffect(
        sessionId = sessionId,
        context = context,
        viewModel = viewModel,
        attachmentsState = attachments_st,
        tHangDiagAppContext = tHangDiagAppContext,
    )
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        // [T-android-moveto-stash-binding] Pass this screen's session so the
        // store only hands over a stash addressed to it (and drops stale ones).
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    val inputFieldValue_st = remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var inputFieldValue by inputFieldValue_st
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    val lastSendTimeMs_st = remember { mutableStateOf(0L) }
    var lastSendTimeMs by lastSendTimeMs_st
    // [T-voice-mode-memory-refine-android] True when a voice recording started
    // since the composer was last cleared. The SEND is what commits the mode:
    // mic-start no longer writes "voice" (an accidental mic tap with no send
    // must not flip the default) — instead this flag is consulted on send.
    val voiceUsedSinceClear_st = remember { mutableStateOf(false) }
    var voiceUsedSinceClear by voiceUsedSinceClear_st
    // Shared by both send paths (send button / Enter): commit the composer
    // mode at send time — "voice" if this composition used voice, otherwise
    // "text" — then reset the tracker for the now-cleared composer.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = voiceUsedSinceClear)
        voiceUsedSinceClear = false
        // [T-android-voice-correction] A send is the natural moment to mine
        // typed vocabulary: the message is committed, and the builder's own
        // hourly throttle makes the common case a no-op. Consent-gated and
        // fire-and-forget inside.
        com.openminis.app.speech.correction.VoiceCorrection.mineVocabularyIfNeeded(context)
    }
    // [T-android-send-no-autoscroll-behind-preview] Timestamp of the most
    // recent USER message append, stamped in LE(messages.size) so it covers
    // every origin (send button, Enter, RPC, enqueue-while-streaming). Used
    // as the follow-grace window for the reserve-change pin. Deliberately
    // separate from lastSendTimeMs above — that one also drives the 300ms
    // IME-residue suppression in the composer and must stay UI-send-only.
    val lastUserAppendMs_st = remember { mutableStateOf(0L) }
    var lastUserAppendMs by lastUserAppendMs_st
    // [T-android-stream-end-arm-race] Timestamp of the last streaming→idle
    // edge. Guards the position-driven userScrolledAway net against the final
    // markdown reflow that lands just after _isStreaming clears.
    val lastStreamEndMs_st = remember { mutableStateOf(0L) }
    var lastStreamEndMs by lastStreamEndMs_st
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [T-android-slash-menu-align-ios-prepend] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once; otherwise default the caret to the end
            // (existing behavior). Coerce into bounds defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    val inputFocused_st = remember { mutableStateOf(false) }
    var inputFocused by inputFocused_st

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    val sendSwipeProgress_st = remember { mutableStateOf(0f) }
    var sendSwipeProgress by sendSwipeProgress_st
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    val sendSwipeLocation_st = remember { mutableStateOf(Offset.Zero) }
    var sendSwipeLocation by sendSwipeLocation_st
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val coroutineScope = rememberCoroutineScope()

    val showModelPicker_st = remember { mutableStateOf(false) }
    var showModelPicker by showModelPicker_st
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    val showAttachMenu_st = remember { mutableStateOf(false) }
    var showAttachMenu by showAttachMenu_st
    val showChatMenu_st = remember { mutableStateOf(false) }
    var showChatMenu by showChatMenu_st
    val showAgentPresetSheet_st = remember { mutableStateOf(false) }
    var showAgentPresetSheet by showAgentPresetSheet_st
    var showSkillsSheet by remember { mutableStateOf(false) }
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    var showMcpsSheet by remember { mutableStateOf(false) }
    // GH#32/#35: session-local prompt/model/tool overrides.
    var showSessionConfigSheet by remember { mutableStateOf(false) }
    val showVirtualScreenViewer_st = remember { mutableStateOf(false) }
    var showVirtualScreenViewer by showVirtualScreenViewer_st
    var showSessionAdvancedSettings by rememberSaveable { mutableStateOf(false) }
    var showTokenUsageSheet by remember { mutableStateOf(false) }
    // T185: Move-to-session sheet visibility. Hoisted to the top of
    // ChatScreen so the trigger (capsule inside the composer) and the
    // sheet body (rendered later in the layout tree) share the same
    // backing state without needing fragile scope wiring.
    val showMoveSheet_st = remember { mutableStateOf(false) }
    var showMoveSheet by showMoveSheet_st
    val showClearChatDialog_st = remember { mutableStateOf(false) }
    var showClearChatDialog by showClearChatDialog_st
    val deleteSingleMessageTargetId_st = remember { mutableStateOf<String?>(null) }
    var deleteSingleMessageTargetId by deleteSingleMessageTargetId_st
    // Assistant reply the user asked to regenerate while later messages exist, with how many would go.
    val regenerateTarget_st = remember { mutableStateOf<Pair<String, Int>?>(null) }
    var regenerateTarget by regenerateTarget_st
    // Reply whose long-press menu is open.
    val messageMenuTarget_st = remember { mutableStateOf<String?>(null) }
    var messageMenuTarget by messageMenuTarget_st
    val deleteFromHereTargetId_st = remember { mutableStateOf<String?>(null) }
    var deleteFromHereTargetId by deleteFromHereTargetId_st
    val compactAboveTargetId_st = remember { mutableStateOf<String?>(null) }
    var compactAboveTargetId by compactAboveTargetId_st
    // [T-new-chat-menu-entry] Confirmation gate for "New Chat" while the
    // current session is still streaming — stopping the running task needs
    // an explicit confirm; idle sessions skip the dialog entirely.
    val showNewChatStopDialog_st = remember { mutableStateOf(false) }
    var showNewChatStopDialog by showNewChatStopDialog_st
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    val showEnhancedCacheDialog_st = remember { mutableStateOf(false) }
    var showEnhancedCacheDialog by showEnhancedCacheDialog_st

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberMediaPickerLauncher(context = context, viewModel = viewModel)

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    val pendingCameraUri_st = rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraUri by pendingCameraUri_st
    val pendingCameraFilePath_st = rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by pendingCameraFilePath_st
    val cameraLauncher = rememberCameraLauncher(
        context = context,
        viewModel = viewModel,
        pendingCameraUriState = pendingCameraUri_st,
        pendingCameraFilePathState = pendingCameraFilePath_st,
    )
    val launchCamera = rememberLaunchCamera(
        context = context,
        pendingCameraUriState = pendingCameraUri_st,
        pendingCameraFilePathState = pendingCameraFilePath_st,
        cameraLauncher = cameraLauncher,
    )
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // Voice variant lives next to the MicButton because it needs sttAvailable
    // — camera is always available so it can fire from the top-level scope.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // [T-android-assistant-home] "Analyze screen" quick action: the home page
    // opens a fresh draft and seeds this action, so the composer arrives with
    // the prompt visible — the user reads (and can edit) what will be asked
    // before anything is sent. Consumed once so re-entering the draft later
    // does not overwrite whatever the user typed meanwhile.
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.ANALYZE_SCREEN
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            viewModel.setInputText(context.getString(R.string.assistant_action_screen_prompt))
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberFilePickerLauncher(context = context, viewModel = viewModel)
    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { source, idx, off ->
        // [T-android-top-drag-jump] TEMP: log every programmatic scroll's source
        // so we can see which one fights the user near the top. Remove after fix.
        AppLogger.debug(
            "ScrollSrc",
            "scrollToItem src=$source idx=$idx off=$off canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset} inProgress=${listState.isScrollInProgress}",
        )
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    val tracedScrollBy: suspend (source: String, delta: Float) -> Unit = { source, delta ->
        AppLogger.debug(
            "ScrollSrc",
            "scrollBy src=$source delta=$delta canBwd=${listState.canScrollBackward} firstIdx=${listState.firstVisibleItemIndex} firstOff=${listState.firstVisibleItemScrollOffset}",
        )
        runCatching { listState.scrollBy(delta) }
        Unit
    }
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    val verboseScrollLogs = false

    // ─── T120: scroll-follow rewrite (supersedes T66 / T92 / T99 / T100 / T101 / T112) ───
    //
    // Five iterations of "fight the LazyColumn" (anchor lock, fling-settle
    // gate, isStreaming/lastToolCount/lastAwaiting force-follow LEs) never
    // truly stopped the streaming jitter. Survey of production Compose
    // chat clients (google-ai-edge/gallery, GetStream/stream-chat-android-ai,
    // lambiengcode/compose-chatgpt-kotlin-android-chatbot, Taewan-P/gpt_mobile)
    // showed a consistent pattern:
    //
    //   1. Trust reverseLayout's native bottom anchor — do not call
    //      scrollToItem(0) on every streaming token.
    //   2. Auto-scroll only on TERMINAL events (user sends, IME opens,
    //      stream finishes) — never per-token.
    //   3. Treat "user scrolled away" as a derived value of the current
    //      list position, not a stateful flag mutated by a gesture flow.
    //   4. Provide a JumpToBottom FAB as the universal escape hatch
    //      (already present in this file).
    //
    // What was removed
    //   - Anchor-lock LaunchedEffect (T92 / T99 / T112) — Compose's
    //     reverseLayout already keeps a fixed item anchored, the lock
    //     was fighting that.
    //   - userScrolledAway state mutation in two snapshotFlow collectors —
    //     replaced by a single derivedStateOf<Boolean>.
    //   - LE(isStreaming) edge force-follow.
    //   - LE(lastToolCount) force-follow.
    //   - LE(lastAwaiting) force-follow.
    //   - LE(bottomReserve) inside the toolbar block.
    //   - LE(messages.size) for assistant/tool/system rows — only the
    //     user-send branch survives, because sending is the one event
    //     where "follow the new turn" is unambiguously the user's intent.
    //
    // What stayed
    //   - User-action scroll calls at the send button, retry buttons,
    //     and the JumpToBottom FAB — those are direct user intent.
    //   - reverseLayout=true on the LazyColumn — handles "stick to
    //     bottom while user is at bottom" natively.

    // T128: tightened from 90 dp (google-ai-edge/gallery) to 32 dp.
    // 90 dp made the JumpToBottom FAB appear well before the user had
    // really left the bottom — users reported the "Quick to bottom" button
    // appearing too often. 32 dp is roughly half the floating tool-bar height, so the
    // visual definition of "at bottom" lines up with what the user sees.
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    // T138 phase 2 v3: ground-truth bottom test via layoutInfo. If
    // LazyList currently renders the visual-bottom item (data-index 0
    // under reverseLayout) and its bottom edge sits within `threshold`
    // px of the viewport bottom, the user is visually at the bottom.
    // `firstVisibleItemIndex` is unreliable here: when a single message
    // emission expands into N tool / text flat items, firstVisible
    // drifts by N in one frame (logcat showed jumps of 5+ on a
    // multi-tool turn). Anchor on the rendered set instead.
    val isNearBottom = rememberIsNearBottom(listState = listState, tagScroll = tagScroll, nearBottomThresholdPx = nearBottomThresholdPx)
    val contentOverflows = rememberContentOverflows(listState = listState, tagScroll = tagScroll, isNearBottom = isNearBottom)
    val itemSizeByIndex = remember(listState) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { vis ->
                for (it in vis) {
                    val cached = itemSizeByIndex[it.index]
                    if (cached == null || cached != it.size) itemSizeByIndex[it.index] = it.size
                }
            }
    }
    // [T-android-scroll-fab-first-entry] Average observed item size, used to
    // estimate the height of indices we've never had on-screen. The pure
    // cache-only approach (b58e9515) was one-sided: belowSum (already-scrolled-
    // past items, cached) worked, but aboveSum summed indices we hadn't reached
    // yet, which are NEVER cached at the moment they're off-screen ABOVE — so
    // aboveSum stayed 0 forever and the up-button never appeared (logged:
    // aboveSum=0 across an entire top-scroll, even with all 68 items eventually
    // cached). Estimating unknown indices by the running average makes BOTH
    // ends symmetric and direction-independent. The average is a real measured
    // mean (not a wild min/avg-of-visible extrapolation that the commit comment
    // warned against), so it tracks actual pixel distance closely enough for a
    // one-viewport threshold.
    val avgItemSize = remember(listState, itemSizeByIndex) {
        derivedStateOf {
            val sizes = itemSizeByIndex.values
            if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size
        }
    }
    // [T-android-scrollbtn-turn-walk] The isFarFromTop / isFarFromBottom pair
    // that used to live here is gone, mirroring iOS dcdec3c5: the up-button's
    // visibility is now the shared `!isNearBottom` condition, so the separate
    // one-viewport-from-both-ends estimation has no remaining consumer.
    // `itemSizeByIndex` / `avgItemSize` above are deliberately KEPT — the
    // streaming glide (LE(streaming-content)) still uses the running average to
    // size its per-frame steps.

    // T138 phase 2 v3: separate "user scrolled away" intent from listState
    // position. isNearBottom flips false during the transient window where
    // new items are inserted at index 0 but listState still anchors on the
    // previous item — that is NOT user intent. Drive auto-follow off
    // `userScrolledAway` which only toggles on real user drags.
    val userScrolledAway_st = remember { mutableStateOf(false) }
    var userScrolledAway by userScrolledAway_st

    // [T-android-scrollbtn-turn-walk] Up-button turn-walk state, mirroring iOS
    // `lastJumpedUserId` (dcdec3c5). Holds the id of the user message the
    // up-button last jumped to, so a REPEATED tap walks one turn further back
    // instead of re-landing on the same turn. Reset to null whenever the
    // anchoring context changes — manual drag, jump-to-bottom, message-list
    // change, or session switch — so the next tap re-anchors to whatever the
    // user is currently looking at rather than continuing a stale sequence.
    val lastJumpedUserId_st = remember(sessionId) { mutableStateOf<String?>(null) }
    var lastJumpedUserId by lastJumpedUserId_st

    // [T-android-scrollbtn-turn-walk] Content changed (new/removed messages) —
    // the turn-walk anchor may no longer line up, so restart it on the next tap.
    // iOS does this in its snapshot-apply path; on Android the equivalent
    // trigger is the message list itself changing identity/size.
    LaunchedEffect(messages.size) { lastJumpedUserId = null }

    // [T-android-scrollbtn-turn-walk] Up-button action: walk backwards through
    // the conversation one USER turn at a time (iOS `scrollToPreviousUserTurn`).
    // Replaces the old "jump to the very first message":
    //   - First tap: scroll to the user message of the turn the viewport is
    //     currently in (nearest user message at or above the visual top).
    //   - Repeated taps (no intervening drag / new message): each goes one
    //     turn further back.
    //   - Already at the first turn: stay put, no overscroll.
    // A browse action — it must NOT clear `userScrolledAway`, so streaming
    // doesn't yank the user back down after they jump up (same rationale as
    // iOS leaving scrollMode untouched).
    //
    // Index resolution goes through the LazyColumn's stable item KEYS
    // ("user:<id>", set in FlatChatItem.UserBubble) rather than arithmetic on
    // message indices. Two things make raw arithmetic wrong here: one message
    // flattens into MANY rows (header / markdown blocks / tool blocks), and a
    // conditional resume-banner item sits at index 0 ahead of items(), shifting
    // every subsequent index by one. Keys are immune to both.
    val scrollToPreviousUserTurn = rememberScrollToPreviousUserTurn(
        messagesState = messages_st,
        listState = listState,
        tracedScrollToItem = tracedScrollToItem,
        lastJumpedUserIdState = lastJumpedUserId_st,
    )

    // [T-android-scroll-fab-reversed] TEMP diagnostic — capture BOTH FABs'
    // gates so we can verify the matrix (bottom=none, middle=both, top=down
    // only) and why the down-FAB is missing at the top. Remove after fix.
    LaunchedEffect(listState) {
        snapshotFlow {
            val up = !isNearBottom.value && messages.isNotEmpty()
            val down = userScrolledAway && contentOverflows.value && messages.isNotEmpty()
            "FABs up=$up down=$down | nearBottom=${isNearBottom.value} lastJumped=${lastJumpedUserId?.take(8)} scrolledAway=$userScrolledAway overflow=${contentOverflows.value} canFwd=${listState.canScrollForward} canBwd=${listState.canScrollBackward}"
        }.collect { AppLogger.debug("ScrollFAB2", it) }
    }

   // T-drag-send-queue: shared send-or-enqueue handler used by BOTH the
   // send-button tap and the swipe-up-to-send drag. Routes through
   // `viewModel.sendMessage(...)` which internally dispatches to
   // `enqueuePrompt()` when `_isStreaming.value` is true, so the message is
   // queued rather than dropped when the agent loop is mid-flight. Slash-
   // command input short-circuits to the command runner (mirrors the tap
   // path). Caller decides whether to invoke this — gating (canActivate,
   // armFraction, swipedUp) stays at the call site.
    val configuration = LocalConfiguration.current
    val hasHardwareKeyboard = configuration.keyboard ==
        android.content.res.Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden ==
        android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    val releaseComposerAfterSend: () -> Unit = {
        if (!hasHardwareKeyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    val performSendOrEnqueue = rememberPerformSendOrEnqueue(
        viewModel = viewModel,
        lastSendTimeMsState = lastSendTimeMs_st,
        noteSendForInputModePref = noteSendForInputModePref,
        coroutineScope = coroutineScope,
        tracedScrollToItem = tracedScrollToItem,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAway_st,
        releaseComposerAfterSend = releaseComposerAfterSend,
    )
    val lastInterruptMs_st = remember { mutableStateOf(0L) }
    var lastInterruptMs by lastInterruptMs_st
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Programmatic scrolls
    // (the streaming auto-follow glide, settle, pin-to-bottom) go through
    // `listState.scroll { }` / `scrollToItem`, which set
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "streaming auto-followed" (must NOT
    // touch focus). Without this the keyboard closed itself mid-stream and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    val isUserDragging_st = remember { mutableStateOf(false) }
    var isUserDragging by isUserDragging_st
    ScrollEffect1(
        listState = listState,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAway_st,
        lastJumpedUserIdState = lastJumpedUserId_st,
        lastInterruptMsState = lastInterruptMs_st,
        isUserDraggingState = isUserDragging_st,
    )
    // Re-engage follow whenever the user (manually or via FAB) returns
    // the viewport to the bottom.
    //
    // [T-android-stream-end-anchor-jump] Gate the clear on RECENT USER
    // INTERACTION. Without this, the final markdown re-render at the
    // streaming→idle edge briefly collapses the last assistant row's height
    // (large code blocks / tables / images finalizing their layout), which
    // clamps firstVisibleItemScrollOffset within the nearBottomThreshold for
    // one frame. isNearBottom flips true, this LE clears userScrolledAway,
    // and the stream-end settle LE — whose live-anchor check sees the same
    // bogus near-bottom offset — then scrollToItem()s the user back to
    // wherever the layout collapse parked them (often the start of the
    // current user message, because that's the "first content row" the
    // settle LE pins on). Only accept the clear when the user actually
    // dragged into this position recently — a real return-to-bottom gesture
    // always trails a DragInteraction.Stop, which lastInterruptMs records.
    // FAB taps also work because the FAB onClick path resets userScrolledAway
    // directly (line 1124) and never relies on this LE.
    LaunchedEffect(isNearBottom.value) {
        if (isNearBottom.value && userScrolledAway) {
            val sinceDragMs = System.currentTimeMillis() - lastInterruptMs
            // KEEP userScrolledAway when no recent drag — the at-bottom
            // reading came from a stream-end layout reflow, not a real
            // return-to-bottom gesture.
            if (sinceDragMs > 1500L) return@LaunchedEffect
            userScrolledAway = false
        }
    }
    // T169 / T170: an IME show/hide animates the LazyColumn's content area,
    // which can briefly register as a synthetic drag-stop and flip
    // userScrolledAway=true even though the user never actually scrolled.
    //
    // T170: only force-reset when we're actually back at the bottom. Earlier
    // behaviour of unconditionally clearing userScrolledAway hid the FAB on
    // users who had scrolled up to read history and then opened the keyboard
    // to send a follow-up — auto-follow then yanked them away from where
    // they were reading.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (userScrolledAway && isNearBottom.value) userScrolledAway = false
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row, so
    // LE(messages.size) below skips them. Without this collector the "Minis is
    // thinking…" placeholder stays parked behind the input bar until the first
    // streamed token finally bumps the auto-follow tuple.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            // Match the user-send path: clear any prior "scrolled away" flag
            // so the streaming auto-follow stays active for the new turn.
            userScrolledAway = false
            tracedScrollToItem("FORCE-SCROLL-TO-BOTTOM(resume/retry/rerun)", 0, 0)
        }
    }
    // Auto-scroll on user-send: explicit "show me the next response"
    // gesture, fires regardless of current scroll position.
    ScrollEffect2(
        messagesState = messages_st,
        lastUserAppendMsState = lastUserAppendMs_st,
        tracedScrollToItem = tracedScrollToItem,
        userScrolledAwayState = userScrolledAway_st,
    )
    // T128: streaming auto-follow when the user is at the bottom.
    //
    // T120 removed all per-token scroll calls assuming reverseLayout
    // would keep the bottom pinned natively. That's true for *new
    // LazyList items*, but a streaming text block grows by appending
    // characters into the same index-0 message item — its height
    // increases while LazyListState keeps firstVisibleItemIndex=0
    // and offset=0, so the new tokens push out below the viewport
    // (and behind the floating tool thumbnail). Result: users at the
    // bottom watched the FAB pop up, tapped it, and immediately had
    // to tap again as the next chunk landed.
    //
    // T170: align with iOS three-stage pin (initial scroll → wait for
    // layoutIfNeeded → re-pin to catch async self-sizing). After
    // scrollToItem(0) we await one frame then re-pin, which catches the
    // common case of a tool-pill + typing indicator inserted in the same
    // recomposition: the first scroll pins to the pre-grow position, the
    // second pin captures the post-self-sizing height. Suppressed when
    // the user is currently dragging — never compete with active touch.
    // T256: streaming auto-follow via snapshotFlow + conflate + sample.
    // Replaces the per-token `LaunchedEffect(lastAssistantStreamingKey)`
    // that pegged the Pixel 4a UI thread (95p frame 77ms / 29% janky) by
    // restarting the entire 3-stage scroll dance on every token. The new
    // pipeline:
    //   1. snapshotFlow emits a tuple per recomposition rather than the
    //      raw content string — content-length comparison is cheap.
    //   2. conflate() drops intermediate ticks the collector never saw.
    //   3. sample(150L) caps follow rate to ~6.5 Hz, matching iOS's
    //      80ms scroll-coalesce + 100ms layout-flush combined gate.
    // Stage 2 (frame settle) and stage 3 (220ms offset clip) move into a
    // separate edge-triggered LE that fires once per stream END, not per
    // token — async self-sizing / image height settling needs the safety
    // net but not at 50ms cadence.
    ScrollEffect3(
        viewModel = viewModel,
        messagesState = messages_st,
        listState = listState,
        tracedScrollToItem = tracedScrollToItem,
        avgItemSize = avgItemSize,
        userScrolledAwayState = userScrolledAway_st,
        lastInterruptMsState = lastInterruptMs_st,
    )
    // T256: stage-2/3 settle moved out of the per-token LE — runs once on
    // the stream-end edge (isAwaitingModelResponse stays false but the
    // assistant message growth rate drops to zero). Catches async
    // self-sizing of code blocks / tables / images that finish layout
    // beyond the last sample tick.
    // T-streaming-side-channel: derive streamingNowFlag from isStreaming
    // VM flow directly (turn-level signal) rather than reading per-token
    // assistant content, so this state doesn't tick during a stream.
    val streamingNowFlag = isStreaming
    ScrollEffect4(
        isStreamingState = isStreaming_st,
        listState = listState,
        lastStreamEndMsState = lastStreamEndMs_st,
        tracedScrollToItem = tracedScrollToItem,
        userScrolledAwayState = userScrolledAway_st,
        lastInterruptMsState = lastInterruptMs_st,
        streamingNowFlag = streamingNowFlag,
    )
    // T170: layoutInfo-driven safety net. iOS pins via contentSize KVO on
    // every height change; on Compose the equivalent is a snapshotFlow over
    // layoutInfo. This catches:
    //   - Async self-sizing that completes >1 frame after the streaming LE
    //   - StreamingMarkdownText reflow when its width changes (orientation,
    //     IME pad)
    //   - Tool-block height changes (e.g. terminal preview expanding)
    // Triggers ONLY when the bottom item is visible AND the user hasn't
    // scrolled away — otherwise we'd fight the user's manual scroll.
    ScrollEffect5(
        listState = listState,
        tracedScrollToItem = tracedScrollToItem,
        tracedScrollBy = tracedScrollBy,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAway_st,
    )
    // T170: when a user-initiated drag ends near the bottom, give the
    // streaming follow path one extra pin in case content grew during the
    // drag (we suppressed the streaming LE while isScrollInProgress). This
    // mirrors iOS settleAfterInteraction.
    ScrollEffect6(
        viewModel = viewModel,
        isStreamingState = isStreaming_st,
        listState = listState,
        tracedScrollToItem = tracedScrollToItem,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAway_st,
        lastInterruptMsState = lastInterruptMs_st,
    )

    // [T-android-updown-fab-asymmetry] Position-driven safety net for the
    // down-button's gate.
    //
    // The re-arm above keys on the `isScrollInProgress` FALLING EDGE, so it only
    // covers viewport movement that Compose reports as a scroll — drags and
    // flings. `listState.scrollToItem` (used by every programmatic jump here,
    // including the up-button's turn-walk) moves the viewport WITHOUT ever
    // setting that flag, so no edge arrives and `userScrolledAway` stays false
    // while the user is nowhere near the bottom. The down-button is then hidden
    // and the only way back is a manual drag — the reported bug.
    //
    // Keying on the position itself closes that hole for ALL movers, present and
    // future, instead of patching each call site. Deliberately one-directional:
    // this only ARMS the flag. Clearing stays with the existing handlers (drag
    // stop, jump-to-bottom, send, session switch), because "we drifted near the
    // bottom" must not be mistaken for "the user chose to follow again" — that
    // conflation is what T138/T170 were fixed for.
    //
    // [T-android-stream-follow-dies-after-first-paragraph] The arm must NOT
    // fire on content growth. `isNearBottom` goes false for two very different
    // reasons: (a) the user moved the viewport away, and (b) the streaming
    // message grew taller than the viewport while the viewport stood still.
    // Case (b) happens on essentially every multi-paragraph reply — the row at
    // index 0 gets taller under reverseLayout, so the bottom edge slides out
    // from under us before the next follow-scroll runs. Arming on (b) was
    // self-defeating: the flag killed the streaming auto-follow that would
    // have restored the bottom, so the reply scrolled through its first
    // paragraph and then froze with the rest hidden behind the tool bar and
    // composer, with no drag anywhere in the trace (observed 20:35:56.145 —
    // `scrolledAway=true` 1.4s after send, zero DragInteraction).
    //
    // Suppress the arm outright while a turn is streaming. A first attempt
    // tried to keep the net alive during streaming by demanding a "viewport
    // moved" signal (isScrollInProgress, or firstVisibleItemIndex changing).
    // That is not a valid discriminator and the on-device trace disproved it:
    //
    //   [20:40:45.050] ScrollFollow userScrolledAway ARMED
    //                  inProgress=false firstIdx=1 streaming=true
    //
    // A growing row at index 0 pushes the previous row across the viewport
    // boundary, so firstVisibleItemIndex advances 0→1 with the user's finger
    // nowhere near the screen — indistinguishable, by position alone, from a
    // real scroll. There is no purely positional test that separates the two.
    //
    // So gate on the turn instead: while isStreaming is true, the streaming
    // auto-follow owns the viewport and an off-bottom reading is expected
    // drift, not intent. Real user gestures during a stream are still honoured
    // — the DragInteraction.Stop handler above sets userScrolledAway directly
    // and does not route through this net. Outside a stream the net keeps its
    // original T-android-updown-fab-asymmetry behaviour untouched, which is
    // the case it was actually built for (programmatic turn-walk jumps).
    //
    // [T-android-stream-end-arm-race] The window must extend PAST the
    // streaming→idle edge. The final markdown re-render (code blocks, tables
    // and lists finishing their real layout) lands AFTER _isStreaming flips
    // false, and it changes row heights exactly like streaming growth did:
    //
    //   21:20:52.959  _isStreaming=false
    //   21:20:53.066  coldPrewarm.done + firstItem re-compose  (final reflow)
    //   21:20:53.076  ARMED  inProgress=false firstIdx=1 streaming=false
    //
    // 117 ms after the flag cleared, so the isStreaming gate no longer
    // covered it and the reflow armed userScrolledAway with the user's finger
    // nowhere near the screen. That in turn made the stream-end settle LE
    // (which sleeps 220 ms then bails on `if (userScrolledAway)`) a no-op, so
    // the transcript was left parked mid-reply with the down-FAB showing —
    // reported as "output stopped but the view isn't at the bottom".
    //
    // STREAM_END_ARM_GRACE_MS keeps the suppression alive across that reflow.
    // It is deliberately longer than the settle LE's own 220 ms delay so the
    // re-pin gets to run first and legitimately restore the bottom; a real
    // drag inside the window still arms the flag directly via the
    // DragInteraction.Stop handler, which never routes through this net.
    ScrollEffect7(
        viewModel = viewModel,
        listState = listState,
        lastStreamEndMsState = lastStreamEndMs_st,
        isNearBottom = isNearBottom,
        userScrolledAwayState = userScrolledAway_st,
    )

    // Auto-focus input on new sessions so keyboard pops up immediately.
    //
    // T176: theme switch (Activity recreate) re-enters this LE before the
    // composer's `Modifier.focusRequester(inputFocusRequester)` has been
    // attached for the new composition. requestFocus() then throws
    // `FocusRequester is not initialized` and the process crashes. Guard
    // with try/catch — we lose nothing if the focus call is a no-op on
    // the recreated activity (the user wasn't typing anyway), and the
    // common new-session path still works because the 300 ms delay lets
    // the Modifier attach.
    LaunchedEffect(Unit) {
        if (sessionId.startsWith("__new__")) {
            // Small delay to let the layout settle before requesting focus
            kotlinx.coroutines.delay(300)
            try {
                inputFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                AppLogger.debug(
                    tagScroll,
                    "auto-focus skipped: FocusRequester not attached (likely activity recreate / theme switch): ${e.message}",
                )
            }
        }
    }

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, true)) }
    val toolStatusBarEnabled_st = remember {
        mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_STATUS_BAR, false))
    }
    var toolStatusBarEnabled by toolStatusBarEnabled_st
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    val editingSession_st = remember { mutableStateOf<com.openminis.app.data.db.ChatSessionEntity?>(null) }
    var editingSession by editingSession_st
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_TOOL_STATUS_BAR ->
                    toolStatusBarEnabled = sp.getBoolean(key, false)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    val previewUrl_st = remember { mutableStateOf<String?>(null) }
    var previewUrl by previewUrl_st
    // T146: dedicated state for the immersive HTML preview path. Holding
    // both a `holder` and `fullscreen` flag (rather than two separate
    // states) ensures the same WebView survives the sheet→fullscreen
    // toggle without reloading the page (iOS parity, WebPreviewSheet.swift).
    val htmlPreviewHolder_st = remember {
        mutableStateOf<com.openminis.app.ui.preview.WebViewHolder?>(null)
    }
    var htmlPreviewHolder by htmlPreviewHolder_st
    val htmlPreviewFallbackTitle_st = remember { mutableStateOf("") }
    var htmlPreviewFallbackTitle by htmlPreviewFallbackTitle_st
    val htmlPreviewFullscreen_st = remember { mutableStateOf(false) }
    var htmlPreviewFullscreen by htmlPreviewFullscreen_st
    val appCtx = context.applicationContext
    // Pinned-shortcut deep link: minis://session/<id>/<resource-path>
    // consumes here on first composition iff this screen is showing the
    // matching session; opens fullscreen HTML preview backed by a fresh
    // holder. Pending state is left untouched when a different chat is on
    // screen so the right ChatScreen instance still consumes it later.
    SessionLaunchEffect(
        sessionId = sessionId,
        context = context,
        htmlPreviewHolderState = htmlPreviewHolder_st,
        htmlPreviewFallbackTitleState = htmlPreviewFallbackTitle_st,
        htmlPreviewFullscreenState = htmlPreviewFullscreen_st,
        appCtx = appCtx,
    )
    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    val previewImageGallery_st = remember {
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    }
    var previewImageGallery by previewImageGallery_st
    // T-pwa-2: long-press on an HTML attachment chip opens the
    // "Add to Home Screen" sheet for that attachment.
    val webAppSheetTarget_st = remember { mutableStateOf<InputAttachment?>(null) }
    var webAppSheetTarget by webAppSheetTarget_st
    val urlClickHandler = rememberUrlClickHandler(
        onPreviewAttachment = onPreviewAttachment,
        context = context,
        viewModel = viewModel,
        attachmentsState = attachments_st,
        coroutineScope = coroutineScope,
        previewUrlState = previewUrl_st,
        previewImageGalleryState = previewImageGallery_st,
    )

    // Auto-present the in-app preview when a shell tool's stdout emits an
    // OSC MinisOpenURL marker (via /usr/local/bin/minis-open). The broker is
    // populated by ChatViewModel's shell lineCallback; forwarding the URL
    // into `urlClickHandler` routes it exactly like a chat-link tap —
    // http(s)/about → UrlPreviewSheet, minis:// deep links → DeepLinkHandler,
    // minis://<host>/<path> → in-app file preview by extension.
    val pendingMinisOpenUrl by com.openminis.app.terminal.MinisOpenUrlBroker.pendingUrl
        .collectAsState()
    val minisOpenTerminalVisible by com.openminis.app.terminal.MinisOpenUrlBroker.terminalVisible
        .collectAsState()
    LaunchedEffect(pendingMinisOpenUrl, minisOpenTerminalVisible) {
        val url = pendingMinisOpenUrl ?: return@LaunchedEffect
        // The fullscreen TerminalScreen owns the broker while it's up —
        // let it present its own web preview (mirrors iOS ISHTerminalView)
        // so we don't try to open a sheet on a covered ChatScreen.
        if (minisOpenTerminalVisible) return@LaunchedEffect
        urlClickHandler(url.toString())
        com.openminis.app.terminal.MinisOpenUrlBroker.consume()
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // resolved to a session-owned File before being handed to the gallery.
    val markdownImageTapHandler = rememberMarkdownImageTapHandler(
        sessionId = sessionId,
        context = context,
        messagesState = messages_st,
        coroutineScope = coroutineScope,
        previewImageGalleryState = previewImageGallery_st,
        urlClickHandler = urlClickHandler,
    )

    CompositionLocalProvider(
        LocalBrowserTabPool provides viewModel.browserTabPool,
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalToolStatusBarEnabled provides toolStatusBarEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
        LocalMarkdownSessionId provides sessionId,
        // Long-press a file or image in the chat to quote it into the next message.
        LocalQuoteFile provides { file, name ->
            viewModel.quoteAttachmentFile(file, name)
            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
            keyboardController?.show()
        },
    ) {
    // The chat page is white (the redesign); only the drawer keeps the grey page.
    val pagePalette = LocalChatPalette.current
    Scaffold(
        containerColor = pagePalette.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatTopBar(
                onBotDetails = onBotDetails,
                onBack = onBack,
                isTwoPane = isTwoPane,
                onToggleSidebar = onToggleSidebar,
                onOpenDrawer = onOpenDrawer,
                onNewChat = onNewChat,
                onBrowseChatFiles = onBrowseChatFiles,
                context = context,
                viewModel = viewModel,
                isStreamingState = isStreaming_st,
                teamState = team_st,
                currentBot = currentBot,
                listState = listState,
                showChatMenuState = showChatMenu_st,
                showVirtualScreenViewerState = showVirtualScreenViewer_st,
                showClearChatDialogState = showClearChatDialog_st,
                pagePalette = pagePalette,
            )
        },
       snackbarHost = { SnackbarHost(snackbarHostState) },
   ) { padding ->
        val chatPaneWidthPx_st = remember { mutableStateOf(0) }
        var chatPaneWidthPx by chatPaneWidthPx_st
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onGloballyPositioned { chatPaneWidthPx = it.size.width },
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // the streaming auto-follow's programmatic glide, so the old code
            // hid the keyboard + cleared focus on every streaming tick, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            ChatMessagePane(
                sessionId = sessionId,
                providerRepository = providerRepository,
                onOpenSetupStep = onOpenSetupStep,
                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                onMoveToSession = onMoveToSession,
                onPreviewAttachment = onPreviewAttachment,
                context = context,
                keyboardController = keyboardController,
                focusManager = focusManager,
                viewModel = viewModel,
                messages_st = messages_st,
                messagesState = messages_st,
                hasOlderMessages_st = hasOlderMessages_st,
                isStreaming_st = isStreaming_st,
                isStreamingState = isStreaming_st,
                generatingMessageId_st = generatingMessageId_st,
                replySpeechState_st = replySpeechState_st,
                replySpeechStateState = replySpeechState_st,
                canResume_st = canResume_st,
                compactProgress_st = compactProgress_st,
                error_st = error_st,
                sessionTitle_st = sessionTitle_st,
                listState = listState,
                inputText_st = inputText_st,
                inputTextState = inputText_st,
                lastUserAppendMs_st = lastUserAppendMs_st,
                lastUserAppendMsState = lastUserAppendMs_st,
                inputFocusRequester = inputFocusRequester,
                coroutineScope = coroutineScope,
                showAttachMenu_st = showAttachMenu_st,
                deleteSingleMessageTargetId_st = deleteSingleMessageTargetId_st,
                regenerateTarget_st = regenerateTarget_st,
                messageMenuTargetState = messageMenuTarget_st,
                deleteFromHereTargetId_st = deleteFromHereTargetId_st,
                compactAboveTargetId_st = compactAboveTargetId_st,
                tracedScrollToItem = tracedScrollToItem,
                isNearBottom = isNearBottom,
                contentOverflows = contentOverflows,
                userScrolledAway_st = userScrolledAway_st,
                userScrolledAwayState = userScrolledAway_st,
                lastJumpedUserId_st = lastJumpedUserId_st,
                lastInterruptMs_st = lastInterruptMs_st,
                toolStatusBarEnabledState = toolStatusBarEnabled_st,
                previewImageGallery_st = previewImageGallery_st,
                urlClickHandler = urlClickHandler,
                chatPaneWidthPxState = chatPaneWidthPx_st,
            )

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            ChatBottomArea(
                sessionId = sessionId,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                onNewChat = onNewChat,
                onMoveToSession = onMoveToSession,
                onPreviewAttachment = onPreviewAttachment,
                context = context,
                keyboardController = keyboardController,
                viewModel = viewModel,
                messages_st = messages_st,
                messagesState = messages_st,
                isStreaming_st = isStreaming_st,
                isStreamingState = isStreaming_st,
                currentBot = currentBot,
                replySpeechStateState = replySpeechState_st,
                modelName_st = modelName_st,
                attachments_st = attachments_st,
                attachmentsState = attachments_st,
                pastedTexts_st = pastedTexts_st,
                ensureMicPermissionFlow = ensureMicPermissionFlow,
                inputText_st = inputText_st,
                inputTextState = inputText_st,
                inputFieldValue_st = inputFieldValue_st,
                lastSendTimeMs_st = lastSendTimeMs_st,
                voiceUsedSinceClear_st = voiceUsedSinceClear_st,
                noteSendForInputModePref = noteSendForInputModePref,
                inputFocusRequester = inputFocusRequester,
                inputFocused_st = inputFocused_st,
                sendSwipeProgress_st = sendSwipeProgress_st,
                sendSwipeProgressState = sendSwipeProgress_st,
                sendSwipeLocation_st = sendSwipeLocation_st,
                sendSwipeLocationState = sendSwipeLocation_st,
                swipeThresholdPx = swipeThresholdPx,
                swipeArmFraction = swipeArmFraction,
                swipeHapticOffsetPx = swipeHapticOffsetPx,
                swipeArrowHalfPx = swipeArrowHalfPx,
                swipeHaptics = swipeHaptics,
                coroutineScope = coroutineScope,
                showModelPicker_st = showModelPicker_st,
                showAttachMenu_st = showAttachMenu_st,
                showAgentPresetSheet_st = showAgentPresetSheet_st,
                showMoveSheet_st = showMoveSheet_st,
                showMoveSheetState = showMoveSheet_st,
                showClearChatDialogState = showClearChatDialog_st,
                deleteSingleMessageTargetIdState = deleteSingleMessageTargetId_st,
                regenerateTargetState = regenerateTarget_st,
                deleteFromHereTargetIdState = deleteFromHereTargetId_st,
                compactAboveTargetIdState = compactAboveTargetId_st,
                showNewChatStopDialogState = showNewChatStopDialog_st,
                showEnhancedCacheDialogState = showEnhancedCacheDialog_st,
                mediaPickerLauncher = mediaPickerLauncher,
                launchCamera = launchCamera,
                cameraPermissionLauncher = cameraPermissionLauncher,
                filePickerLauncher = filePickerLauncher,
                tracedScrollToItem = tracedScrollToItem,
                userScrolledAway_st = userScrolledAway_st,
                releaseComposerAfterSend = releaseComposerAfterSend,
                performSendOrEnqueue = performSendOrEnqueue,
                editingSessionState = editingSession_st,
                chatInputFontScale = chatInputFontScale,
                previewImageGallery_st = previewImageGallery_st,
                webAppSheetTarget_st = webAppSheetTarget_st,
            )
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = LocalChatPalette.current.background.let { page ->
                            listOf(page, page.copy(alpha = 0f))
                        },
                    )
                )
        )
        }
    }

    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet = false },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet = false },
        )
    }

    // Session Config Sheet (grouping prompt, skills, mcps, memory)
    if (showSessionConfigSheet) {
        SessionConfigSheet(
            viewModel = viewModel,
            onDismiss = { showSessionConfigSheet = false },
            onOpenPrompt = { showSessionAdvancedSettings = true },
            onOpenSkills = { showSkillsSheet = true },
            onOpenMcps = { showMcpsSheet = true },
            onOpenMemory = { viewModel.toggleMemorySheet() },
            onOpenTokenUsage = { showTokenUsageSheet = true },
        )
    }

    // GH#32/#35: session-local advanced settings sheet.
    if (showSessionAdvancedSettings) {
        SessionAdvancedSettingsSheet(
            sessionId = sessionId,
            chatRepository = chatRepository,
            viewModel = viewModel,
            onDismiss = { showSessionAdvancedSettings = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet = false
            },
            onDismiss = { showThinkingLevelSheet = false },
        )
    }

    if (showVirtualScreenViewer) {
        com.openminis.app.ui.settings.VirtualScreenViewerDialog(onDismiss = { showVirtualScreenViewer = false })
    }

    // Model Picker bottom sheet
    ChatModelPickerHost(providerRepository = providerRepository, viewModel = viewModel, showModelPickerState = showModelPicker_st)

    // Offload permission dialog
    OffloadPermissionDialog()

    // Pending ask_user_question card (same QuestionCenter as the Web Remote)
    AskUserQuestionDialog(sessionId = sessionId)

    // One-time dangerous-command approval (same ApprovalSeam as Web Remote).
    DangerousOperationApprovalDialog(sessionId = sessionId)

    // URL preview sheet — shown when a markdown link is tapped
    previewUrl?.let { url ->
        com.openminis.app.ui.components.UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }

    // T146: immersive HTML preview — bottom sheet (90% height) by default,
    // with a Fullscreen button that swaps to a Dialog-based fullscreen
    // surface using the SAME WebViewHolder so the page never reloads.
    htmlPreviewHolder?.let { holder ->
        val onFullDismiss = {
            holder.destroy()
            htmlPreviewHolder = null
            htmlPreviewFallbackTitle = ""
            htmlPreviewFullscreen = false
        }
        if (htmlPreviewFullscreen) {
            com.openminis.app.ui.preview.WebPreviewFullscreenScreen(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                onCollapseToSheet = {
                    holder.detach()
                    htmlPreviewFullscreen = false
                },
                onDismiss = onFullDismiss,
            )
        } else {
            com.openminis.app.ui.preview.WebPreviewBottomSheet(
                holder = holder,
                fallbackTitle = htmlPreviewFallbackTitle,
                pinSessionId = sessionId,
                onExpandFullscreen = {
                    holder.detach()
                    htmlPreviewFullscreen = true
                },
                onDismiss = onFullDismiss,
            )
        }
    }

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewImageGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = { previewImageGallery = null },
        )
    }

    // T-pwa-2: Add-to-Home-Screen sheet, hosted at screen level so it can
    // outlive the chip that triggered it (the chip Box may scroll out of
    // composition while the sheet is up).
    webAppSheetTarget?.let { target ->
        com.openminis.app.webapp.AddToHomeSheet(
            source = com.openminis.app.webapp.WebAppSource.ChatAttachment(
                uri = target.uri,
                fileName = target.fileName,
                sessionId = sessionId,
                sessionTitle = null,
            ),
            onDismiss = { webAppSheetTarget = null },
        )
    }
    } // CompositionLocalProvider
}



