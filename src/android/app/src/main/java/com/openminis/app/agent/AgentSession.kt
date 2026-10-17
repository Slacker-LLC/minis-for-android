package com.openminis.app.agent

import com.openminis.app.ui.chat.*

import android.content.Context
import android.util.Log
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import com.openminis.app.agent.RunCheckpointRecorder
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.CompactBudget
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.provider.selectableThinkingLevels
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.runtime.ToolCallValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.openminis.app.data.repository.isEntryProviderEnabled

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

/**
 * Run state updates only after the database mutation has completed
 * successfully. Keeping this ordering explicit prevents a failed or
 * cancelled delete from leaving the in-memory conversation ahead of Room.
 */
internal suspend fun runAfterDatabaseDelete(
    delete: suspend () -> Unit,
    afterCommit: suspend () -> Unit,
) {
    delete()
    afterCommit()
}

class AgentSession(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) {

    internal val execution = AgentExecution()
    internal val sessionScope get() = execution.scope

   companion object {
       internal const val TAG = "AgentSession"

        /**
         * [T-android-append-to-input-eats-draft] Core snippet joiner.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            if (draft.isBlank()) return "$cleaned "
            val separator = if (draft.last().isWhitespace()) "" else " "
            return draft + separator + cleaned + " "
        }

        /** A retry can start only for a non-empty user turn with a provider. */
        internal fun canRetryMessage(message: ChatMessage, hasProvider: Boolean): Boolean =
            message.role == "user" && message.content.isNotBlank() && hasProvider

        // [T-android-compact-runaway] Keep these names in the ViewModel for
        // compatibility with the upstream JVM contract; the values themselves
        // live in CompactBudget so the runtime and tests share one source.
        internal const val MAX_COMPACT_LLM_CALLS = CompactBudget.MAX_LLM_CALLS
        internal const val COMPACT_TIMEOUT_BASE_MS = CompactBudget.TIMEOUT_BASE_MS
        internal const val COMPACT_TIMEOUT_PER_10K_CHARS_MS =
            CompactBudget.TIMEOUT_PER_10K_CHARS_MS
        internal const val COMPACT_TIMEOUT_MAX_MS = CompactBudget.TIMEOUT_MAX_MS

        internal fun compactTimeoutMsFor(transcriptChars: Int): Long =
            CompactBudget.timeoutMsFor(transcriptChars)


        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        internal val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        internal val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
            "update_goal" to setOf("goal"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a AgentSession and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String? =
            ToolCallValidator.preflight(name, args, tools)
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        internal const val NEWLINE_FLUSH_MIN_CHARS = 50
        internal const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"
        // Guest-backed prompt enrichment is best-effort; it must not hold the
        // provider turn while the Direct Ubuntu runtime is starting or recovering.
        internal const val PROMPT_FRAGMENT_TIMEOUT_MS = 2_000L
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        internal const val MAX_AGENT_TURNS = 200
        /** [T-android-run-checkpoint] Entry source recorded for a chat-driven run. */
        internal const val RUN_CHECKPOINT_ENTRY_SOURCE = "chat-ui"
        /** [T-android-run-checkpoint] Block kind of streamed assistant text. */
        internal const val RUN_CHECKPOINT_TEXT_KIND = "text"
        internal const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        internal const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        /** Pi-style recent-context retention target after compaction. Rather
         * than keeping a fixed number of user turns, keep as many complete
         * recent rounds as fit inside this token budget. */
        /** Hard message cap prevents a pathological tool-heavy round from
         * defeating compaction even when token estimation is optimistic. */
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */
        internal val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

        /**
         * Page factories live on ChatViewModel; execution is never constructed by a page lifecycle.
         */

    }

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)
    internal val messageMapper = ChatMessageMapper(mediaStore.mediaBaseDir) { path -> visionPlaceholderFor(path) }
    internal val titleGenerator = SessionTitleGenerator(
        context, chatRepository, providerRepository, sessionScope,
        object : SessionTitleGenerator.Host {
            override val sessionId: String get() = realSessionId.ifEmpty { this@AgentSession.sessionId }
            override fun currentTitle(): String = _sessionTitle.value
            override fun messages(): List<ChatMessage> = _messages.value
            override fun currentProvider(): LLMProvider? = this@AgentSession.currentProvider
            override fun titleApplied(sessionId: String, title: String, category: String?) {
                _sessionTitle.value = title
                com.openminis.app.tools.android.AndroidDebugSessionStore.update(sessionId) { it.copy(sessionTitle = title) }
                _sessionCategory.value = category
            }
            override fun fallbackTitleApplied(sessionId: String, title: String) {
                _sessionTitle.value = title
                com.openminis.app.tools.android.AndroidDebugSessionStore.update(sessionId) { it.copy(sessionTitle = title) }
            }
        },
    )

    internal val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    internal val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */


    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    internal val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    internal class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }
    internal val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `AgentSessionStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    internal val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()


    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the scroll state on the
     * page projection guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */


    internal val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    internal val _generatingMessageId = MutableStateFlow<String?>(null)
    val generatingMessageId: StateFlow<String?> = _generatingMessageId.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to AgentSessionUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    internal val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /** The resume offered now follows a crash or stall of the previous app run, not a stop in this one. */
    internal val _resumeAfterCrash = MutableStateFlow(false)
    val resumeAfterCrash: StateFlow<Boolean> = _resumeAfterCrash.asStateFlow()

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    internal val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val sessionLoaded = MutableStateFlow(false)

    internal val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

   internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
   val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    /** Selections quoted for the next message; shown as cards above the composer, sent as a leading quote. */
    internal val _quotedTexts = MutableStateFlow<List<String>>(emptyList())
    val quotedTexts: StateFlow<List<String>> = _quotedTexts.asStateFlow()
    internal val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()
    internal var nextPasteId: Int = 1

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [RequestImageBudget.apply] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    internal val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    internal val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    internal val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    internal val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    internal val conversation get() = execution.conversation
    val compactSummary: StateFlow<String?> = conversation.compactSummary

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    data class CompactProgress(
        val startedAtMs: Long,
        val depth: Int = 0,
        val callsIssued: Int = 0,
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        val timeoutSeconds: Int = 0,
    )

    internal val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    internal val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    internal val compactionSummarizer = CompactionSummarizer(
        provider = { currentProvider },
        modelContextWindow = { currentModel?.contextWindow },
        onProgress = { depth, calls ->
            _compactProgress.value = _compactProgress.value?.copy(depth = depth, callsIssued = calls)
        },
    )

    /** Job for the active manual/context compaction run. */
    internal var compactJob: Job? = null
    internal var sessionLoadJob: Job? = null


    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    internal val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    internal val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    internal val isMutating = MutableStateFlow(false)

    /** A history edit uses the same predecessor fence as model turns. */
    internal fun launchHistoryMutation(block: suspend () -> Unit): Job = execution.launchTurn(
        onStarted = { isMutating.value = true; _isStreaming.value = false },
        onSettled = { isMutating.value = false },
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            pendingMediaCommitJob?.join()
        }
        // Reloading operations may deliberately reset the UI readiness flag.
        // Await the real preceding load, never a flag this mutation must restore.
        sessionLoadJob?.join()
        try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { setInlineError(error) }
    }

    internal fun launchAgentTurn(
        request: com.openminis.app.agent.AgentTurnHandle? = null,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ): Job = execution.launchTurn(
        request,
        onStarted = { _isStreaming.value = true },
        onSettled = { _isStreaming.value = false; startNextQueuedRequest() },
    ) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pendingMediaCommitJob?.join() }
            kotlin.coroutines.coroutineContext.ensureActive()
            kotlinx.coroutines.coroutineScope { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            request?.record(com.openminis.app.agent.AgentTurnOutcome.Failed(e.message ?: "turn_setup_failed"))
            setInlineError(e)
        } finally {
            activeRunId?.let { run -> settleAcceptedRequests(run,
                if (!kotlin.coroutines.coroutineContext.isActive) AgentTurnOutcome.Cancelled
                else request?.recordedOutcome ?: activeRunOutcome) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                request?.userMessageId?.let { id -> chatRepository.dao.setPendingRequestState(activeSessionId, id, "NEEDS_REVIEW") }
                restorePendingRequests()
            }
        }
    }

    @Volatile internal var pendingAssistantTurn: PendingAssistantTurn? = null
    internal val requestCommitMutex = kotlinx.coroutines.sync.Mutex()
    internal var pendingMediaCommitJob: Job? = null
    internal var currentProvider: LLMProvider? = null
    internal var currentModel: LLMModel? = null
    @Volatile internal var sessionSource: String? = null
    @Volatile internal var activeRunOutcome: AgentTurnOutcome = AgentTurnOutcome.Failed("turn_did_not_finish")
    @Volatile internal var activeRunId: String? = null
    @Volatile internal var activeGoalTask: com.openminis.app.data.db.AgentTaskEntity? = null

    /** [T-android-run-checkpoint] Recovery log of the run this session drives. */
    @Volatile internal var runCheckpointRecorder: RunCheckpointRecorder? = null

    /** [T-android-run-checkpoint] Redacted transcript lines of the in-flight run. */
    internal val runCheckpointTranscript = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Structured agent history for the agent loop (contentParts-based). */
    internal val agentHistory get() = conversation.history
    internal val pendingExternalMessages = java.util.concurrent.ConcurrentLinkedQueue<MessageEntity>()

    @Volatile var chatOnlyForNextTurn = false

    /**
     * Every tool this session can expose, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     *
     * This is the pool deferred loading draws from ([ToolDeferral]); what a
     * request actually carries is [agentTools].
     */
    internal val agentToolPool: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            // [T-android-vision-group / GH#182] The main model's own vision
            // capability. When false but a Vision Group is configured, read_image
            // is still exposed and routes through the group (see
            // executeReadImageTool). Note pre-vision-group Android always passed
            // the default `true` here, so read_image was already always exposed;
            // threading the real flag lets a text-only model without a Vision
            // Group correctly LOSE the tool (iOS parity), while a configured
            // Vision Group keeps it.
            supportsImageInput = currentModel?.let {
                it.inputModalities?.map { m -> m.lowercase() }?.contains("image") == true
            } == true,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
            // Agent preset is Android-authoritative runtime state: the
            // session's effective preset (never null — falls back to the
            // default for new sessions) chooses the real tool configuration.
            presetToolset = com.openminis.app.remote.AgentPresetRegistry
                .presetForSession(context, sessionId).toolset,
            subAgentRosterNames = subAgentRoster()?.map { it.name },
            subAgentModelHandles = subAgentCallableModels().map { it.handle },
            subAgentChild = ChatSessionEntity.isSubAgentSource(sessionSource),
        ).let {
            // MCP servers the user switched off for this session are not offered (and their handler
            // refuses calls as well; see MCPToolHandler).
            com.openminis.app.mcp.client.MCPProvider.offeredInSession(it, activeSessionId)
        }

    /** Deferred tools this session has loaded; only ever grows (see [ToolDeferral]). */
    private val loadedDeferredTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun deferralEnabled() = com.openminis.app.tools.ToolDeferralPrefs.isEnabled(context)

    /** What the next model request carries: the resident tools plus the deferred ones loaded so far. */
    internal val agentTools: List<AgentToolDefinition>
        get() = com.openminis.app.tools.ToolDeferral.offered(
            agentToolPool,
            loadedDeferredTools + conversation.calledToolNames,
            deferralEnabled(),
        )

    /**
     * Loads the deferred pool tools named in [names] for the next request and returns the names actually
     * loaded. Anything the pool does not hold (withheld by chat-only, MCP session switches, the sub agent
     * child rule, a vision gate) or that is already resident loads nothing.
     */
    internal fun loadDeferredTools(names: Collection<String>): List<String> {
        if (chatOnlyForNextTurn || !deferralEnabled()) return emptyList()
        val pool = agentToolPool
        return names.mapNotNull { com.openminis.app.tools.ToolDeferral.findDeferred(pool, it)?.name }
            .distinct()
            .also { loadedDeferredTools.addAll(it) }
    }

    /** The system prompt's list of deferred tools; null when nothing is deferred or tools are off this turn. */
    internal fun deferredToolsPromptFragment(): String? {
        if (chatOnlyForNextTurn || !deferralEnabled()) return null
        return com.openminis.app.tools.ToolDeferral.indexFragment(
            com.openminis.app.tools.ToolDeferral.split(agentToolPool).deferred,
        )
    }

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector get() = conversation.toolLoopDetector

    internal val turnStreamHost = object : TurnStreamHost {
        override suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

        override fun updateMessage(assistantId: String, content: String, blocks: List<AssistantBlock>) {
            updateAssistantMessage(assistantId, content, true, blocks)
        }

        override fun recordCheckpointText(blockIndex: Int, text: String) {
            runCheckpointRecorder?.accept(RUN_CHECKPOINT_TEXT_KIND, blockIndex, text)
        }

        override fun hostedToolLabel(kind: String): String = context.getString(hostedToolLabelRes(kind))
        override fun hostedToolFailed(): String = context.getString(R.string.hosted_tool_failed)

        override fun saveMedia(data: ByteArray, mimeType: String) = mediaStore.saveMedia(
            data = data,
            mimeType = mimeType,
            sessionId = activeSessionId,
            originalFileName = null,
        )

        override val mediaBaseDir get() = mediaStore.mediaBaseDir
    }

    internal val attachmentPreparer = UserAttachmentPreparer(context, mediaStore) { _imageBudgetEvent.tryEmit(it) }

    internal val toolRoundHost = object : ToolRoundHost {
        override val chatOnly: Boolean get() = chatOnlyForNextTurn
        override val sessionId: String get() = activeSessionId

        override suspend fun refreshUi(assistantId: String, text: String, blocks: List<AssistantBlock>) {
            withContext(Dispatchers.Main) { updateAssistantMessage(assistantId, text, true, blocks) }
        }

        override fun preflight(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String? =
            preflightValidateToolCall(name, args, tools)

        override fun loadDeferred(name: String): String? =
            if (loadDeferredTools(listOf(name)).isEmpty()) null
            else com.openminis.app.tools.ToolDeferral.loadedOnDemandMessage(name)

        override suspend fun executeTool(
            name: String,
            argsJson: String,
            toolId: String,
            blocks: MutableList<AssistantBlock>,
            assistantId: String,
            currentText: String,
        ): ToolExecutionResult = this@AgentSession.executeTool(name, argsJson, toolId)
    }

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    internal var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser_use tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(context).also {
            it.setSession(activeSessionId)
            // Surface download start/finish/failure as system-info notices in
            // this chat. May fire from the pool's IO scope — hop to Main since
            // appendSystemInfo does a read-modify-write on _messages.
            it.onDownloadEvent = { text ->
                sessionScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    appendSystemInfo(text, "info")
                }
            }
            _browserTabPoolRef = it
        }
    }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to AgentSessionUiStateExt.kt.

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    internal val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()


    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            sessionScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()


    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()


    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            entry != null && instance != null &&
                entry.model.id.contains("gpt", ignoreCase = true) &&
                (instance.useResponsesAPI || isCodexOAuth)
        }.stateIn(
            sessionScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills,memory}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */


    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /** Whether the active provider/model honors a session temperature override. */
    val currentModelSupportsTemperature: Boolean
        get() = currentProvider?.supportsTemperatureOverride
            ?: (currentModel?.supportsReasoning != true)

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    internal val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val activeModel = _activeEntryId.value
                ?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id }?.model }
                ?: currentModel
            val declared = activeModel?.selectableThinkingLevels.orEmpty()
            if (declared.isNotEmpty()) return declared

            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()


    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
    )

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to AgentSessionUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state.
     *
     *  The business command set is the SAME as the Web Remote's
     *  `commands/list` (both are the Android-authoritative
     *  [com.openminis.app.remote.AgentCommandRegistry]); the App renders its
     *  own idioms, but every id dispatches to the same runtime handler.
     */

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to AgentSessionSlashExt.kt as AgentSession extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to AgentSessionMentionExt.kt as AgentSession extension functions.

    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    internal enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    internal var pendingSendText: String? = null

    internal val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    internal enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // sessionScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    internal val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    internal val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    /** A draft that has no database row yet: nothing persisted, nothing to clean up on exit. */
    internal val hasNoRow: Boolean get() = isDraft && realSessionId.isEmpty()

    /**
     * Thinking level chosen on a draft before its row exists. Materialising the row just to store a
     * preference left an empty conversation behind every time a chat was opened (the model entry's
     * default thinking level is applied on open), so the level is held here and written together
     * with the row on the first real send.
     */
    internal var pendingThinkingOverride: ThinkingLevel? = null

    /**
     * True for a draft where nothing has happened: no row, no attachment, no typed text, no run.
     * "New chat" on such a chat has nothing to replace, so it must not open another draft.
     */
    val isBlankDraft: Boolean
        get() = hasNoRow && _attachments.value.isEmpty() && _inputText.value.isBlank() && !_isStreaming.value

    /**
     * DSH-style event projection of this exact native VM. It does not own any
     * chat state: messages / streamingById remain canonical; the emitter only
     * records their ordered state transitions for remote replay.
     */
    internal val sessionEventEmitter = ChatSessionEventEmitter {
        realSessionId.ifEmpty { sessionId }
    }

    init {
        sessionScope.launch {
            _isStreaming.collect { streaming ->
                if (streaming) {
                    stopReplySpeech()
                } else {
                    _generatingMessageId.value = null
                }
            }
        }
        // Existing sessions need their durable event high-water mark before
        // any event can be framed. A send racing this small IO read simply
        // queues unsequenced intents inside SessionEventHub; activation drains
        // them in source order. Draft sessions activate in ensureSession().
        if (!isDraft) {
            sessionScope.launch(Dispatchers.IO) {
                runCatching { SessionEventHub.activateSession(chatRepository.dao, sessionId) }
                    .onFailure { Log.w(TAG, "event log activation failed: ${it.message}") }
            }
        }
        loadSession()
        sessionScope.launch {
            var observedInitial = false
            isStreaming.collect { running ->
                if (!observedInitial) {
                    observedInitial = true
                    if (!running) return@collect
                }
                sessionEventEmitter.runStatus(
                    isRunning = running,
                    modelName = modelName.value,
                    thinkingLevel = thinkingLevel.value.name,
                )
            }
        }
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        sessionScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                sessionScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        sessionScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the Main slot's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // [T-android-disabled-provider-still-selectable-via-slot]
                // Runtime re-resolution when a session's currently
                // active member has its provider DISABLED mid-session. The
                // Slot resolution already skips disabled members, but it only
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // runs while no provider is active. Once an entry resolves,
                // currentProvider is cached — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (applyNewChatDefaultModel()) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the slot result so a reload does not snap back.
                        _activeEntryId.value?.let {
                            persistBinding(com.openminis.app.data.model.ModelBinding.encodeEntry(it))
                        }
                    } else {
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE active entry=$before provider disabled and no slot fallback is available",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the Main slot, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    // Deep links are migrated to entry IDs in the slot UI phase;
                    // a legacy group id is not a runtime binding.
                    applyNewChatDefaultModel()
                }
            }
        }
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts (P2: Ubuntu 侧经
     * Direct Root bind 的 App-owned filesDir 目录) and browser artifacts land in a
     * single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    internal data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to AgentSessionUiStateExt.kt (extension functions).


    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    internal val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    val replySpeechState: StateFlow<com.openminis.app.speech.ReplySpeechState> =
        com.openminis.app.speech.VoiceOutputState.replySpeechState.asStateFlow()

    internal var replySpeechJob: Job? = null
    internal var replyPlayer: com.openminis.app.speech.ReadAloudPlayer? = null
    internal val voiceConversationActive = MutableStateFlow(false)

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    internal data class InjectedTurn(val newAssistantId: String)

    /** Retry the last agent turn (triggered by inline error Retry button).
     *
     *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
     *  Earlier behaviour blew away the entire failed assistant ChatMessage —
     *  including its already-completed tool_use cards — and reset
     *  agentHistory back to the last "real" user message, so on Retry every
     *  succeeded tool re-executed from scratch (the bug the user reported).
     *
     *  New behaviour:
     *   - Keep the assistant ChatMessage in the UI; clear its error sticker
     *     and the streaming/awaiting flags. Drop only tool blocks still in
     *     STREAMING / PENDING / RUNNING state — those have no matching
     *     tool_result and would orphan the request body.
     *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
     *     turn whose stream errored). If the tail is already user(tool_result),
     *     the failure happened on the NEXT LLM call before any output —
     *     history is already valid, leave it.
     *   - GC orphaned tool_result rows whose tool_use is no longer in
     *     agentHistory (defends against the API "unexpected tool_use_id" 400).
     *   - Sync the DB: if we popped a trailing assistant, drop just its
     *     persisted row so a re-load doesn't resurrect the failed turn.
     */
    internal var lastRetryTimestamp = 0L

    /** What the app did before giving up on a failed model request; the error banner shows it (see [setInlineError]). */
    internal var giveUpNote: String? = null

    // ── Repeat-tool reminder (DeepSeek Harness repeat-tool-guard) ──────────
    internal var lastToolKey: String? = null
    internal var lastToolCount = 0

    // ─── UI Helpers ──────────────────────────────────────────────────────

    // ─── Legacy tool execution methods (kept for compatibility) ───────────

    // ─── Misc Helpers ────────────────────────────────────────────────────

    internal fun close() {
        execution.close()
        replySpeechJob?.cancel()
        replySpeechJob = null
        replyPlayer?.shutdown()
        replyPlayer = null
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */

}
