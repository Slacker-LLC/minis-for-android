package com.openminis.app.ui.chat

import android.util.Log
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Build
import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.agent.RunCheckpointStore
import com.openminis.app.agent.RunContextSnapshot
import com.openminis.app.agent.RunRecoveryCoordinator
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.provider.LLMProvider
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.tools.ToolSensitivePolicy
import com.openminis.app.ui.chat.ChatViewModel.Companion.NEWLINE_FLUSH_MAX_LEN
import com.openminis.app.ui.chat.ChatViewModel.Companion.NEWLINE_FLUSH_MIN_CHARS
import com.openminis.app.ui.chat.ChatViewModel.Companion.PROMPT_FRAGMENT_TIMEOUT_MS
import com.openminis.app.ui.chat.ChatViewModel.Companion.TAG
import com.openminis.app.ui.chat.ChatViewModel.StreamFlushState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.openminis.app.data.repository.instance
import com.openminis.app.data.repository.resolvedAgentLoopEntries
import com.openminis.app.data.repository.skillPromptFragment

internal fun ChatViewModel.updateAssistantMessage(
    id: String,
    content: String,
    isStreaming: Boolean,
    toolBlocks: List<AssistantBlock>,
    isAwaitingModelResponse: Boolean = false,
) {
    // T-streaming-side-channel: during a live turn, write high-frequency
    // fields into [_streamingById] instead of mutating the canonical
    // message list. This keeps the `messages` StateFlow reference stable
    // across the turn so ChatScreen's top-level reads
    // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
    // a full recompose of the 8980-line composable on every token.
    //
    // On stream end (isStreaming=false), drain the accumulated delta
    // back into the canonical message in a single `_messages` emit, then
    // clear the side-channel entry so post-turn reads (history rebuild,
    // persist, agent loop) see the canonical truth.
    if (isStreaming) {
        val toolBlocksImmutable = toolBlocks.toList()

        // [T-android-stream-flush-dualpath] Dual-path flush at the
        // message-accumulation layer (NOT per-fragment, which never
        // throttled). Decide whether to publish this delta now:
        //   • structural change (toolBlocks count / awaiting flag) →
        //     publish immediately — these drive tool-bubble UI and must
        //     never be coalesced away or the bubble state stalls.
        //   • else time-path: enough ms since last publish for this length.
        //   • else newline fast-path: a line break in the newly-streamed
        //     chunk + ≥50 new chars, gated to short docs (iOS parity).
        // When none fire, stash the latest as a trailing publish so the
        // final chunk before a pause still lands; a fresh delta cancels
        // and replaces it.
        val st = streamFlushStates.getOrPut(id) {
            StreamFlushState().also { it.lastFlushedLen = 0 }
        }
        val prev = _streamingById.value[id]
        // [T-android-stream-flush-review] Structural change also covers an
        // in-place tool-block STATUS flip (running → success), not just a
        // count change — otherwise a spinner→checkmark could lag up to one
        // throttle tier. Compare a cheap (kind,status) fingerprint.
        val toolStatusChanged = prev != null &&
            prev.toolBlocks.size == toolBlocksImmutable.size &&
            toolBlocksImmutable.indices.any { i ->
                prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
            }
        val structuralChange = prev == null ||
            prev.toolBlocks.size != toolBlocksImmutable.size ||
            prev.isAwaitingModelResponse != isAwaitingModelResponse ||
            toolStatusChanged
        val now = System.currentTimeMillis()
        val elapsed = now - st.lastFlushMs
        val throttle = streamFlushThrottleMs(content.length)
        val newChunk = if (content.length > st.lastFlushedLen) {
            content.substring(st.lastFlushedLen.coerceAtMost(content.length))
        } else ""
        val unflushed = content.length - st.lastFlushedLen
        val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
            newChunk.contains('\n') &&
            unflushed >= NEWLINE_FLUSH_MIN_CHARS

        fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
            // The event journal is the unthrottled transport source. Log
            // the exact structural projection before exposing the Compose
            // state so a snapshot fence never observes UI content whose
            // corresponding sequence has not been allocated.
            sessionEventEmitter.streamingUpdate(id, text, blocks, awaiting)
            _streamingById.value = _streamingById.value + (
                id to StreamingDelta(
                    content = text,
                    toolBlocks = blocks,
                    isAwaitingModelResponse = awaiting,
                )
            )
            st.lastFlushMs = System.currentTimeMillis()
            st.lastFlushedLen = text.length
        }

        if (structuralChange || elapsed >= throttle || newlineFlush) {
            st.trailingJob?.cancel()
            st.trailingJob = null
            st.pendingContent = null
            publish(content, toolBlocksImmutable, isAwaitingModelResponse)
        } else {
            // Throttled: always record this delta as the freshest pending
            // value, so whenever the trailing job fires it publishes the
            // latest text — not whatever was captured when it was first
            // scheduled (review #2). Schedule the job only once.
            st.pendingContent = content
            st.pendingBlocks = toolBlocksImmutable
            st.pendingAwaiting = isAwaitingModelResponse
            if (st.trailingJob == null) {
                val wait = (throttle - elapsed).coerceAtLeast(16L)
                st.trailingJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(wait)
                    val pc = st.pendingContent
                    if (pc != null) {
                        publish(pc, st.pendingBlocks, st.pendingAwaiting)
                        st.pendingContent = null
                    }
                    st.trailingJob = null
                }
            }
        }
        // [T-android-timeout-while-running] If a transient banner
        // (`message.error`) is still on the canonical assistant message
        // when a fresh streaming event arrives, the banner is stale —
        // the model is producing again, by construction the prior
        // transient timeout / retry / fallback has been resolved.
        // Clear it in the same mutation. setTransientInlineError /
        // setInlineError are the only paths that write `error`; the
        // terminal path (setInlineError) sets isStreaming=false on the
        // same message in the same emit, so it cannot reach this
        // branch and the clear is safe.
        //
        // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
        // glued to the bottom of the conversation while the agent
        // continued running (LM Studio tool loop on 30/30, "Minis is
        // thinking" indicator). Caused by (a) the fallback-switch branch in
        // runAgentLoop not calling clearInlineError(), and (b) the
        // streaming-side-channel writing every subsequent delta into
        // _streamingById without ever touching _messages where
        // `error` lives. (a) is fixed at the fallback site; (b) is
        // fixed here defensively so any future write-path that forgets
        // to clear can't strand a stale banner across the rest of
        // the turn.
        val canonical = _messages.value
        val canonicalIdx = canonical.indexOfLast { it.id == id }
        if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
            val updated = canonical.toMutableList()
            updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
            _messages.value = updated
        }
        return
    }
    // [T-android-stream-flush-dualpath] Stream end → cancel any pending
    // trailing flush and drop the throttle accumulator for this message;
    // the canonical drain below publishes the final, complete text.
    clearStreamFlushState(id)
    // Stream end → sync delta into canonical message + clear side-channel.
    val current = _messages.value
    val idx = current.indexOfLast { it.id == id }
    if (idx < 0) {
        // The message itself is gone (e.g. clearChat raced ahead) —
        // just clear any leftover stream delta and bail.
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
        sessionEventEmitter.clearMessage(id)
        return
    }
    val updated = current.toMutableList()
    updated[idx] = current[idx].copy(
        content = content,
        isStreaming = false,
        toolBlocks = toolBlocks.toList(),
        isAwaitingModelResponse = isAwaitingModelResponse,
        // The turn's clock stops here: the status line shows send-to-now until this is set.
        updatedAtMs = if (isAwaitingModelResponse) current[idx].updatedAtMs else System.currentTimeMillis(),
    )
    _messages.value = updated
    if (_streamingById.value.containsKey(id)) {
        _streamingById.value = _streamingById.value - id
    }
    // `assistant/message` is emitted only once the native canonical row
    // is settled, making it an idempotent final projection for replay.
    sessionEventEmitter.messageSettled(
        messageId = id,
        content = content,
        blocks = toolBlocks,
        isAwaitingModelResponse = isAwaitingModelResponse,
    )
}

/**
 * Read a message's content + toolBlocks honoring any active streaming
 * delta. Use this from non-render code that needs the "current" view of
 * a message during a live turn (e.g. agent history builders, persistence
 * snapshots) without forcing the render layer to consult the delta map.
 */
internal fun ChatViewModel.effectiveContent(id: String): String? {
    val delta = _streamingById.value[id]
    if (delta != null) return delta.content
    return _messages.value.firstOrNull { it.id == id }?.content
}

/**
 * Force-drain any outstanding streaming delta for [id] back into the
 * canonical message and clear the side-channel slot. Called from turn
 * exit paths (cancel / error / retry / resume / clearChat) so the
 * canonical message reflects all accumulated content even if the last
 * [updateAssistantMessage] call had isStreaming=true.
 */
internal fun ChatViewModel.flushStreamingDelta(id: String) {
    val delta = _streamingById.value[id] ?: return
    val current = _messages.value
    val idx = current.indexOfLast { it.id == id }
    if (idx >= 0) {
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = delta.content,
            isStreaming = false,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
            updatedAtMs = System.currentTimeMillis(),
        )
        _messages.value = updated
    }
    // [T-android-stream-flush-review] Cancel the pending trailing flush
    // BEFORE clearing the side channel — otherwise its viewModelScope
    // coroutine (not cancelled by streamJob.cancel) fires later and
    // re-adds the orphan side-channel entry, reviving a stale "thinking"
    // row after the turn was stopped/drained.
    clearStreamFlushState(id)
    _streamingById.value = _streamingById.value - id
}

/** Drain ALL outstanding streaming deltas (called on global resets). */
internal fun ChatViewModel.flushAllStreamingDeltas() {
    clearAllStreamFlushStates()
    val pending = _streamingById.value
    if (pending.isEmpty()) return
    val current = _messages.value.toMutableList()
    var changed = false
    for ((id, delta) in pending) {
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) continue
        current[idx] = current[idx].copy(
            content = delta.content,
            isStreaming = false,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
            updatedAtMs = System.currentTimeMillis(),
        )
        changed = true
    }
    if (changed) _messages.value = current
    _streamingById.value = emptyMap()
}

/**
 * Resolve the immutable identity for the provider that actually emitted
 * this turn. The active entry id is important: model ids are not unique
 * across provider instances, especially after slot fallback.
 */
internal fun ChatViewModel.modelAttributionSnapshot(provider: LLMProvider?): ModelAttributionSnapshot? {
    val actualProvider = provider ?: return null
    val entry = _activeEntryId.value?.let { entryId ->
        providerRepository.config.value.modelEntries.firstOrNull { it.id == entryId }
    } ?: return null
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    return ModelAttributionSnapshot(
        modelId = actualProvider.model.id,
        displayName = actualProvider.model.displayName,
        providerTypeRaw = instance.providerType.name,
        providerInstanceId = instance.id,
    )
}

/** Gives the newest assistant entry in [agentHistory] that has no stored-row id the id [dbId]. */
internal fun ChatViewModel.backfillAssistantDbId(dbId: String?) {
    if (dbId == null) return
    val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
    if (lastIdx >= 0) agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = dbId)
}

internal suspend fun ChatViewModel.persistAssistantTurn(
    turn: AssistantTurnCodec.Turn,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    modelSnapshot: ModelAttributionSnapshot? = null,
    pendingTurn: PendingAssistantTurn? = null,
): String? {
    if (turn.parts.isEmpty()) return null
    // [T-android-sensitive-tool-redaction] The live turn keeps the real
    // arguments; what is written to Room is the redacted payload.
    val partsJson = ToolSensitivePolicy.redactTranscriptJson(turn.parts, turn.partsJson)
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
    }
    val write: suspend () -> String? = {
        chatRepository.appendMessage(
            pendingTurn?.sessionId ?: realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent ?: pendingTurn?.reasoningContent,
            modelSnapshot = modelSnapshot,
        ).id
    }
    val messageId = if (pendingTurn != null) pendingTurn.commit(turn, write) else write()
    // [T-android-run-checkpoint] The transcript mirrors what was persisted
    // (already redacted). A turn without tool calls is the run's final
    // answer, so it also closes the recovery record.
    persistRunCheckpoint(partsJson)
    if (turn.parts.none { it is AgentContentPart.ToolUse }) finishRunCheckpoint()
    return messageId
}

@Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
internal suspend fun ChatViewModel.persistAssistantMessage(
    text: String,
    usage: LLMUsage?,
    toolBlocks: List<AssistantBlock>? = null,
    toolCallInputs: Map<String, String> = emptyMap()
    ,
    reasoningContent: String? = null,
) {
    if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

    val partsJson = buildString {
        append("[")
        var first = true
        if (text.isNotEmpty()) {
            append("""{"type":"text","value":${escapeJson(text)}}""")
            first = false
        }
        toolBlocks?.forEach { block ->
            // Only persist real tool-use blocks. text / thinking / info blocks are
            // either represented via the `text` parameter (accumulatedText) or
            // reconstructed from thinking metadata; persisting them as `toolUse`
            // produces empty-name records that Anthropic rejects with
            // "messages.N.content.M.tool_use.name: String should have at least 1 character".
            if (block.kind != "tool_use") return@forEach
            if (block.toolName.isBlank()) return@forEach  // extra safety
            if (!first) append(",")
            first = false
            val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
            val pUrl = block.browserURL ?: ""
            val iPath = block.imageFilePath ?: ""
            append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
        }
        append("]")
    }
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
    }
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        modelSnapshot = modelAttributionSnapshot(currentProvider),
    )
}

/** Persist tool results as a user-role message (mirrors iOS behavior). */
internal suspend fun ChatViewModel.persistToolResultMessage(parts: List<AgentContentPart>): String? {
    val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
    if (results.isEmpty()) return null
    // [T-android-sensitive-tool-redaction] A sensitive result keeps its id,
    // name and error flag, so the tool_use/tool_result pairing still holds,
    // and loses its text and image bytes before they reach the database.
    val persistedResults = results.map(ToolSensitivePolicy::redactResultPart)
    val partsJson = buildString {
        append("[")
        persistedResults.forEachIndexed { index, result ->
            if (index > 0) append(",")
            val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
            append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
        }
        append("]")
    }
    val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
    val checkpointSession = realSessionId.ifEmpty { sessionId }
    results.forEach { result ->
        ToolCheckpointStore.markDone(context, checkpointSession, result.id, !result.isError)
    }
    appendRunCheckpointToolResults(persistedResults)
    return entity.id
}

/**
 * [T-android-run-checkpoint] Writes the recovery transcript and the context
 * snapshot of the in-flight run. Ported from Eta
 * `agent/runtime/AgentRunCheckpointStore.kt` (Mangi-11/Eta @ c15de97): the
 * transcript and the model context are stored independently, because neither
 * can be derived from the other, and both are already redacted.
 *
 * A checkpoint failure never takes the turn down: the run then has no
 * recovery, which is the state of a run that was never checkpointed.
 */
internal fun ChatViewModel.persistRunCheckpoint(partsJson: String) {
    val runId = activeBotRunId ?: return
    if (runCheckpointRecorder == null) return
    runCatching {
        runCheckpointTranscript.add("""{"role":"assistant","parts":$partsJson}""")
        RunCheckpointStore.saveTranscript(context, activeSessionId, runId, runCheckpointTranscript.toList())
        RunCheckpointStore.saveContext(
            context = context,
            sessionId = activeSessionId,
            runId = runId,
            snapshot = RunContextSnapshot.encode(agentHistory).toString(),
        )
    }.onFailure { Log.w(TAG, "run checkpoint write failed: " + it.message) }
}

internal fun ChatViewModel.appendRunCheckpointToolResults(results: List<AgentContentPart.ToolResult>) {
    val runId = activeBotRunId ?: return
    if (runCheckpointRecorder == null || results.isEmpty()) return
    runCatching {
        results.forEach { result ->
            runCheckpointTranscript.add(
                """{"role":"tool","name":${escapeJson(result.name)},"toolUseId":${escapeJson(result.id)},"isError":${result.isError},"content":${escapeJson(result.content)}}""",
            )
        }
        RunCheckpointStore.saveTranscript(context, activeSessionId, runId, runCheckpointTranscript.toList())
    }.onFailure { Log.w(TAG, "run checkpoint tool result write failed: " + it.message) }
}

/**
 * [T-android-run-checkpoint] Closes the record once the run reached its
 * terminal state; the first call wins, so a re-entered turn cannot write
 * back into an acknowledged run.
 */
internal fun ChatViewModel.finishRunCheckpoint() {
    val runId = activeBotRunId ?: return
    val recorder = runCheckpointRecorder ?: return
    runCatching {
        recorder.seal()
        RunCheckpointStore.markTerminal(context, activeSessionId, runId)
    }.onFailure { Log.w(TAG, "run checkpoint terminal write failed: " + it.message) }
    runCheckpointRecorder = null
}

/**
 * [T-android-run-checkpoint] Reconciles the run records this session left
 * behind with what this process knows. Ported from Eta
 * `ui/app/AgentRunRecoveryCoordinator.kt` (Mangi-11/Eta @ c15de97).
 */
suspend fun ChatViewModel.reconcileRunCheckpoints() {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isBlank()) return
    val plan = withContext(Dispatchers.IO) {
        RunRecoveryCoordinator.plan(
            checkpoints = RunCheckpointStore.list(context).filter { it.sessionId == sid },
            locallyObservedRunId = activeBotRunId,
            activeRunId = activeBotRunId,
            activeStateKnown = true,
            terminalStateKnown = true,
        )
    }
    plan.completed.forEach { action ->
        RunCheckpointStore.remove(context, sid, action.checkpoint.runId)
    }
    plan.interrupted.forEach { action ->
        Log.w(TAG, "[RunCheckpoint] run " + action.checkpoint.runId + " is interrupted: " + action.reason)
        // The visible repair stays with InterruptedTailDetector; once the
        // state is known the record has served its purpose.
        RunCheckpointStore.remove(context, sid, action.checkpoint.runId)
    }
    plan.undecided.forEach { action ->
        Log.i(TAG, "[RunCheckpoint] run " + action.checkpoint.runId + " kept undecided: " + action.reason)
    }
}

internal suspend fun ChatViewModel.buildSystemPrompt(): String? {
    // Cache-friendly layout: keep `base` byte-stable by stripping out anything
    // that varies per request, then append a "Runtime context" suffix at the
    // very end with all the dynamic bits (date, timezone, locale, configured
    // minis-model-use count). OpenAI / DeepSeek prompt caching is prefix-
    // based, so the longer the static head, the better the hit rate.
    // Pre-T122 the prompt embedded `Current time: yyyy-MM-dd HH:mm` mid-base,
    // which guaranteed cache misses across minute boundaries — even a quick
    // follow-up could land on a different minute and pay full ingestion.
    val today = java.time.LocalDate.now()
    val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    val tzId = java.util.TimeZone.getDefault().id
    val lang = context.resources.configuration.locales[0].toLanguageTag()

    // Count of agent-loop-visible models for the `minis-model-use` CLI
    // (exposed as a shell command via the native_offload handler).
    val modelUseCount = try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }

    // [T-soul-md] Layer 1 is the "You are <name>, a capable AI assistant
    // running on an Android device ..." identity sentence plus the SOUL.md
    // Personality block; SystemPromptBuilder renders it and Settings -> Soul
    // owns the wording. AgentSystemPrompt.base() prepends it to the editable
    // modules - see com.openminis.app.prompt for the whole head-of-prompt
    // composition and its byte-stability contract.
    val memoryOn = _memoryEnabled.value
    // [T-system-prompt-modules] The static head of the system prompt — the
    // identity sentence plus every editable section (tool list, /var/minis
    // paths, minis:// scheme, style rules, Android tooling, environment and
    // scheduled-task notes, memory on/off notice) — is no longer spelled out
    // here. It is owned by com.openminis.app.prompt: shipped defaults live in
    // `assets/prompts/<id>.md`, user overrides in the app's files dir, and the
    // editable surface is Settings -> System prompt (plus `minis-config` for
    // the agent). Only the per-turn runtime fragments below are built here.
    val base = com.openminis.app.prompt.AgentSystemPrompt.base(context, memoryOn)

    // Match iOS order exactly: skills → global memory → recent daily memory.
    // See ios/Agent/Chat/AIChatViewModel.swift:4375-4387. Each fragment is
    // appended only when non-null; absent fragments leave no separator.
    // Do not rescan the guest skill tree on the critical send path. The
    // repository already refreshes on startup, Settings entry, and after
    // skill file writes. A guest-runtime outage must not prevent the provider
    // request from being launched just because a prompt fragment is stale.
    val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
    // [T-mcp-integration-android] Re-read servers.json (the CLI / file
    // browser may have changed it out-of-band) then build the Top-20
    // enabled-MCP disclosure, injected right after the skills fragment.
    val mcpFragment = withContext(Dispatchers.IO) {
        mcpRepository?.reloadFromDisk()
        mcpRepository?.mcpPromptFragment(activeSessionId)
    }
    // [T-memory-toggle-gates-injection-and-tools-android] Skip loading
    // GLOBAL.md + recent daily logs entirely when the user has turned
    // memory off for this session. Cheaper (no disk read) and — more
    // importantly — keeps the model from seeing stale persistent state
    // it can't tell the user how to manage. Skills and SOUL.md are
    // intentionally NOT gated by this toggle: skills are part of the
    // tool surface and SOUL.md is part of identity, both orthogonal
    // to the memory feature.
    val globalMemoryFragment = if (memoryOn) {
        bestEffortPromptFragment("GLOBAL.md") {
            // [C5-android-memory-injection-budget] Budget the injected file
            // against the model's real window (Eta
            // AgentMemoryContextBuilder.coreBudgetChars) instead of always
            // using the default one; loadGlobalMemoryFragmentAsync falls back
            // to the default window when the live window is unknown.
            memoryRepository?.loadGlobalMemoryFragmentAsync(effectiveContextWindowTokens())
        }
    } else null
    val dailyMemoryFragment = if (memoryOn) {
        bestEffortPromptFragment("recent memory") {
            memoryRepository?.loadRecentDailyMemoryFragmentAsync()
        }
    } else null

    return buildString {
        append(base)
        // Agent preset prompt configuration is Android-authoritative and
        // applied by the runtime itself — same preset, same prompt, on
        // both App and Web (the Web never edits the prompt).
        val presetPrompt = com.openminis.app.remote.AgentPresetRegistry
            .presetForSession(context, sessionId).promptSection
        if (presetPrompt != null) {
            append("\n\n")
            append(presetPrompt)
        }
        if (skillFragment != null) {
            append("\n\n")
            append(skillFragment)
        }
        if (mcpFragment != null) {
            append("\n\n")
            append(mcpFragment)
        }
        subAgentPromptFragment()?.let {
            append("\n\n")
            append(it.trimEnd())
        }
        if (globalMemoryFragment != null) {
            append("\n\n")
            append(globalMemoryFragment)
        }
        if (dailyMemoryFragment != null) {
            append("\n\n")
            append(dailyMemoryFragment)
        }
        // Runtime context goes last so the prefix above stays byte-stable
        // across requests within the same day. Keep ordering deterministic
        // (date → tz → lang → model count) — any reorder defeats the cache.
        append("\n\nRuntime context:\n")
        append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
        append("- Device language: ").append(lang).append("\n")
        append("- minis-model-use models available: ").append(modelUseCount)
    }
}

fun ChatViewModel.executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
    val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
    if (!_memoryEnabled.value) {
        return MemoryTools.ToolResult(
            "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
            false,
        )
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
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
    return result
}

/**
 * Prompt enrichment is optional. Keep the model turn independent from the
 * guest runtime and report a bounded miss instead of leaving the UI in a
 * permanent streaming state while the runtime recovers.
 */
internal suspend fun <T> ChatViewModel.bestEffortPromptFragment(
    label: String,
    block: suspend () -> T?,
): T? {
    return try {
        withTimeoutOrNull(PROMPT_FRAGMENT_TIMEOUT_MS) { block() }.also { value ->
            if (value == null) {
                Log.w(TAG, "Prompt fragment skipped after ${PROMPT_FRAGMENT_TIMEOUT_MS}ms: $label")
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Log.w(TAG, "Prompt fragment unavailable: $label (${error.message})")
        null
    }
}

fun ChatViewModel.executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
    val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
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
    return result
}

internal suspend fun ChatViewModel.buildPastedParts(text: String, sessionId: String): PastedParts? =
    PastedTextProcessor.processPastedParts(text, _pastedTexts.value, sessionId, mediaStore)

/**
 * Build the parts_json array for a user message: a `text` part (omitted
 * when the user only sent attachments with no caption) followed by one
 * `mediaRef` part per persisted image. Mirrors the existing single-part
 * shape when there are no attachments.
 */
internal fun ChatViewModel.buildUserPartsJson(
    text: String,
    mediaRefPartsJson: List<String>,
    // [T-android-retry-attachment-loss] The <user-attached-files> XML
    // inventory (non-image file paths/sizes the model uses to `cat` the
    // file). iOS persists this same XML as a trailing text part so it
    // round-trips through retry / rerun / session-reload unchanged — the
    // model keeps seeing the /var/minis/attachments/uploads/... paths.
    // Android previously only added it to the in-memory agentHistory and
    // never persisted it, so a retry silently dropped the file inventory.
    // Persist it here as a text part (iOS parity); toLLMMessage restores
    // it via the plain "text" case with zero special-casing.
    attachedFilesXml: String? = null,
    bodyPartsJson: List<String>? = null,
): String {
    val parts = mutableListOf<String>()
    if (bodyPartsJson != null) {
        parts.addAll(bodyPartsJson)
    } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
        parts.add("""{"type":"text","value":${escapeJson(text)}}""")
    }
    parts.addAll(mediaRefPartsJson)
    attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}

/**
 * [T-android-overlay-reply-status-34599] Pull the most recent
 * assistant text out of `_messages` and hand it to
 * [SessionActivityTracker.publishLastReply]. The tracker truncates
 * to a fixed-width excerpt and pairs it with [sessionId] so the
 * floating overlay can render a "tap to open this chat" capsule
 * after the stream completes. No-op when no assistant message has
 * content yet (e.g. fail during the very first turn).
 */
internal fun ChatViewModel.publishOverlayReplyExcerpt(sessionId: String) {
    val snapshot = _messages.value
    val text = snapshot.asReversed().firstOrNull { msg ->
        msg.role == "assistant" && msg.content.isNotBlank()
    }?.content
    SessionActivityTracker.publishLastReply(sessionId, text)
}
