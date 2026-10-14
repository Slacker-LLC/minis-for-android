package com.openminis.app.ui.chat

import com.openminis.app.agent.*

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ThumbUp
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Page projection. Execution and its lifetime belong to AgentSessionStore. */
class ChatViewModel(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    context: Context,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {
    internal val agentSession = com.openminis.app.agent.AgentSessionStore.getOrCreate(sessionId) {
        com.openminis.app.agent.AgentSession(sessionId, chatRepository, providerRepository, context.applicationContext, memoryRepository, skillRepository, mcpRepository)
    }
    internal val sessionId get() = agentSession.sessionId
    internal val chatRepository get() = agentSession.chatRepository
    internal val providerRepository get() = agentSession.providerRepository
    internal val context get() = agentSession.context
    val memoryRepository get() = agentSession.memoryRepository
    val skillRepository get() = agentSession.skillRepository
    val mcpRepository get() = agentSession.mcpRepository
    internal val execution get() = agentSession.execution
    internal val sessionScope get() = agentSession.sessionScope
    internal val mediaStore get() = agentSession.mediaStore
    internal val messageMapper get() = agentSession.messageMapper
    internal val titleGenerator get() = agentSession.titleGenerator
    internal val _messages get() = agentSession._messages
    val messages get() = agentSession.messages
    internal val _visibleMessageCap get() = agentSession._visibleMessageCap
    val visibleMessageCap get() = agentSession.visibleMessageCap
    internal val _streamingById get() = agentSession._streamingById
    val streamingById get() = agentSession.streamingById
    internal val streamFlushStates get() = agentSession.streamFlushStates
    internal val _inputText get() = agentSession._inputText
    val inputText get() = agentSession.inputText
    internal val _pendingCaret get() = agentSession._pendingCaret
    val pendingCaret get() = agentSession.pendingCaret
    internal val _isStreaming get() = agentSession._isStreaming
    val isStreaming get() = agentSession.isStreaming
    internal val _generatingMessageId get() = agentSession._generatingMessageId
    val generatingMessageId get() = agentSession.generatingMessageId
    internal val _selectedToolDetailId get() = agentSession._selectedToolDetailId
    val selectedToolDetailId get() = agentSession.selectedToolDetailId
    internal val _canResume get() = agentSession._canResume
    val canResume get() = agentSession.canResume
    internal val _resumeAfterCrash get() = agentSession._resumeAfterCrash
    val resumeAfterCrash get() = agentSession.resumeAfterCrash
    internal val _editingMessageId get() = agentSession._editingMessageId
    val editingMessageId get() = agentSession.editingMessageId
    internal val _error get() = agentSession._error
    val error get() = agentSession.error
    internal val _modelName get() = agentSession._modelName
    val modelName get() = agentSession.modelName
    internal val sessionLoaded get() = agentSession.sessionLoaded
    internal val _sessionTitle get() = agentSession._sessionTitle
    val sessionTitle get() = agentSession.sessionTitle
    internal val _sessionCategory get() = agentSession._sessionCategory
    val sessionCategory get() = agentSession.sessionCategory
    internal val _quotedTexts get() = agentSession._quotedTexts
    val quotedTexts get() = agentSession.quotedTexts
    internal val _pastedTexts get() = agentSession._pastedTexts
    val pastedTexts get() = agentSession.pastedTexts
    internal var nextPasteId get() = agentSession.nextPasteId
        set(value) { agentSession.nextPasteId = value }
    internal val _imageBudgetEvent get() = agentSession._imageBudgetEvent
    val imageBudgetEvent get() = agentSession.imageBudgetEvent
    internal val _requestBudgetEvent get() = agentSession._requestBudgetEvent
    val requestBudgetEvent get() = agentSession.requestBudgetEvent
    internal val _forceScrollToBottom get() = agentSession._forceScrollToBottom
    val forceScrollToBottom get() = agentSession.forceScrollToBottom
    internal val _providerName get() = agentSession._providerName
    val providerName get() = agentSession.providerName
    internal val _fallbackTrigger get() = agentSession._fallbackTrigger
    val fallbackTrigger get() = agentSession.fallbackTrigger
    internal val _activeEntryId get() = agentSession._activeEntryId
    val activeEntryId get() = agentSession.activeEntryId
    internal val _promptQueue get() = agentSession._promptQueue
    val promptQueue get() = agentSession.promptQueue
    internal val _lastTurnContextTokens get() = agentSession._lastTurnContextTokens
    val lastTurnContextTokens get() = agentSession.lastTurnContextTokens
    internal val conversation get() = agentSession.conversation
    val compactSummary get() = agentSession.compactSummary
    internal val _compactProgress get() = agentSession._compactProgress
    val compactProgress get() = agentSession.compactProgress
    internal val _isCompacting get() = agentSession._isCompacting
    val isCompacting get() = agentSession.isCompacting
    internal val compactionSummarizer get() = agentSession.compactionSummarizer
    internal var compactJob get() = agentSession.compactJob
        set(value) { agentSession.compactJob = value }
    internal val _autoRetryAttempt get() = agentSession._autoRetryAttempt
    val autoRetryAttempt get() = agentSession.autoRetryAttempt
    internal val _autoRetryCountdown get() = agentSession._autoRetryCountdown
    val autoRetryCountdown get() = agentSession.autoRetryCountdown
    internal var pendingAssistantTurn get() = agentSession.pendingAssistantTurn
        set(value) { agentSession.pendingAssistantTurn = value }
    internal var pendingMediaCommitJob get() = agentSession.pendingMediaCommitJob
        set(value) { agentSession.pendingMediaCommitJob = value }
    internal var currentProvider get() = agentSession.currentProvider
        set(value) { agentSession.currentProvider = value }
    internal var currentModel get() = agentSession.currentModel
        set(value) { agentSession.currentModel = value }
    internal var sessionSource get() = agentSession.sessionSource
        set(value) { agentSession.sessionSource = value }
    internal var activeRunId get() = agentSession.activeRunId
        set(value) { agentSession.activeRunId = value }
    internal var runCheckpointRecorder get() = agentSession.runCheckpointRecorder
        set(value) { agentSession.runCheckpointRecorder = value }
    internal val runCheckpointTranscript get() = agentSession.runCheckpointTranscript
    internal val agentHistory get() = agentSession.agentHistory
    internal val pendingExternalMessages get() = agentSession.pendingExternalMessages
    var chatOnlyForNextTurn get() = agentSession.chatOnlyForNextTurn
        set(value) { agentSession.chatOnlyForNextTurn = value }
    internal val agentTools get() = agentSession.agentTools
    internal val toolLoopDetector get() = agentSession.toolLoopDetector
    internal val turnStreamHost get() = agentSession.turnStreamHost
    internal val attachmentPreparer get() = agentSession.attachmentPreparer
    internal val toolRoundHost get() = agentSession.toolRoundHost
    internal var _browserTabPoolRef get() = agentSession._browserTabPoolRef
        set(value) { agentSession._browserTabPoolRef = value }
    val browserTabPool get() = agentSession.browserTabPool
    internal val _showBrowserSheet get() = agentSession._showBrowserSheet
    val showBrowserSheet get() = agentSession.showBrowserSheet
    internal val _showMemorySheet get() = agentSession._showMemorySheet
    val showMemorySheet get() = agentSession.showMemorySheet
    internal val _clearChatConfirmRequested get() = agentSession._clearChatConfirmRequested
    val clearChatConfirmRequested get() = agentSession.clearChatConfirmRequested
    internal val _memoryToolRecords get() = agentSession._memoryToolRecords
    val memoryToolRecords get() = agentSession.memoryToolRecords
    internal val _memoryEnabled get() = agentSession._memoryEnabled
    val memoryEnabled get() = agentSession.memoryEnabled
    internal val _thinkingLevel get() = agentSession._thinkingLevel
    val thinkingLevel get() = agentSession.thinkingLevel
    internal val _enhancedCacheEnabled get() = agentSession._enhancedCacheEnabled
    val enhancedCacheEnabled get() = agentSession.enhancedCacheEnabled
    val showEnhancedCacheToggle get() = agentSession.showEnhancedCacheToggle
    internal val _fastModeEnabled get() = agentSession._fastModeEnabled
    val fastModeEnabled get() = agentSession.fastModeEnabled
    internal val _autoCompactEnabled get() = agentSession._autoCompactEnabled
    val autoCompactEnabled get() = agentSession.autoCompactEnabled
    val showFastModeToggle get() = agentSession.showFastModeToggle
    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()
    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()
    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()
    internal var savedInputBeforeSlash: String? = null
    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()
    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()
    internal val _mentionAnchor = MutableStateFlow(-1)
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()
    val currentModelSupportsReasoning get() = agentSession.currentModelSupportsReasoning
    val currentModelSupportsTemperature get() = agentSession.currentModelSupportsTemperature
    internal val currentModelMaxThinkingLevel get() = agentSession.currentModelMaxThinkingLevel
    val availableThinkingLevels get() = agentSession.availableThinkingLevels
    val currentModelContextWindow get() = agentSession.currentModelContextWindow
    val currentModelMaxOutputTokens get() = agentSession.currentModelMaxOutputTokens
    internal var pendingSendText get() = agentSession.pendingSendText
        set(value) { agentSession.pendingSendText = value }
    internal val _showCompactBeforeSendPrompt get() = agentSession._showCompactBeforeSendPrompt
    val showCompactBeforeSendPrompt get() = agentSession.showCompactBeforeSendPrompt
    internal val isDraft get() = agentSession.isDraft
    internal val initialGroupId get() = agentSession.initialGroupId
    internal val initialFolderId get() = agentSession.initialFolderId
    internal var realSessionId get() = agentSession.realSessionId
        set(value) { agentSession.realSessionId = value }
    internal val hasNoRow get() = agentSession.hasNoRow
    internal var pendingThinkingOverride get() = agentSession.pendingThinkingOverride
        set(value) { agentSession.pendingThinkingOverride = value }
    val isBlankDraft get() = agentSession.isBlankDraft
    internal val sessionEventEmitter get() = agentSession.sessionEventEmitter
    internal val activeSessionId get() = agentSession.activeSessionId
    val currentSessionId get() = agentSession.currentSessionId
    internal val _hasInjectedShareContent get() = agentSession._hasInjectedShareContent
    val hasInjectedShareContent get() = agentSession.hasInjectedShareContent
    val replySpeechState get() = agentSession.replySpeechState
    internal var replySpeechJob get() = agentSession.replySpeechJob
        set(value) { agentSession.replySpeechJob = value }
    internal var replyPlayer get() = agentSession.replyPlayer
        set(value) { agentSession.replyPlayer = value }
    internal val voiceConversationActive get() = agentSession.voiceConversationActive
    internal var lastRetryTimestamp get() = agentSession.lastRetryTimestamp
        set(value) { agentSession.lastRetryTimestamp = value }
    internal var lastToolKey get() = agentSession.lastToolKey
        set(value) { agentSession.lastToolKey = value }
    internal var lastToolCount get() = agentSession.lastToolCount
        set(value) { agentSession.lastToolCount = value }

    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → 编辑 → send. `truncateBeforeEdit`
            // provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    val listState: LazyListState = LazyListState(0, 0)

    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // RuntimePathRegistry holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            mountsProvider = {
                com.openminis.app.runtime.RuntimePathRegistry
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "model",
            icon = Icons.Default.ModelTraining,
            title = "Model",
            subtitle = "",
        ),
        SlashCommand(
            id = "permission",
            icon = Icons.Outlined.Security,
            title = "Permission",
            subtitle = "",
        ),
        SlashCommand(
            id = "goal",
            icon = Icons.Outlined.Flag,
            title = "Goal",
            subtitle = "",
        ),
        SlashCommand(
            id = "plan",
            icon = Icons.Default.Schedule,
            title = "Plan",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
        SlashCommand(
            id = "feedback",
            icon = Icons.Outlined.ThumbUp,
            title = "Feedback",
            subtitle = "",
        ),
        SlashCommand(
            id = "export",
            icon = Icons.Outlined.FileDownload,
            title = "Export",
            subtitle = "",
        ),
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
    )

    val attachments get() = agentSession.attachments

    companion object {
        internal const val TAG = "ChatViewModel"
        internal val MAX_COMPACT_LLM_CALLS get() = com.openminis.app.agent.AgentSession.MAX_COMPACT_LLM_CALLS
        internal val COMPACT_TIMEOUT_BASE_MS get() = com.openminis.app.agent.AgentSession.COMPACT_TIMEOUT_BASE_MS
        internal val COMPACT_TIMEOUT_PER_10K_CHARS_MS get() = com.openminis.app.agent.AgentSession.COMPACT_TIMEOUT_PER_10K_CHARS_MS
        internal val COMPACT_TIMEOUT_MAX_MS get() = com.openminis.app.agent.AgentSession.COMPACT_TIMEOUT_MAX_MS
        internal val PREFLIGHT_NON_BLOCKING_FIELDS get() = com.openminis.app.agent.AgentSession.PREFLIGHT_NON_BLOCKING_FIELDS
        internal val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS get() = com.openminis.app.agent.AgentSession.PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS
        internal val NEWLINE_FLUSH_MIN_CHARS get() = com.openminis.app.agent.AgentSession.NEWLINE_FLUSH_MIN_CHARS
        internal val NEWLINE_FLUSH_MAX_LEN get() = com.openminis.app.agent.AgentSession.NEWLINE_FLUSH_MAX_LEN
        val INITIAL_VISIBLE_MESSAGE_CAP get() = com.openminis.app.agent.AgentSession.INITIAL_VISIBLE_MESSAGE_CAP
        val VISIBLE_MESSAGE_CAP_STEP get() = com.openminis.app.agent.AgentSession.VISIBLE_MESSAGE_CAP_STEP
        val LONG_SESSION_THRESHOLD get() = com.openminis.app.agent.AgentSession.LONG_SESSION_THRESHOLD
        internal val IN_FLIGHT_TOOL_STATUSES get() = com.openminis.app.agent.AgentSession.IN_FLIGHT_TOOL_STATUSES
        internal val TAG_STREAM get() = com.openminis.app.agent.AgentSession.TAG_STREAM
        internal val PROMPT_FRAGMENT_TIMEOUT_MS get() = com.openminis.app.agent.AgentSession.PROMPT_FRAGMENT_TIMEOUT_MS
        internal val MAX_AGENT_TURNS get() = com.openminis.app.agent.AgentSession.MAX_AGENT_TURNS
        internal val RUN_CHECKPOINT_ENTRY_SOURCE get() = com.openminis.app.agent.AgentSession.RUN_CHECKPOINT_ENTRY_SOURCE
        internal val RUN_CHECKPOINT_TEXT_KIND get() = com.openminis.app.agent.AgentSession.RUN_CHECKPOINT_TEXT_KIND
        internal val MIN_MAX_TOKENS get() = com.openminis.app.agent.AgentSession.MIN_MAX_TOKENS
        internal val GLOBAL_MAX_TOKENS_CEILING get() = com.openminis.app.agent.AgentSession.GLOBAL_MAX_TOKENS_CEILING
        val CANCELLED_MARKER get() = com.openminis.app.agent.AgentSession.CANCELLED_MARKER
        internal val AUTO_RETRY_DELAYS_SEC get() = com.openminis.app.agent.AgentSession.AUTO_RETRY_DELAYS_SEC
        internal fun joinDraftWithSnippet(draft: String, snippet: String) = com.openminis.app.agent.AgentSession.joinDraftWithSnippet(draft, snippet)
        internal fun canRetryMessage(message: ChatMessage, hasProvider: Boolean) = com.openminis.app.agent.AgentSession.canRetryMessage(message, hasProvider)
        internal fun compactTimeoutMsFor(transcriptChars: Int) = com.openminis.app.agent.AgentSession.compactTimeoutMsFor(transcriptChars)
        internal fun preflightEmptyStringAllowed(tool: String, field: String) = com.openminis.app.agent.AgentSession.preflightEmptyStringAllowed(tool, field)
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ) = com.openminis.app.agent.AgentSession.preflightValidateToolCallImpl(name, args, tools)
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }
}
