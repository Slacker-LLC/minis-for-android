package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.openminis.app.R
import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.agent.AgentTurnOutcome
import com.openminis.app.agent.ToolBatchRepair
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.data.CompactBudget
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.LLMFailureClassifier
import com.openminis.app.provider.LLMProvider
import com.openminis.app.tools.AskUserQuestionTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel.Companion.AUTO_RETRY_DELAYS_SEC
import com.openminis.app.ui.chat.ChatViewModel.Companion.COMPACT_TIMEOUT_MAX_MS
import com.openminis.app.ui.chat.ChatViewModel.Companion.GLOBAL_MAX_TOKENS_CEILING
import com.openminis.app.ui.chat.ChatViewModel.Companion.MAX_AGENT_TURNS
import com.openminis.app.ui.chat.ChatViewModel.Companion.MIN_MAX_TOKENS
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG_STREAM
import com.openminis.app.ui.chat.ChatViewModel.Companion.preflightValidateToolCallImpl
import com.openminis.app.ui.chat.ChatViewModel.FallbackCandidate
import com.openminis.app.ui.chat.ChatViewModel.InLoopContextAction
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.openminis.app.data.repository.instance

/**
 * Unwrap exceptions thrown inside callbackFlow.
 * callbackFlow wraps internal throws into CancellationException(cause=original).
 * This extracts the original LLMError if present.
 */
/**
 * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
 * Mirrors iOS AIChatViewModel pre-API validation.
 *
 * Ensures: every assistant message with tool_use is immediately followed by a user
 * message containing the matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */
internal fun ChatViewModel.sanitizeAgentHistory() {
    // [T-tool-batch-repair-android] The pairing rule itself lives in ToolBatchRepair
    // so it can be tested directly. This call site is unchanged: it runs before every
    // API call, which is what makes a batch interrupted by a process death safe on
    // the next turn as well.
    val repaired = ToolBatchRepair.repair(agentHistory)
    if (!repaired.changed) return
    Log.w(
        TAG,
        "sanitize: injected " + repaired.injected + " placeholder tool_result(s), " +
            "dropped " + repaired.droppedResults + " orphan result(s) and " +
            repaired.droppedMessages + " empty message(s)",
    )
    agentHistory.clear()
    agentHistory.addAll(repaired.messages)
}

internal fun ChatViewModel.unwrapFlowException(e: Throwable): Throwable {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is com.openminis.app.data.model.LLMError) return cause
        cause = cause.cause
    }
    return e
}

/**
 * Compute max output tokens that fits within the remaining context window.
 * Logic mirrors iOS's dynamicMaxTokens():
 *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
 *
 * @param provider The current LLM provider (carries defaultMaxTokens).
 * @param lastContextTokens API-reported input token count from the last call (0 = first call).
 */
internal fun ChatViewModel.dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
    val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
    // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
    // The global cap means we never send more than 128K regardless of
    // what the model claims it can output.
    val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
    // Context window: model.contextWindow if known, else the shared
    // model-id heuristic. [T-anthropic-context-window] Route through
    // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
    // values apply here too, instead of the stale local "everything 200K"
    // copy that under-reported modern Claude/Gemini windows.
    val contextWindow = model.contextWindowTokens
    if (contextWindow <= 0) return maxOutputCeiling
    val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
    val remaining = contextWindow - inputTokens
    val clamped = maxOf(remaining, MIN_MAX_TOKENS)
    val result = minOf(maxOutputCeiling, clamped)
    if (result < maxOutputCeiling) {
        android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
    }
    return result
}

internal suspend fun ChatViewModel.runAgentLoop(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<FallbackCandidate> = emptyList(),
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    reusingAssistantId: String? = null,
): AgentTurnOutcome {
    mergeExternalHistory()
    // Refresh the cached identity before building the tool allow-list. This
    // also covers a Bot Direct Chat where the user sends immediately after
    // navigation, before the init coroutine has finished hydrating the VM.
    chatRepository.getSession(activeSessionId)?.let { session ->
        sessionBotId = session.botId
        sessionSource = session.source
    }
    AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
    pendingMediaCommitJob?.join()

    // GH#32: resolve the sparse session payload once per agent-loop run.
    // Settings changed while a loop is already in flight intentionally take
    // effect on the next run rather than changing semantics mid-turn.
    val sessionOverrides = runCatching {
        SessionOverrides.fromJson(
            chatRepository.getSession(activeSessionId)?.sessionOverrides,
        )
    }.onFailure { error ->
        AppLogger.warning(TAG, "session overrides unavailable for $activeSessionId: ${error.message}")
    }.getOrDefault(SessionOverrides())

    val botPrompt = runCatching {
        val botId = chatRepository.getSession(activeSessionId)?.botId
        botId?.let { botRepository?.getBot(it) }
    }.onFailure { error ->
        AppLogger.warning(TAG, "bot identity unavailable for $activeSessionId: ${error.message}")
    }.getOrNull()?.let { bot ->
        check(bot.enabled) { context.getString(R.string.bots_disabled_footer) }
        com.openminis.app.agent.BotContextResolver.systemPrompt(bot)
    }
    val effectiveSystemPrompt = AgentRunSetup.systemPrompt(
        callerPrompt = systemPrompt,
        botPrompt = botPrompt,
        sessionPrompt = sessionOverrides.systemPrompt,
        sessionSource = sessionSource,
        unattended = OffloadPermissionManager.isUnattendedSession(activeSessionId),
    )

    // [T-android-mem-probe-trust] Send-path context shape. The existing
    // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
    // log described the session as it was opened, never as it was sent —
    // and the send is where the memory goes. `historySize` alone says
    // nothing about payload: 17 messages carrying a 100 KB tool_result each
    // is a very different request from 1500 short ones. Logged once per
    // agent loop (not per turn) to stay cheap; the walk is O(parts) over
    // already-resident strings.
    runCatching {
        AppLogger.info(
            TAG_STREAM,
            AgentRunSetup.contextShape(agentHistory)
                .toLogString(com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()),
        )
    }
    // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
    // normally a single message id for the whole agent loop (iOS-parity:
    // multiple tool/text turns folded into one bubble). It is reassigned
    // ONLY when a queued mid-loop prompt is injected as a new turn: the
    // just-finished bubble is sealed and a fresh assistantId starts so the
    // queued user message renders BETWEEN them. `allToolBlocks` and
    // `accumulatedText` are also reset at that point so the new bubble
    // starts empty and `buildTurnParts(allToolBlocks, turnStartBlockIndex,
    // toolInputMap)` continues to slice only the current turn's blocks
    var assistantId = reusingAssistantId
        ?: if (_messages.value.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() && it.toolBlocks.isEmpty() } == true) {
            _messages.value.last().id
        } else {
            "assistant_${System.currentTimeMillis()}"
        }
    val allToolBlocks = mutableListOf<AssistantBlock>()
    // Per-tool ring of the most recent `accumulated` JSON snapshots emitted
    // by `LLMStreamChunk.ToolInputDelta`. Capped at TOOL_INPUT_CHUNK_RING_MAX
    // entries per tool id so memory stays bounded even on long streams.
    // The preflight validator below drains this on a blocked call so we
    // can reconstruct how the model assembled (or failed to assemble) the
    // args.
    val toolInputChunkRings: MutableMap<String, MutableList<String>> = mutableMapOf()
    var accumulatedText = ""
    var lastContextTokens = 0  // updated each turn from API usage

    // Fallback state — mirrors iOS streamWithGroupFallback
    var currentProvider = provider
    val remainingFallbacks = fallbackProviders.toMutableList()
    val fallbackReasons = mutableListOf<String>()

    // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
    val allToolInputs = mutableMapOf<String, String>()

    // Add placeholder assistant message (once). Mark as awaiting so the
    // "Minis is thinking" indicator shows during the initial request gap
    // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
    // T300: snapshot the user's current thinking level at message
    // creation so the renderer can hide Deep Thinking blocks for
    // turns the user explicitly asked not to surface, even when a
    // forced-reasoning model still streams reasoning_content.
    val turnThinkingLevel = _thinkingLevel.value
    withContext(Dispatchers.Main) {
        val currentList = _messages.value
        val existingIdx = currentList.indexOfFirst { it.id == assistantId }
        if (existingIdx >= 0) {
            val updated = currentList.toMutableList()
            val existing = updated[existingIdx]
            updated[existingIdx] = existing.copy(
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                toolBlocks = emptyList(),
                error = null,
                thinkingLevel = turnThinkingLevel,
            )
            _messages.value = updated
        } else {
            val assistantPlaceholder = ChatMessage(
                id = assistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = turnThinkingLevel,
            )
            _messages.value = currentList + assistantPlaceholder
            sessionEventEmitter.messageCreated(assistantPlaceholder)
        }
    }

    // Tracks whether the loop was exited via a `break` (any reason — no
    // tool calls, msgIdx safety, etc.) or fell off the end of the range.
    // Set false by every break path that *isn't* "the model wanted to
    // keep going past MAX_AGENT_TURNS". Without this flag the post-loop
    // tail can't tell the runaway path apart from a normal turn ending,
    // which previously slapped a fake "200 turns hit" error on every
    // ordinary completion.
    var loopExitedNormally = false
    var loopOutcome: AgentTurnOutcome = AgentTurnOutcome.Completed
    // [T-android-auto-compact-inloop] How many times the in-loop guard has
    // compacted during THIS runAgentLoop. Bounds compact-thrash: once the
    // cap is hit, a still-over-threshold history stops the turn rather than
    // compacting forever. Mirrors iOS maxInLoopCompactions.
    var inLoopCompactions = 0
    // [T-android-empty-after-toolresult-reminder] One-shot guard for the
    // "<system-reminder> + retry one round" recovery when the server returns
    // an empty response right after a tool result. Fires at most once per
    // runAgentLoop so it can never loop; if the reminder round is also empty
    // we surface a real error instead of a silent blank bubble. Mirrors iOS
    // AIChatViewModel.didInjectEmptyToolReminderThisRun.
    var didInjectEmptyToolReminder = false
    // [C6-android-model-failure-discipline] Side-effect facts of this agent
    // round, recorded where they happen so the retry gate can refuse to
    // re-issue a request after the round has produced effects. Eta tracks
    // `hostedToolStarted` per provider request (AgentModelRetry.kt:45-47);
    // Minis runs no provider-hosted tools and executes managed tools between
    // the turns of a round, so "a managed tool already ran here" and "a turn
    // of this round is already committed" are the equivalent facts.
    var roundToolExecuted = false
    var roundTurnCommitted = false
    // [C6-android-model-failure-discipline] Compactions spent on provider
    // context-overflow failures in this round, bounded by the ported
    // AgentContextBudget.MAX_OVERFLOW_ATTEMPTS (Eta AgentLoop.kt:129-140).
    var overflowAttempts = 0
    for (turn in 0 until MAX_AGENT_TURNS) {
        // Sanitize history before each API call (mirrors iOS pre-API validation)
        mergeExternalHistory()
        sanitizeAgentHistory()

        // Context window management: offload large tool outputs in older
        // messages to disk when the policy threshold for this model's
        // context window is crossed. Stubs in agentHistory still tell the
        // model where to file_read the original content. Mirrors iOS
        // AIChatViewModel.swift:4549.
        // [T-anthropic-context-window] Use contextWindowTokens (heuristic-
        // backed) instead of the raw nullable field, so offload triggers at
        // the correct fraction for heuristic-only Claude/Gemini models (1M)
        // rather than never firing when contextWindow is unset.
        // [T-context-window-live-read] Live read per loop turn — a stale
        // snapshot inside a long-running agent turn is exactly the iOS
        // fcc22b66 item-3 bug.
        effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
            val freed = ContextOffloader.offloadIfNeeded(
                SessionOffloadStore(context, activeSessionId), agentHistory,
                contextWindow = window,
                lastContextTokens = lastContextTokens,
            )
            if (freed > 0) {
                // The reading came from the previous response, before the offload. Take away what
                // the offload really freed so the in-loop guard judges the request that is about to
                // be sent, not the one that was.
                lastContextTokens = (lastContextTokens - freed).coerceAtLeast(0)
                if (_lastTurnContextTokens.value > 0) {
                    _lastTurnContextTokens.value = (_lastTurnContextTokens.value - freed).coerceAtLeast(0)
                }
            }
        }

        // [T-android-auto-compact-inloop] In-loop context guard (iOS
        // f70ac173). checkContextBeforeSend only runs at the SEND entry
        // point, so a single turn that fans out into many tool iterations
        // could blow past the thresholds mid-loop. Offload alone can't
        // recover when the bulk is the model's own text, and the turn would
        // slam into the provider's context ceiling.
        //
        // Runs AFTER offload so it judges the post-offload size.
        when (inLoopContextCheck(inLoopCompactions)) {
            InLoopContextAction.PROCEED -> {}
            InLoopContextAction.COMPACTED -> {
                // The next API call reads the freshly-compacted
                // effectiveAgentHistory automatically — compaction already
                // re-appends the recent turns, so no resume handoff is
                // needed. A compaction iteration is space management, not
                // task progress, so it must NOT consume a turn slot:
                // decrementing cancels this iteration's advance. The
                // MAX_AGENT_TURNS ceiling is never reset, and
                // maxInLoopCompactions bounds compact-thrash within a turn,
                // so a loop that keeps compacting cannot defeat the runaway
                // backstop.
                inLoopCompactions++
                continue
            }
            InLoopContextAction.STOP -> {
                // The loop cannot present a modal mid-flight, so stop
                // safely: user-visible notice + resumable, without the
                // turn-limit error overwrite.
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                )
                // [T-android-inloop-stop-thinking-orphan] Finalize the
                // assistant message before leaving the loop.
                //
                // The placeholder was created with isStreaming = true /
                // isAwaitingModelResponse = true. Only updateAssistantMessage
                // (isStreaming = false) or finalizeAtTurnLimit ever clears
                // those, and this branch reaches NEITHER: appendSystemInfo
                // appends a SEPARATE system row and never touches the
                // placeholder, while `loopExitedNormally = true` below
                // deliberately skips finalizeAtTurnLimit at the loop tail.
                //
                // Without this the bubble stays on "Minis is thinking"
                // forever — the streamJob's finally only clears the GLOBAL
                // _isStreaming, not the per-message flags. Reachable with no
                // failure at all: ContextPolicy gives every model with a
                // context window under 64K `exhaustedOnly = true`, so
                // crossing the exhaust line lands here directly.
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(
                        assistantId, accumulatedText, false, allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                    // Same orphan guard finalizeAtTurnLimit carries: the loop
                    // ran on IO while this hops to Main, so a late delta can
                    // re-add the side-channel entry after the drain, and
                    // mergeStreamingOverlay would then force isStreaming=true
                    // again with no further writer left to clear it.
                    clearStreamFlushState(assistantId)
                    if (_streamingById.value.containsKey(assistantId)) {
                        _streamingById.value = _streamingById.value - assistantId
                    }
                }
                // No persistAssistantTurn here: this guard runs BEFORE the
                // turn body, so nothing new has been produced yet and the
                // per-turn accumulators it would need
                // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                // are not in scope. Everything from previous turns was
                // already persisted by those turns.
                appendSystemInfo(
                    text = "Context is full and could not be reduced further. " +
                        "Tap Continue to resume, or start a new chat.",
                    iconKind = "compact",
                )
                _canResume.value = true
                // Android's equivalent of iOS's `hitTurnLimit = false`: this
                // is a deliberate stop, NOT the runaway-ceiling path, so the
                // post-loop tail must not slap a fake "hit 200 turns" error
                // on it. finalizeAtTurnLimit is skipped; the notice above is
                // the user-visible explanation.
                loopExitedNormally = true
                loopOutcome = AgentTurnOutcome.NeedsAttention("context_exhausted")
                break
            }
        }

        // Mark where this turn's blocks start in allToolBlocks so we can persist
        // only the NEW parts from this turn (not the full accumulated history).
        // Matches iOS's per-turn RawMessage persistence.
        val turnStartBlockIndex = allToolBlocks.size
        val pendingTurn = PendingAssistantTurn(assistantId, activeSessionId, turnStartBlockIndex)
        pendingAssistantTurn = pendingTurn
        // The live state of this response and how each stream chunk updates it.
        val stream = TurnStream(
            host = turnStreamHost,
            turn = turn,
            assistantId = assistantId,
            allToolBlocks = allToolBlocks,
            turnStartBlockIndex = turnStartBlockIndex,
            pendingTurn = pendingTurn,
            sessionEventEmitter = sessionEventEmitter,
            toolInputChunkRings = toolInputChunkRings,
            provider = { currentProvider },
            contextTokens = lastContextTokens,
            onContextTokens = {
                lastContextTokens = it
                _lastTurnContextTokens.value = it
            },
        )
        // GH#32: null allow-list inherits every agent tool; an explicit
        // empty list is chat-only. Keep this list stable for the whole
        // provider retry/fallback cycle of this turn.
        val turnTools = if (chatOnlyForNextTurn) {
            emptyList()
        } else {
            agentTools
        }
        val turnToolNames = buildSet {
            for (tool in turnTools) {
                add(tool.name)
                add(tool.apiName)
                val normName = tool.name.lowercase().filter { it.isLetterOrDigit() }
                if (normName.isNotEmpty()) add(normName)
                val normApi = tool.apiName.lowercase().filter { it.isLetterOrDigit() }
                if (normApi.isNotEmpty()) add(normApi)
                com.openminis.app.tools.runtime.ToolRegistry.aliasesFor(tool.name).forEach { alias ->
                    add(alias)
                    val normAlias = alias.lowercase().filter { it.isLetterOrDigit() }
                    if (normAlias.isNotEmpty()) add(normAlias)
                }
            }
        }
        // Stream the response — with auto-retry on transient errors, then fallback.
        // callbackFlow wraps throws into CancellationException(cause=LLMError),
        // so we catch at collect level and unwrap.
        var collectDone = false
        var retryAttempt = 0  // per-turn auto-retry counter (resets on each new turn)
        suspend fun discardAttemptBlocks() {
            val discarded = allToolBlocks.drop(turnStartBlockIndex)
            var removed = false
            try {
                withContext(Dispatchers.Main) {
                    while (allToolBlocks.size > turnStartBlockIndex) allToolBlocks.removeAt(allToolBlocks.size - 1)
                    updateAssistantMessage(assistantId, accumulatedText + stream.turnTextSb.toString(), true, allToolBlocks)
                    removed = true
                }
            } finally {
                if (removed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    AssistantTurnCodec.discardMedia(discarded, mediaStore.mediaBaseDir)
                }
            }
        }
        // [C6-android-model-failure-discipline] One rollback for every failed
        // non-terminal attempt: drop this turn's partial blocks (turns already
        // committed survive) and reset the per-attempt accumulators, including
        // the failed attempt's thinking. Eta discards the failed attempt's
        // reasoning before the next request (AgentModelRetry's
        // discardAttemptReasoning) instead of letting it reach the final answer,
        // which the panel buffer and the opaque reasoning blob both feed.
        suspend fun rollbackFailedAttempt() {
            // Roll back partial blocks from the failed stream attempt so the retried
            // stream's deltas don't double-append on top of stale content. Previous
            // turns (everything before turnStartBlockIndex) are preserved.
            if (allToolBlocks.size > turnStartBlockIndex) {
                // [T-android-fallback-text-rewind] Keep this turn's
                // already-streamed text on screen across the rollback.
                // `accumulatedText` only folds in `turnTextSb` after the
                // while loop completes successfully, so passing bare
                // `accumulatedText` here would visibly rewind everything
                // the user already read this turn. The next attempt
                // streams into a fresh `turnTextSb` and re-publishes
                // `accumulatedText + newTurnText`, so this transient
                // value is overwritten cleanly (no duplication).
                discardAttemptBlocks()
            }
            stream.resetForRetry()
        }
        // [T-eta-character-cards] A bound session freezes its character for this run: the live
        // card wins while the character exists, the binding's snapshot is the fallback. A
        // failure here must never stop the turn - an unreadable binding simply means no
        // character block.
        val roleplayTurn = if (activeSessionId.isNotBlank()) {
            runCatching {
                com.openminis.app.roleplay.CharacterRepository.resolveForSession(activeSessionId)
            }.getOrNull()
        } else null
        while (!collectDone) {
            try {
                // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                // Cache flag onto the active provider here — the single
                // choke point every turn passes through, regardless of how
                // currentProvider was (re)assigned by the fallback loop.
                // Non-Anthropic providers ignore it (cast fails silently).
                (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                    ?.enhancedCache = _enhancedCacheEnabled.value
                // Route through effectiveAgentHistory() so a populated
                // [_compactSummary] is prepended as a `<context-summary>`
                // user message. Falls through to the raw agentHistory when
                // no compact has happened, so the common path stays zero-copy.
                val requestMessages = RequestImageBudget.apply(effectiveAgentHistory(), activeSessionId) { _requestBudgetEvent.tryEmit(it) }
                // [T-eta-character-cards] A bound character adds its block and any lore or
                // depth projection to THIS request only - the stored transcript and the
                // history the next turn reads back are untouched.
                val roleplayProjection = roleplayTurn?.let { (binding, card) ->
                    com.openminis.app.roleplay.RoleplayTurnProjection.project(
                        messages = requestMessages,
                        card = card,
                        userName = binding.userName,
                        userDescription = binding.userDescription,
                        contextWindow = effectiveContextWindowTokens(),
                        // The story memory is stored per character, so it is read here rather
                        // than inside the (pure) projection.
                        memory = runCatching {
                            com.openminis.app.roleplay.CharacterMemoryRepository.snapshot(
                                context,
                                binding.characterId,
                            )
                        }.getOrNull(),
                    )
                }
                // Where this request's time went (TTFT vs streaming, tokens, tool calls). One line per
                // request, emitted on success, failure and cancellation alike. Read it next to the
                // device-side `VScreenTiming` lines to see whether the model or the screen is slow.
                val modelTelemetry = ModelTurnTelemetry(turn) { android.os.SystemClock.elapsedRealtime() }
                currentProvider.streamMessage(
                    messages = roleplayProjection?.messages ?: requestMessages,
                    systemPrompt = roleplayProjection
                        ?.let { "$effectiveSystemPrompt\n\n${it.characterBlock}" }
                        ?: effectiveSystemPrompt,
                    maxTokens = sessionOverrides.effectiveMaxTokens(
                        dynamicMaxTokens(currentProvider, lastContextTokens),
                    ),
                    temperature = sessionOverrides.temperature,
                    tools = turnTools,
                    thinkingLevel = if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF,
                ).onEach { modelTelemetry.onChunk(it) }.onCompletion {
                    modelTelemetry.finish()
                    AppLogger.info("ModelTiming", "model=${currentProvider.model.id} ${modelTelemetry.summary()}")
                }.collect { chunk -> stream.handle(chunk, accumulatedText) }  // end collect
                stream.finishStream(accumulatedText)
                collectDone = true
                // Stream completed without error — clear any lingering retry UI state.
                if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                }
            } catch (e: Exception) {
                if (e is CancellationException && e.cause == null) throw e  // real job cancellation
                val actual = unwrapFlowException(e)
                // [C6-android-model-failure-discipline] One classifier for every
                // failure shape a provider can raise - HTTP status, in-stream
                // error, transport exception, quota / billing / auth / parameter
                // - then one decision: retry the same provider, compact and
                // re-issue on context overflow, or terminate. Ported from Eta
                // agent/model/AgentModelFailure.kt + agent/model/AgentModelRetry.kt
                // (@ c15de97).
                val classification = LLMFailureClassifier.classify(actual)
                val isRateLimit = classification.isRateLimited
                val is5xx = classification.isHttp5xx
                val decision = LLMFailureClassifier.decide(
                    classification = classification,
                    retriesUsed = retryAttempt,
                    // AUTO_RETRY_DELAYS_SEC stays the only schedule: its size is
                    // the retry budget, its values are the backoff steps.
                    retryDelaysSec = AUTO_RETRY_DELAYS_SEC,
                    overflowAttemptsUsed = overflowAttempts,
                    effects = LLMFailureClassifier.RequestEffects(
                        toolStarted = roundToolExecuted,
                        outputCommitted = roundTurnCommitted,
                    ),
                )
                if (decision is LLMFailureClassifier.Decision.RetrySameProvider) {
                    val delaySec = decision.delaySec
                    retryAttempt += 1
                    val errDesc = actual.message ?: actual.javaClass.simpleName
                    Log.w(TAG, "🔁 ${decision.code} on ${currentProvider.model.displayName}, retry $retryAttempt/${AUTO_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc")
                    withContext(Dispatchers.Main) {
                        _autoRetryAttempt.value = retryAttempt
                        // Show the error inline on the streaming assistant message during countdown.
                        // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                        setTransientInlineError("$errDesc — retrying ($retryAttempt/${AUTO_RETRY_DELAYS_SEC.size})…")
                    }
                    try {
                        for (remaining in delaySec downTo 1) {
                            _autoRetryCountdown.value = remaining
                            kotlinx.coroutines.delay(1000)
                        }
                    } finally {
                        _autoRetryCountdown.value = 0
                    }
                    // Clear inline error so the retry attempt can start cleanly.
                    withContext(Dispatchers.Main) {
                        clearInlineError()
                    }
                    // [C6-android-model-failure-discipline] Cancel wins over the
                    // retry timer: the countdown above is a cancellable delay and
                    // the job is re-checked before any new request goes out.
                    coroutineContext.ensureActive()
                    rollbackFailedAttempt()
                    continue  // retry on same provider
                }
                // [C6-android-model-failure-discipline] Context overflow is not a
                // retry: compact the history and re-issue the turn, bounded by the
                // ported AgentContextBudget.MAX_OVERFLOW_ATTEMPTS (Eta
                // AgentLoop.kt:129-140). The countdown is skipped - there is
                // nothing to wait for, the request itself was too large.
                if (decision is LLMFailureClassifier.Decision.CompactAndRetry) {
                    overflowAttempts = decision.attempt
                    Log.w(
                        TAG,
                        "🧷 ${classification.code} — compacting (overflow attempt " +
                            "${decision.attempt}/${AgentContextBudget.MAX_OVERFLOW_ATTEMPTS}) before re-issuing the turn",
                    )
                    rollbackFailedAttempt()
                    appendSystemInfo(
                        text = context.getString(R.string.chat_context_overflow_retry, decision.attempt, AgentContextBudget.MAX_OVERFLOW_ATTEMPTS),
                        iconKind = "compact",
                    )
                    // compactAll() only answers through its callback on the real
                    // compaction path; its early returns (no provider, another
                    // compact in flight, no persisted anchor) never call it, so
                    // those cases are checked here instead of suspending the turn
                    // on a continuation that would never resume. The wait itself is
                    // bounded by the same wall-clock budget the compaction path runs
                    // under (CompactBudget.TIMEOUT_MAX_MS, plus a second of slack for
                    // the callback hop), so a lost callback degrades into a terminal
                    // failure rather than wedging the turn.
                    val canCompact = !_isCompacting.value &&
                        this@runAgentLoop.currentProvider != null &&
                        agentHistory.any { !it.dbMessageId.isNullOrEmpty() }
                    val compacted = canCompact &&
                        withTimeoutOrNull(COMPACT_TIMEOUT_MAX_MS + 1_000L) { awaitCompaction() } == true
                    if (compacted) {
                        // [T-android-auto-compact-inloop] Same invalidation the
                        // in-loop guard performs: the reading that triggered the
                        // compaction is stale once the history shrank, and no
                        // usage chunk arrives until an API call completes.
                        _lastTurnContextTokens.value = 0
                        continue
                    }
                    Log.w(TAG, "🧷 ${classification.code} — compaction unavailable, failing the turn")
                }
                // Retries exhausted or non-retryable — proceed to fallback / throw.
                _autoRetryAttempt.value = 0
                _autoRetryCountdown.value = 0
                // [T-android-timeout-while-running] Clear any transient
                // inline error from the prior retry attempts before we
                // either fall back (loop continues with a new provider)
                // or throw (terminal setInlineError below re-sets it
                // with the final non-retryable message). Without this,
                // a transient banner from the previous attempt could
                // linger as the new provider starts streaming — the
                // updateAssistantMessage(isStreaming=true) defense
                // catches it on the next delta, but clearing here
                // makes the intent explicit and avoids a one-frame
                // flash of the stale banner.
                withContext(Dispatchers.Main) { clearInlineError() }
                // [C6-android-model-failure-discipline] A round that already
                // produced effects does not fall back either: upstream marks such
                // a failure recoveryAllowed=false (AgentModelRetry.kt:45-47) and
                // the run ends with the completed tool results in place. The
                // rate-limit / 5xx fallback keeps working for rounds that have not
                // started a tool or committed a turn yet.
                val blockedByEffects = roundToolExecuted || roundTurnCommitted
                val shouldFallback = !blockedByEffects && (isRateLimit || is5xx ||
                    fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always)
                val nextCandidate = if (shouldFallback) remainingFallbacks.removeFirstOrNull() else null
                val next = nextCandidate?.provider
                if (next != null && nextCandidate != null) {
                    val reason = when {
                        isRateLimit -> "Rate limited"
                        actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
                        else -> actual.message ?: "Error"
                    }
                    // [T-android-model-indicator-flash-on-endpoint-retry]
                    // Same-model recovery is a TRANSPARENT retry, not a real
                    // model switch. A slot can hold several entries for the
                    // SAME modelId behind different provider instances/endpoints
                    // (e.g. duplicate provider credentials). When the first
                    // fails, ordered slot fallback moves to the next instance —
                    // same modelId, different endpoint — which should recover
                    // silently. Only flash the model capsule when the
                    // resolved modelId ACTUALLY changes; an endpoint/instance-
                    // only change must not surface to the UI.
                    val isRealModelChange = next.model.id != currentProvider.model.id
                    fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                    Log.i(TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                    currentProvider = next
                    // Also update class-level provider so the next sendMessage() starts from here
                    this@runAgentLoop.currentProvider = next
                    // Update top bar model info + active entry. (For a same-
                    // model endpoint recovery these are no-ops on the visible
                    // model name, but still keep activeEntryId / provider name
                    // in sync with the instance we actually used.)
                    _modelName.value = currentProvider.model.displayName
                    // Update activeEntryId so model picker reflects the switch.
                    // [T-android-fallback-entry-identity] Look the entry up by
                    // its OWN id, carried on the candidate. The previous
                    // `find { it.model.id == currentProvider.model.id }` was
                    // ambiguous: two instances can expose the same model id, so
                    // it returned whichever entry sits earlier in modelEntries.
                    // Observed in the field — falling back onto
                    // `deepseek-v4-flash` served by "DeekSeak" showed the
                    // provider as "Bailian OpenAI", because Bailian also has a
                    // `deepseek-v4-flash` entry and happened to be found first.
                    // That also poisoned activeEntryId and the persisted
                    // binding, so re-entering the session resumed on the WRONG
                    // instance.
                    val newEntry = providerRepository.config.value.modelEntries.find {
                        it.id == nextCandidate.entryId
                    }
                    if (newEntry != null) {
                        _activeEntryId.value = newEntry.id
                        currentModel = newEntry.model
                        val newInstance = providerRepository.instance(newEntry.providerInstanceId)
                        if (newInstance != null) {
                            _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
                        }
                    }
                    // Flash ONLY on a genuine model switch — never on a
                    // transparent same-model endpoint retry.
                    if (isRealModelChange) _fallbackTrigger.value++
                    // Persist the fallback model so re-entering the session starts from here
                    if (newEntry != null) {
                        persistBinding(com.openminis.app.data.model.ModelBinding.encodeEntry(newEntry.id))
                    }
                    val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${currentProvider.model.displayName}"
                    discardAttemptBlocks()
                    allToolBlocks.add(AssistantBlock(
                        id = "fallback_info_$turn",
                        kind = "info",
                        content = infoText,
                        toolTitle = "Switched model",
                        toolStatus = ToolBlockStatus.SUCCESS,
                    ))
                    // [T-android-fallback-text-rewind] Same as the retry-
                    // rollback path above: preserve this turn's streamed text
                    // (`turnTextSb`) on screen while we switch providers.
                    // `accumulatedText` hasn't folded it in yet, so bare
                    // `accumulatedText` would rewind the visible reply. The new
                    // provider streams into a fresh `turnTextSb` (reset just
                    // below) and re-publishes `accumulatedText + newTurnText`.
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText + stream.turnTextSb.toString(), true, allToolBlocks)
                    }
                    // Reset turn state for retry with new provider
                    stream.turnTextSb.setLength(0)
                    stream.currentTextBlockSb = null
                    // [T-android-tool-splits-reply-fix] Fresh stream from a
                    // different provider after rolling back this attempt.
                    stream.turnTextBlockIdx = -1
                    // [C6-android-model-failure-discipline] Drop the failed attempt's
                    // thinking before the next model runs: neither the panel text nor
                    // the opaque reasoning blob may leak from a failed attempt into
                    // the final answer (Eta discardAttemptReasoning).
                    stream.turnThinking.clear()
                    stream.turnReasoningBlob = null
                    pendingTurn.reasoningContent = null
                    stream.toolCalls.clear()
                    stream.toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                    // loop continues — will retry collect with currentProvider
                } else {
                    // [C6-android-model-failure-discipline] Terminal for this round.
                    // A retry-exhausted, overflow-exhausted or effects-blocked
                    // failure says something the provider's own error does not (how
                    // many attempts were spent, that the round already produced
                    // effects), so the classifier's message is surfaced once; a
                    // plain permanent classification already reads fine as the raw
                    // provider error that gets thrown below.
                    val terminal = decision as? LLMFailureClassifier.Decision.Terminal
                    if (terminal != null) {
                        Log.w(TAG, "⛔ ${terminal.code} — terminal, no retry: ${terminal.message}")
                        if (classification.retryable || classification.overflow || blockedByEffects) {
                            appendSystemInfo(text = terminal.message, iconKind = "compact")
                        }
                    }
                    // All fallbacks exhausted. Surface the trail of tried
                    // models AND the group members that were silently
                    // skipped (disabled / not logged in / hidden) so the
                    // user can see why fallback never reached them —
                    // mirrors iOS streamWithGroupFallback exhausted path.
                    if (allToolBlocks.drop(turnStartBlockIndex).any { it.mediaRef != null }) {
                        stream.materializeActiveTextBlock()
                        pendingTurn.toolInputs = allToolInputs + stream.toolCalls.associate { (id, _, args) -> id to args.toString() }
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + stream.turnTextSb.toString(), false, allToolBlocks)
                            val interruptedTools = allToolBlocks.drop(turnStartBlockIndex)
                                .filter { it.kind == "tool_use" }.map { it.id to it.toolName }
                            persistInterruptedMediaTurn(pendingTurn, allToolBlocks.toList(), interruptedTools, userStopped = false)
                        }
                    }
                    if (shouldFallback) {
                        val skipped = unavailableSlotMembers()
                        if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                            val trail = (fallbackReasons + skipped).joinToString("\n")
                            val finalDesc = actual.message ?: actual.toString()
                            throw com.openminis.app.data.model.LLMError.ProviderError("$trail\n$finalDesc")
                        }
                    }
                    throw actual  // re-throw unwrapped, all fallbacks exhausted
                }
            }
        }  // end while (!collectDone)

        // T307: materialise the per-turn StringBuilder ONCE at the
        // turn boundary. After this point everything is plain String
        // semantics — `turnText` participates in cross-turn accumulation
        // and gets persisted into agentHistory below.
        val turnText = stream.turnTextSb.toString()
        // Accumulate text across turns
        accumulatedText += turnText

        // Map toolUseId -> input JSON string for persistence (accumulated across turns)
        stream.toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
        val toolInputMap = allToolInputs
        pendingTurn.toolInputs = toolInputMap.toMap()
        val turnContent = withContext(Dispatchers.IO) {
            AssistantTurnCodec.build(allToolBlocks, turnStartBlockIndex, toolInputMap, mediaStore.mediaBaseDir)
        }
        val assistantParts = turnContent.parts
        // Prefer the opaque blob from LLMStreamChunk.ReasoningContent when the
        // provider emitted one — that path preserves empty strings (DeepSeek V4
        // `reasoning_content: ""` on non-thinking turns). Fall back to the
        // ThinkingDelta concatenation only when no blob arrived; in that case
        // an empty buffer becomes null (no field to round-trip).
        val turnReasoningContent: String? = stream.turnReasoningBlob
            ?: stream.turnThinking.toString().takeIf { it.isNotEmpty() }

        val historyMessage = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = turnText,
            contentParts = assistantParts,
            reasoningContent = turnReasoningContent,
            providerOutputItems = stream.turnProviderItems.toList(),
        )
        pendingTurn.historyMessage = historyMessage
        agentHistory.add(historyMessage)

        // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
        // provider) returns a turn with no visible text AND no tool calls.
        // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
        // logs to triage server-empty vs parser-drop vs swallowed-exception.
        if (turnText.isEmpty() && stream.toolCalls.isEmpty() &&
            allToolBlocks.drop(turnStartBlockIndex).none { it.mediaRef != null }) {
            AppLogger.warning(
                TAG_STREAM,
                "empty turn detected: turn=$turn finishReason=${stream.turnFinishReason} " +
                    "reasoningLen=${stream.turnThinking.length} reasoningBlobLen=${stream.turnReasoningBlob?.length ?: -1} " +
                    "model=${provider.model.id} provider=${provider.name}"
            )
        }

        // If no tool calls, we're done
        if (stream.toolCalls.isEmpty()) {
            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=${stream.turnFinishReason})")
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            val finalAssistantDbId = persistAssistantTurn(
                turnContent,
                stream.lastUsage,
                turnReasoningContent,
                pendingTurn = pendingTurn,
                modelSnapshot = modelAttributionSnapshot(currentProvider),
            )
            // Link the history entry to its stored row, as the tool-round path does. Without it a
            // plain-text turn stays unlinked until the session is reloaded, and Compact cannot
            // anchor on it (it walks back onto a user message and refuses to split the pair).
            backfillAssistantDbId(finalAssistantDbId)
            // [T-error-persist-android] Empty-response hint: the model ended a
            // turn (finish=stop/end_turn) with no visible text anywhere in the
            // reply and no tool blocks — the user just sees a blank bubble.
            // Surface a hint instead. When the context is near full, point at
            // compaction; otherwise suggest retry/switch. setInlineError
            // attaches + persists onto the (empty) assistant row so the hint
            // survives a reload too.
            val hasVisibleContent = accumulatedText.isNotBlank() ||
                allToolBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) || it.imageFilePath != null }

            // [T-android-silent-stream-drop] A turn's stopReason comes ONLY
            // from the SSE terminal event, which always carries a concrete
            // reason. A null therefore means the stream closed WITHOUT one —
            // the connection dropped mid-flight (a mid-flight throw would
            // have gone down the fallback/retry path instead, not here).
            //
            // Previously null was folded into `finishedCleanly`, so a
            // PARTIAL reply — bytes arrived, then the socket died — was
            // persisted silently as if complete. The reply just stopped with
            // no error and no way to retry: the "断流" reports. Mirrors iOS
            // d6604021.
            // Only the PARTIAL case is handled here. An EMPTY turn with a null
            // stopReason keeps falling through to the empty-turn handling
            // below (system-reminder retry, then the empty-response hint),
            // which already covers it well — re-routing it here would lose
            // that recovery.
            if (stream.turnFinishReason == null && hasVisibleContent) {
                AppLogger.warning(
                    TAG_STREAM,
                    "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                        "surfacing as an interrupted reply (turn=$turn)",
                )
                withContext(Dispatchers.Main) {
                    setInlineError(
                        context.getString(R.string.chat_error_stream_dropped_partial),
                    )
                }
                _canResume.value = true
                // Deliberate stop, not the runaway ceiling — keep the
                // post-loop tail from adding a fake turn-limit error.
                loopExitedNormally = true
                loopOutcome = AgentTurnOutcome.Failed("stream_closed_without_finish_reason")
                break
            }

            // A null stopReason reaching here means an EMPTY turn, which the
            // empty-turn path below is designed to recover; treat it as
            // "clean" for that purpose exactly as before.
            val finishedCleanly = stream.turnFinishReason == null ||
                stream.turnFinishReason == "stop" || stream.turnFinishReason == "end_turn"
            if (!hasVisibleContent && finishedCleanly) {
                // [T-android-empty-after-toolresult-reminder] Special case: the
                // server returned an empty turn right after a tool result. The
                // model owes a follow-up (next tool call or a final answer) but
                // stalled — the user sees a blank bubble with no explanation.
                // Inject a one-shot <system-reminder> into that tool result and
                // retry ONE round. The guard fires at most once per run, so it
                // can never loop; the SECOND empty falls through to the error
                // hint below. Mirrors iOS AIChatViewModel.swift empty-after-
                // tool-result path.
                //
                // The empty assistant turn was just appended (above) — drop it
                // so the tool result is the last message and the model gets a
                // clean "continue from here" prompt on the retry.
                val priorIsToolResult = agentHistory.size >= 2 &&
                    agentHistory[agentHistory.size - 2].contentParts.isNotEmpty() &&
                    agentHistory[agentHistory.size - 2].contentParts.all { it is AgentContentPart.ToolResult }
                if (!didInjectEmptyToolReminder && priorIsToolResult) {
                    didInjectEmptyToolReminder = true
                    AppLogger.warning(TAG_STREAM, "empty turn after tool result — injecting <system-reminder> and retrying one round (turn=$turn)")
                    // Remove the empty assistant turn we just added.
                    agentHistory.removeAt(agentHistory.size - 1)
                    // Inject the reminder into the last tool result's content.
                    val trIdx = agentHistory.size - 1
                    val trMsg = agentHistory[trIdx]
                    val reminder = "\n\n<system-reminder>The previous response was empty. A tool result was just provided and you MUST continue: respond with the next tool call(s) if more work is needed, or a final text answer for the user. Do not return an empty response.</system-reminder>"
                    val newParts = trMsg.contentParts.toMutableList()
                    val lastTrPartIdx = newParts.indexOfLast { it is AgentContentPart.ToolResult }
                    if (lastTrPartIdx >= 0) {
                        val part = newParts[lastTrPartIdx] as AgentContentPart.ToolResult
                        newParts[lastTrPartIdx] = part.copy(content = part.content + reminder)
                        agentHistory[trIdx] = trMsg.copy(contentParts = newParts)
                    }
                    // Retry a fresh model round with the nudged history.
                    continue
                }
                val window = effectiveContextWindowTokens()
                val usedCtx = stream.lastUsage?.latestContextTokens ?: 0
                val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                    usedCtx.toDouble() / window.toDouble() > 0.70
                val hint = when {
                    // Reminder already fired and the retry was ALSO empty — this
                    // is a genuine stall, not a transient blank. Point the user
                    // at retry/switch explicitly.
                    didInjectEmptyToolReminder ->
                        context.getString(R.string.error_empty_response_after_tool)
                    contextNearFull ->
                        context.getString(R.string.error_empty_response_context_large)
                    else ->
                        context.getString(R.string.error_empty_response_generic)
                }
                withContext(Dispatchers.Main) { setInlineError(hint) }
                loopOutcome = AgentTurnOutcome.Failed(hint)
            }
            if (!finishedCleanly) {
                loopOutcome = AgentTurnOutcome.NeedsAttention("model_finish_reason:${stream.turnFinishReason}")
            }
            // Auto-title after first exchange
            if (turn == 0) titleGenerator.generateIfNeeded()
            loopExitedNormally = true
            break
        }
        AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${stream.toolCalls.size} tool call(s), continuing")

        // [T-android-session-last-message-live-tool-call] Push a live
        // preview to the session list NOW, before the (possibly long-
        // running) tools execute. The authoritative assistant row isn't
        // written until turn end (persistAssistantTurn below), so without
        // this the home list shows a stale preview — or "No messages yet"
        // for a turn that opened with a tool call and no prior text —
        // for the entire tool duration. extractTextPreview prefers the
        // assistant's partial text and falls back to the tool summary, so
        // the list reflects exactly what the model just emitted. Mirrors
        // iOS overlaying the live VM's last message over the DB value.
        run {
            if (turnContent.parts.isNotEmpty()) {
                chatRepository.updateSessionPreview(
                    realSessionId.ifEmpty { sessionId },
                    turnContent.partsJson,
                )
            }
        }

        // Execute all tool calls
        val toolRound = ToolRound(
            host = toolRoundHost,
            turn = turn,
            turnToolNames = turnToolNames,
            turnTools = turnTools,
            assistantId = assistantId,
            allToolBlocks = allToolBlocks,
            toolInputChunkRings = toolInputChunkRings,
            toolLoopDetector = toolLoopDetector,
            sessionEventEmitter = sessionEventEmitter,
            // [C6-android-model-failure-discipline] From here on this round has
            // produced a side effect: a later failure is terminal.
            onExecuting = { roundToolExecuted = true },
        )
        toolRound.run(stream.toolCalls, accumulatedText)
        val resultParts = toolRound.resultParts

        // Update UI with tool statuses. Mark as awaiting the next model
        // response so "Minis is thinking" shows during the network gap
        // between tool results being sent and the next turn's first chunk.
        // Mirrors iOS isAwaitingModelResponse.
        withContext(Dispatchers.Main) {
            updateAssistantMessage(
                assistantId, accumulatedText, true, allToolBlocks,
                isAwaitingModelResponse = true,
            )
        }

        // Persist the assistant+tools turn (with full input JSON and thinking).
        // Capture the persisted DB id so we can back-fill agentHistory's last
        // assistant entry — compact-marker boundary resolution depends on it.
        val completedTurn = withContext(Dispatchers.IO) {
            AssistantTurnCodec.build(allToolBlocks, turnStartBlockIndex, toolInputMap, mediaStore.mediaBaseDir)
        }
        val assistantDbId = persistAssistantTurn(
            completedTurn,
            stream.lastUsage,
            turnReasoningContent,
            pendingTurn = pendingTurn,
            modelSnapshot = modelAttributionSnapshot(currentProvider),
        )
        if (assistantDbId != null) {
            // [C6-android-model-failure-discipline] A turn of this round is now
            // durable: later failures in the same round are terminal.
            roundTurnCommitted = true
            backfillAssistantDbId(assistantDbId)
        }

        // Persist tool results as user-role message (mirrors iOS)
        val toolResultDbId = persistToolResultMessage(resultParts)

        // Add tool results to history
        agentHistory.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = resultParts,
            dbMessageId = toolResultDbId,
        ))

        // Auto-title after first exchange (mirrors iOS generateSessionTitleIfNeeded)
        if (turn == 0) {
            titleGenerator.generateIfNeeded()
        }

        // [T-android-queued-message-interrupt-on-toolclose] iOS d14174d3
        // parity. User report: "怎么样了" queued bubble (dashed border,
        // red X) stayed pending behind a long sync→export→read→gh-issue
        // tool chain — drainQueuedPrompts() only fires when the WHOLE
        // tool loop converges, so the queued prompt waited for the
        // entire plan to finish even though the user wanted to
        // interrupt the moment a tool closed.
        //
        // Fix: at the post-tool-result boundary (we just appended the
        // tool_result to agentHistory above), if there's anything in
        // the queue, abandon the rest of the running plan and inject
        // the queued prompt as a fresh user turn — the next iteration
        // makes a brand-new API call whose response targets the
        // queued prompt directly.
        //
        // Why not just append-and-continue: the agentHistory tail is
        // user(tool_result). Anthropic's mergeConsecutiveSameRole would
        // fold a directly-appended user(queued_text) into that
        // tool_result, so the model would read the queued prompt as
        // in-loop context for the previous turn (#579 / iOS regression).
        // Inject a minimal assistant bridge first so the sequence is
        //   …user(tool_result) → assistant(bridge) → user(queued) →
        //   …assistant(responds-to-queued).
        // The bridge lives in agentHistory only (NOT persisted) —
        // it's purely a wire-format spacer for the API call.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(
                TAG_STREAM,
                "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
            )
            val handled = try {
                injectQueuedPromptsAsNewTurn(
                    finishedAssistantId = assistantId,
                    finishedAccumulatedText = accumulatedText,
                    finishedAllToolBlocks = allToolBlocks,
                )
            } catch (e: Exception) {
                Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", e)
                null
            }
            if (handled != null) {
                // Switch loop-scope state to the new bubble. Subsequent
                // iterations populate `handled.newAssistantId` and slice
                // `allToolBlocks` from the freshly-zeroed start index
                // (turnStartBlockIndex captures allToolBlocks.size at
                // iteration top, so clearing means new turn's blocks
                // span [0..size).
                assistantId = handled.newAssistantId
                accumulatedText = ""
                allToolBlocks.clear()
                allToolInputs.clear()
                toolInputChunkRings.clear()
                _canResume.value = false
                continue
            }
            // null return = empty-after-build / drain rejected; fall
            // through to normal next-turn dispatch so the queue doesn't
            // pin the loop indefinitely.
        }
    }
    // Two ways to leave the for-loop above:
    //   (a) `break` from the "no tool calls" happy-path → loopExitedNormally=true,
    //       updateAssistantMessage(...false...) already cleared streaming state.
    //   (b) `for (turn in 0 until MAX_AGENT_TURNS)` exhausted → flag stays false,
    //       which means the model kept asking for tool calls past the ceiling.
    //
    // (b) is the only case that needs the inline-error/Resume hand-holding;
    // (a) must NOT be touched or every normal completion gets a fake "hit
    // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
    if (!loopExitedNormally) {
        AppLogger.warning(
            TAG_STREAM,
            "runAgentLoop EXIT — hit MAX_AGENT_TURNS=$MAX_AGENT_TURNS, finalizing as resumable",
        )
        withContext(Dispatchers.Main) {
            finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks)
        }
        loopOutcome = AgentTurnOutcome.NeedsAttention("agent_turn_limit_reached")
    } else {
        AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
    }
    return loopOutcome
}

/**
 * Finalize the current assistant message when [runAgentLoop] hits the
 * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
 * "thinking" indicator clears, writes an inline error explaining *why*
 * we stopped, and arms canResume so the user can continue from here.
 * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
 */
internal fun ChatViewModel.finalizeAtTurnLimit(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
) {
    updateAssistantMessage(
        assistantId, text, false, blocks,
        isAwaitingModelResponse = false,
    )
    // [T-android-thinking-indicator-linger] updateAssistantMessage drains
    // _streamingById[assistantId] above, but the agent loop ran on
    // Dispatchers.IO while this finalize hops to Main — a late streaming
    // delta can re-add the side-channel entry AFTER the drain, and since
    // the loop has now exited no further isStreaming=false write will ever
    // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
    // on any message with a side-channel entry, so that orphan keeps the
    // "thinking" row alive forever. Defensively drop the entry here as the
    // last Main-thread write of this turn.
    // [T-android-stream-flush-review] Cancel the trailing flush too, so it
    // can't re-add this orphan entry after we drop it on the error path.
    clearStreamFlushState(assistantId)
    if (_streamingById.value.containsKey(assistantId)) {
        _streamingById.value = _streamingById.value - assistantId
    }
    setInlineError(
        "Stopped after $MAX_AGENT_TURNS agent turns to prevent runaway " +
        "tool use. The model kept calling tools without finishing — tap " +
        "Resume to continue from here, or send a new message to start over.",
    )
    _canResume.value = true
}

/**
 * Instance entry point used by the tool-dispatch path. The real logic lives
 * in the companion so tests can reach it without a ChatViewModel.
 */
internal fun ChatViewModel.preflightValidateToolCall(
    name: String,
    args: JSONObject,
    tools: List<AgentToolDefinition>,
): String? = preflightValidateToolCallImpl(name, args, tools)

internal suspend fun ChatViewModel.executeTool(
    name: String,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    // T330: tri-state permission gating moved into the offload IPC
    // handler (OffloadGate). The CLIs land there whether the LLM
    // emitted a named tool call or a raw shell command, so the gate
    // is consistent across both paths. The pre-check that lived here
    // (`permissionTools = {calendar, location, …}`) was effectively
    // dead since these tools have no native ChatViewModel executor
    // — they always fall through to shell_execute or the offload
    // bridge, which is now where checkPermission runs.
    val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

    // Execution-intent checkpoint (DSH session-checkpoint-policy): persist
    // BEFORE the tool body runs so a process death mid-tool leaves a
    // recoverable "outcome unknown" signal for the next turn.
    val sid = activeSessionId
    if (!sid.isNullOrBlank()) {
        ToolCheckpointStore.recordIntent(context, sid, toolId, name, argsJson)
    }

    // Unified per-tool timeout (DeepSeek Harness
    // dsh-tool-call-timeout-policy): tools declare timeoutMs on their
    // AgentToolDefinition; the executor enforces a cooperative deadline
    // and returns a structured TOOL_TIMEOUT result instead of hanging
    // the whole turn. Tools that manage their own budget (shell,
    // subagent, ask_user_question) declare null and skip the wrapper.
    val timeoutMs = agentTools.firstOrNull { it.matchesName(name) }?.timeoutMs
    val dispatched: ToolExecutionResult? = if (timeoutMs != null) {
        withTimeoutOrNull(timeoutMs) {
            dispatchTool(name, argsJson, toolId, toolBlocks, assistantId, currentText)
        }
    } else {
        dispatchTool(name, argsJson, toolId, toolBlocks, assistantId, currentText)
    }

    val result = dispatched ?: ToolExecutionResult(
        output = "Error: tool call timed out after $timeoutMs ms. " +
            "The call was aborted; retry with a narrower request or split the task.",
        success = false,
        timedOut = true,
        toolTitle = toolTitle,
    )
    return applyRepeatGuard(name, argsJson, result)
}

/** The tool dispatch table, wrapped by [executeTool]'s timeout policy. */
internal suspend fun ChatViewModel.dispatchTool(
    name: String,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
): ToolExecutionResult {
    // P3: tools registered in ToolRegistry (D12 names + old aliases) go
    // through the new runtime gate. Unregistered tools fall through to
    // the legacy dispatch table below.
    val canonical = com.openminis.app.tools.runtime.ToolRegistry.canonicalName(name) ?: name
    if (com.openminis.app.tools.runtime.ToolRegistry.contains(canonical)) {
        return com.openminis.app.tools.runtime.ToolExecutor.execute(
            name = name,
            argsJson = argsJson,
            sessionId = activeSessionId,
            context = context,
            caller = com.openminis.app.tools.runtime.ToolPermissionManager.CALLER_LOCAL,
            toolId = if (canonical == "delegate_bot") {
                "${activeBotRunId.orEmpty()}::$toolId"
            } else {
                toolId
            },
        )
    }
    return when (name) {
    "browser_use" -> executeBrowserUseTool(argsJson)
    "memory_write" -> {
        val tier = com.openminis.app.offload.OffloadPermissionManager.tierFor(activeSessionId)
        if (tier == com.openminis.app.scheduled.ScheduledTaskPermissionTier.READ_ONLY) {
            val denial = com.openminis.app.scheduled.ScheduledReadOnlyPolicy.fileWriteDenial(
                name, runCatching { JSONObject(argsJson) }.getOrNull(),
            ) ?: "file_write_denied_readonly_tier: memory_write"
            com.openminis.app.offload.OffloadPermissionManager.recordScheduledTierDenial(
                activeSessionId, name, name,
            )
            ToolExecutionResult("Error: $denial", false)
        } else {
            executeMemoryWriteTool(argsJson)
        }
    }
    "memory_get" -> executeMemoryGetTool(argsJson)
    else -> ToolExecutionResult("Unknown tool: $name", false)
    }
}

internal fun ChatViewModel.applyRepeatGuard(
    name: String,
    argsJson: String,
    result: ToolExecutionResult,
): ToolExecutionResult = synchronized(this) { applyRepeatGuardLocked(name, argsJson, result) }

// Read-only calls of one turn now finish concurrently; the two counters this reads and writes are the
// view model's, so the update is made under its lock.
private fun ChatViewModel.applyRepeatGuardLocked(
    name: String,
    argsJson: String,
    result: ToolExecutionResult,
): ToolExecutionResult {
    // Ignore tools that are allowed to repeat (reads, questions, waits).
    if (name == FileReadTool.NAME || name == "memory_get" ||
        name == AskUserQuestionTool.NAME || name == "get_goal"
    ) {
        lastToolKey = null
        lastToolCount = 0
        return result
    }
    val key = name + "|" + argsJson.trim()
    if (key == lastToolKey) {
        lastToolCount++
    } else {
        lastToolKey = key
        lastToolCount = 1
    }
    if (lastToolCount < 4) return result
    val hint = when (lastToolCount) {
        4 -> "[reminder] You have called $name with the exact same arguments ${lastToolCount} times in a row. " +
            "Stop and re-read the previous result before trying again — switch approach or finish the task."
        else -> "[reminder] Still repeating $name with identical arguments (${lastToolCount} times). " +
            "Do NOT keep retrying the same call. Change strategy or conclude the task."
    }
    return result.copy(output = hint + "\n\n" + result.output)
}

/**
 * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
 * main model can't natively see, to be carried on the outgoing image part and
 * substituted by the provider's T264 branch. Returns null when the main model
 * has native vision (pixels are attached, no placeholder needed) OR no Vision
 * Group is configured (provider falls back to its historical literal). When a
 * Vision Group IS configured, returns a hint naming [path] and steering the
 * model to call read_image — closing the loop with executeReadImageTool.
 */
internal fun ChatViewModel.visionPlaceholderFor(path: String?): String? {
    val nativeVision = currentModel?.let {
        it.inputModalities?.map { m -> m.lowercase() }?.contains("image") == true
    } == true
    if (nativeVision) return null
    if (!com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)) return null
    return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path)
}

internal suspend fun ChatViewModel.executeBrowserUseTool(argsJson: String): ToolExecutionResult {
    val input = BrowserActionInput.parse(argsJson)
        ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

    return try {
        val result = browserTabPool.execute(input)
        val toolTitle = try {
            JSONObject(argsJson).optString("tool_title", "browser_use")
        } catch (_: Exception) { "browser_use" }

        var output = result.text
        var persistentImagePath: String? = result.imageFilePath
        var inferenceBytes: ByteArray? = null

        // Persist browser screenshots to /var/minis/browser/<session>/ so the
        // agent can reference them via minis:// in subsequent tool calls
        // (mirrors iOS AIChatViewModel case "browser_use").
        val base64 = result.base64Image
        var linuxImagePath: String? = null
        if (base64 != null) {
            val raw = try {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } catch (_: Exception) { null }
            if (raw != null) {
                // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                // long edge across attachments / browser / read_image.
                inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                val persistPath = persistBrowserArtifact(filename, raw)
                if (persistPath != null) {
                    persistentImagePath = persistPath
                    linuxImagePath = "/var/minis/browser/$filename"
                    linuxPathToMinisURL(linuxImagePath)?.let {
                        output = "$output\nminis_url: $it"
                    }
                }
            }
        }

        // Upstream's read_image=false: the screenshot is still saved for the user
        // (thumbnail, artifact, minis:// path) but is not attached to the model,
        // which only asked for the page's metadata.
        if (!result.attachImage) inferenceBytes = null

        // Persist fetched files (fetch action) and append minis_url
        val fetchData = result.fetchedFileData
        val fetchName = result.fetchedFileName
        if (fetchData != null && fetchName != null) {
            persistBrowserArtifact(fetchName, fetchData)
            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                output = "$output\nminis_url: $it"
            }
        }

        ToolExecutionResult(
            output = output,
            success = result.success,
            imageData = inferenceBytes,
            imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
            toolTitle = toolTitle,
            pageURL = result.pageURL,
            imageFilePath = persistentImagePath,
            imageLinuxPath = linuxImagePath,
        )
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false)
    }
}

/**
 * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
 * That directory is bind-mounted to `/var/minis/browser/` so the agent can
 * read it back via file_read / file_write / minis:// URLs.
 * Returns the host absolute path on success, null otherwise.
 */
internal fun ChatViewModel.persistBrowserArtifact(filename: String, data: ByteArray): String? {
    val sid = activeSessionId.takeIf { it.isNotEmpty() } ?: return null
    return try {
        val session = com.openminis.app.runtime.ubuntu.UbuntuPaths.ensureSessionDirs(sid)
            ?: java.io.File(com.openminis.app.runtime.ubuntu.UbuntuPaths.hostSessions, sid)
        val dir = java.io.File(session, "browser").apply { mkdirs() }
        val file = java.io.File(dir, filename)
        file.writeBytes(data)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
        null
    }
}

/**
 * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
 * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
 */
internal fun ChatViewModel.linuxPathToMinisURL(path: String): String? {
    val prefix = "/var/minis/"
    if (!path.startsWith(prefix)) return null
    val rest = path.removePrefix(prefix)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val namespace = rest.substring(0, slash)
    val filename = rest.substring(slash + 1)
    val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "minis://$namespace/$encoded"
}

/**
 * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
 * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
 */
internal fun ChatViewModel.resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= maxEdge) { bmp.recycle(); return null }
    val scale = maxEdge.toFloat() / longest
    val w = (bmp.width * scale).toInt()
    val h = (bmp.height * scale).toInt()
    val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
    bmp.recycle()
    val out = java.io.ByteArrayOutputStream()
    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    resized.recycle()
    return out.toByteArray()
}

internal fun ChatViewModel.executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    if (!_memoryEnabled.value) {
        val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
        return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
    // Record for SessionMemorySheet
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

internal fun ChatViewModel.executeMemoryGetTool(argsJson: String): ToolExecutionResult {
    val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
    val result = MemoryTools.executeMemoryGet(argsJson, repo)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}
