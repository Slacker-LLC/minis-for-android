package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.MediaRef
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

/** What a [TurnStream] needs from the conversation it streams into. */
internal interface TurnStreamHost {
    /** Runs [block] on the main thread; the message list is only mutated there. */
    suspend fun <T> onMain(block: () -> T): T

    /** Re-renders the assistant message (still streaming) from [blocks]. Call it inside [onMain]. */
    fun updateMessage(assistantId: String, content: String, blocks: List<AssistantBlock>)

    /** Feeds the run-recovery log with a fragment of streamed text. */
    fun recordCheckpointText(blockIndex: Int, text: String)

    fun hostedToolLabel(kind: String): String
    fun hostedToolFailed(): String

    /** Persists generated media for this session. */
    fun saveMedia(data: ByteArray, mimeType: String): MediaRef
    val mediaBaseDir: File
}

/**
 * The live state of ONE streamed model response and how each stream chunk updates it: the text and
 * thinking being built, the tool calls the model announced, the throttle that decides when the UI is
 * refreshed, and the tool-call-id dedupe. Moved out of [ChatViewModel.runAgentLoop] unchanged; the
 * loop reads the result fields after the stream ends and calls [resetForRetry] when an attempt fails.
 */
internal class TurnStream(
    private val host: TurnStreamHost,
    private val turn: Int,
    private val assistantId: String,
    private val allToolBlocks: MutableList<AssistantBlock>,
    private val turnStartBlockIndex: Int,
    private val pendingTurn: PendingAssistantTurn,
    private val sessionEventEmitter: ChatSessionEventEmitter,
    private val toolInputChunkRings: MutableMap<String, MutableList<String>>,
    /** The provider streaming right now; it changes when the run falls back to another one. */
    private val provider: () -> LLMProvider,
    /** The last known context size, before this response reports its own. */
    private var contextTokens: Int,
    /** Told the context size each usage report implies, so the loop can size the next request and the pre-send guard can see the pressure. */
    private val onContextTokens: (Int) -> Unit,
) {
    // T307: per-delta StringBuilder for the running turn text + the
    // currently-open trailing text block. `turnText` snapshots are
    // taken (via .toString()) at flush boundaries only, never per
    // delta. `currentTextBlockSb` mirrors the trailing text block's
    // growing content; reset to a fresh builder whenever a new text
    // block opens (which happens after a tool_use / thinking break
    // interrupts the text run).
    val turnTextSb = StringBuilder()
    var currentTextBlockSb: StringBuilder? = null

    // [T-android-tool-splits-reply-fix] Index (into allToolBlocks) of
    // THIS turn's single text block, used only when the provider's
    // streamed content is monolithic (streamTextIsMonolithic — OpenAI
    // Chat Completions). -1 until the turn's first text delta. The
    // merge scope is ONE streamed response: text arriving after a
    // tool RESULT round-trip belongs to the NEXT agent-loop turn,
    // which is a separate assistant message — so genuine
    // multi-segment turns are unaffected by the merge.
    var turnTextBlockIdx = -1

    // One-shot observability: future endpoints that adopt qwen-style
    // post-tool_calls content chunking show up in the log.
    private var loggedPostToolTextMerge = false
    val turnThinking = StringBuilder()

    // Opaque reasoning_content blob captured from the provider's
    // ReasoningContent stream chunk. When set (including empty string),
    // takes precedence over turnThinking concatenation so the exact
    // server-emitted value round-trips on the next request — DeepSeek V4
    // emits "" legitimately and fabricated text would be in-context-learned.
    var turnReasoningBlob: String? = null

    // T321: capture finish_reason from LLMStreamChunk.Finished so we can
    // log it at turn-end alongside the empty-turn warning.
    var turnFinishReason: String? = null
    var lastUsage: LLMUsage? = null

    // [T-eta-responses-opaque-items] Opaque Responses output items (the
    // reasoning items carrying encrypted_content) captured from THIS
    // turn's stream, replayed verbatim by the next request of the run and
    // never persisted. Ported from Eta `ResponsesEphemeralState`.
    val turnProviderItems = mutableListOf<String>()
    val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args

    // [T-android-gemini3-thoughtsig / #179] toolCallId -> Gemini 3.x
    // thoughtSignature for this turn's calls (null for other providers).
    val toolCallSignatures = mutableMapOf<String, String>()

    // T94 fix 2 / T256 / T307: tiered text-delta throttle state and the
    // pending text that has not been pushed to the UI yet. See
    // [textDeltaThrottleMs]. The per-tool-kind input gates: file_write /
    // file_edit pills churn JSON the user can't read anyway — 1Hz is
    // plenty; other tools get 5Hz so command/url previews stay legible.
    private var lastUiUpdateMs = 0L
    private var lastFlushedLen = 0
    private val pendingChunkSb = StringBuilder()
    private var lastFileToolInputMs = 0L
    private var lastOtherToolInputMs = 0L

    private fun textDeltaThrottleMs(len: Int): Long = when {
        len < 500     -> 150L
        len < 2_000   -> 300L
        len < 32_000  -> 500L
        len < 64_000  -> 1_000L
        len < 128_000 -> 1_500L
        else          -> 2_000L
    }

    // [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
    // Some upstream OpenAI-compatible gateways occasionally emit
    // multiple parallel tool_calls with the SAME id but different
    // name/args. Sending both back unchanged trips the receiver's
    // uniqueness check (HTTP 400 "duplicate tool_call_id"). Mirror
    // the iOS fix: the FIRST occurrence keeps the raw id, second
    // becomes "<id>-2", third "<id>-3", etc.
    //
    // Three pieces of state because Android routes ToolInputDelta
    // by chunk.id (iOS routes by name) and OpenAI emits ALL completes
    // together after finish_reason — so we can't drop the
    // "currently in-flight" map by the time completes arrive.
    //
    //   dedupeStartCounts    raw id → # ToolUseStart events seen
    //   dedupeCompleteCounts raw id → # ToolCallComplete events seen
    //   inFlightRenamedId    raw id → renamed id of the tool currently
    //                        streaming deltas (overwritten on each start)
    //
    // Start/complete ordering match: OpenAI streams emit tools in
    // `index` order at finish_reason, mirroring start order.
    private val dedupeStartCounts = mutableMapOf<String, Int>()
    private val dedupeCompleteCounts = mutableMapOf<String, Int>()
    private val inFlightRenamedId = mutableMapOf<String, String>()

    fun dedupeToolStartId(raw: String): String {
        val n = (dedupeStartCounts[raw] ?: 0) + 1
        dedupeStartCounts[raw] = n
        val renamed = if (n == 1) raw else "$raw-$n"
        if (n > 1) {
            AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
        }
        inFlightRenamedId[raw] = renamed
        return renamed
    }

    fun dedupeToolInputId(raw: String): String =
        inFlightRenamedId[raw] ?: raw

    fun dedupeToolCompleteId(raw: String): String {
        val n = (dedupeCompleteCounts[raw] ?: 0) + 1
        dedupeCompleteCounts[raw] = n
        return if (n == 1) raw else "$raw-$n"
    }

    // Materialise the active text block's StringBuilder into its
    // immutable content. Monolithic mode targets the tracked turn
    // text block — which may NOT be the last block once trailing
    // content arrived after tool_calls; ordered mode keeps the
    // original trailing-block behaviour.
    fun materializeActiveTextBlock() {
        val sb = currentTextBlockSb ?: return
        val idx = if (provider().streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
        if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
            allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
        }
    }

    /**
     * Forget everything a failed attempt produced except the blocks (the caller rolls those back):
     * accumulators, the failed attempt's thinking, and the throttle baselines, so the next attempt's
     * first delta goes through immediately instead of coalescing against stale numbers.
     */
    fun resetForRetry() {
        // T307: SB-based per-turn accumulators reset.
        turnTextSb.setLength(0)
        currentTextBlockSb = null
        // [T-android-tool-splits-reply-fix] The tracked turn
        // text block was just rolled back with the rest of
        // this turn's partial blocks.
        turnTextBlockIdx = -1
        turnThinking.clear()
        turnReasoningBlob = null
        pendingTurn.reasoningContent = null
        // The failed attempt's opaque items must not survive into the retry.
        turnProviderItems.clear()
        toolCalls.clear()
        toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
        // T94 fix 2 + T256: throttle bookkeeping is per-stream
        // attempt; reset alongside the partial-block rollback so
        // the next attempt's first delta fires through immediately
        // rather than coalescing against stale baselines.
        pendingChunkSb.setLength(0)
        lastUiUpdateMs = 0L
        lastFlushedLen = 0
        lastFileToolInputMs = 0L
        lastOtherToolInputMs = 0L
    }

    /**
     * T94 fix 2: flush any text that landed in the throttle window after the last UI tick, then reset the
     * throttle bookkeeping for the next response. The retry-rollback / turn-finalize paths assume the
     * message reflects all accumulated text deltas.
     */
    suspend fun finishStream(accumulatedText: String) {
        if (pendingChunkSb.isNotEmpty()) {
            pendingChunkSb.setLength(0)
            // T307: also flush the active text block's pending
            // tail and snapshot turnText.
            materializeActiveTextBlock()
            val turnSnap = turnTextSb.toString()
            host.onMain { host.updateMessage(assistantId, accumulatedText + turnSnap, allToolBlocks) }
        }
        // T256: reset throttle bookkeeping for the next turn so the
        // first delta of the next assistant message fires immediately
        // rather than coalescing against this turn's stale baseline.
        lastFlushedLen = 0
        lastUiUpdateMs = 0L
        lastFileToolInputMs = 0L
        lastOtherToolInputMs = 0L
    }

    /** Applies one stream chunk. [accumulatedText] is the text of the turns before this response. */
    suspend fun handle(chunk: LLMStreamChunk, accumulatedText: String) {
        when (chunk) {
            is LLMStreamChunk.ThinkingDelta -> {
                // Record the provider's raw delta before the
                // throttled Compose projection mutates. The remote
                // snapshot fence can therefore include every token.
                sessionEventEmitter.rawReasoningChunk(
                    assistantId,
                    "thinking_$turn",
                    chunk.text,
                )
                turnThinking.append(chunk.text)
                pendingTurn.reasoningContent = turnReasoningBlob ?: turnThinking.toString()
                // Update thinking block in UI
                val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                if (thinkIdx < 0) {
                    allToolBlocks.add(AssistantBlock(
                        id = "thinking_$turn",
                        kind = "thinking",
                        content = turnThinking.toString(),
                        toolTitle = "Thinking",
                    ))
                } else {
                    allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                }
                host.onMain { host.updateMessage(assistantId, accumulatedText + turnTextSb.toString(), allToolBlocks) }
            }
            is LLMStreamChunk.Text -> {
                // Same ordering as reasoning: the append-only event
                // is authoritative for the raw token stream, while
                // `_streamingById` stays intentionally throttled.
                sessionEventEmitter.rawTextChunk(assistantId, chunk.text)
                // Mark thinking block as done when text starts flowing
                val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                    allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                }
                // T307: append-only on the StringBuilder; .toString()
                // is taken once below at flush time, not per delta.
                turnTextSb.append(chunk.text)
                // [T-android-run-checkpoint] The recovery log receives the
                // same fragments; the recorder merges them.
                host.recordCheckpointText(turnTextBlockIdx.coerceAtLeast(0), chunk.text)
                // Append to the trailing text block — or open a new one if the last
                // block isn't a text block (i.e. a tool call or thinking was in between).
                // This preserves the chronological interleaving of text and tool calls
                // across a single assistant turn. The block's `content` field stays
                // immutable String — we keep a parallel StringBuilder for the active
                // block and materialise via .toString() only on flush.
                val lastIdx = allToolBlocks.lastIndex
                val monolithic = provider().streamTextIsMonolithic
                val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                    // [T-android-tool-splits-reply-fix] Chat Completions
                    // content is ONE string per response — a content
                    // delta arriving after tool_calls deltas (qwen
                    // chunking artifact) is still part of the same
                    // pre-tool sentence. Merge it back instead of
                    // fabricating a post-tool text block, which split
                    // sentences mid-word in the chat UI. Scope: this
                    // streamed response only (see turnTextBlockIdx).
                    if (!loggedPostToolTextMerge &&
                        allToolBlocks.subList(turnTextBlockIdx + 1, allToolBlocks.size).any { it.kind == "tool_use" }
                    ) {
                        loggedPostToolTextMerge = true
                        AppLogger.info(
                            TAG_STREAM,
                            "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${provider().model.id})",
                        )
                    }
                    currentTextBlockSb!!.append(chunk.text)
                    currentTextBlockSb!!
                } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                    currentTextBlockSb!!.append(chunk.text)
                    currentTextBlockSb!!
                } else {
                    // New text run — either first text after a tool_use/thinking
                    // break, or first text in this turn. Open a fresh block AND
                    // a fresh accumulator. The new block's content carries the
                    // first delta verbatim; subsequent deltas append to the SB.
                    val freshSb = StringBuilder(chunk.text)
                    currentTextBlockSb = freshSb
                    val block = AssistantBlock(
                        id = "text_${turn}_${allToolBlocks.size}",
                        kind = "text",
                        content = chunk.text,
                    )
                    if (monolithic) {
                        // Single text block per response. If tool blocks
                        // already arrived (content-after-tool_calls
                        // chunking with no preface text), insert BEFORE
                        // the first tool block of this turn so the
                        // persisted order matches the canonical
                        // {content, tool_calls} message shape.
                        val firstToolIdx = (turnStartBlockIndex until allToolBlocks.size)
                            .firstOrNull { allToolBlocks[it].kind == "tool_use" }
                        if (firstToolIdx != null) {
                            allToolBlocks.add(firstToolIdx, block)
                            turnTextBlockIdx = firstToolIdx
                        } else {
                            allToolBlocks.add(block)
                            turnTextBlockIdx = allToolBlocks.lastIndex
                        }
                    } else {
                        allToolBlocks.add(block)
                    }
                    freshSb
                }
                // T94 fix 2 + T256: tiered text-delta throttle. Mutate local
                // state every delta (above) so block boundaries stay correct
                // for ToolUseStart / ToolInputDelta which read allToolBlocks
                // directly. Only push to _messages when the length-aware gate
                // opens (or a newline lands during a short reply). Pending
                // text lives in `pendingChunkSb` so the stream-end final
                // flush at line ~3580 can drain it.
                pendingChunkSb.append(chunk.text)
                val len = turnTextSb.length
                val unflushed = len - lastFlushedLen
                val throttle = textDeltaThrottleMs(len)
                val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                    lastUiUpdateMs = nowMs
                    lastFlushedLen = len
                    pendingChunkSb.setLength(0)
                    // Materialise SB → String for both the active block's
                    // content (so Compose sees an immutable snapshot) and
                    // for the assistant message body. These are O(n) calls
                    // but happen at throttled cadence, not per delta.
                    // (activeSb === currentTextBlockSb by construction.)
                    materializeActiveTextBlock()
                    val turnSnap = turnTextSb.toString()
                    host.onMain { host.updateMessage(assistantId, accumulatedText + turnSnap, allToolBlocks) }
                }
            }
            is LLMStreamChunk.HostedToolActivity -> {
                // [T-eta-hosted-web-search] A tool the provider ran itself: one info row
                // for the whole call, labelled here because the provider only names the
                // kind. It never becomes a tool_use block, so nothing tries to execute it.
                val label = host.hostedToolLabel(chunk.kind)
                val text = when {
                    !chunk.finished -> label + "…"
                    chunk.success -> label
                    else -> label + " · " + host.hostedToolFailed()
                }
                val row = AssistantBlock(
                    id = "hosted_" + chunk.id,
                    kind = "info",
                    content = text,
                    toolName = "hosted_tool",
                )
                val rowIndex = allToolBlocks.indexOfFirst { it.id == row.id }
                if (rowIndex >= 0) {
                    allToolBlocks[rowIndex] = row
                } else {
                    allToolBlocks.add(row)
                }
                host.onMain { host.updateMessage(assistantId, accumulatedText + turnTextSb.toString(), allToolBlocks) }
            }
            is LLMStreamChunk.ToolUseStart -> {
                // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                // renamed value drives the AssistantBlock.id used by
                // ToolCallComplete / ToolInputDelta lookups and ends
                // up as the persisted tool_call_id on the next request.
                val toolUseId = dedupeToolStartId(chunk.id)
                val toolStartedAtMs = System.currentTimeMillis()
                // Emit the semantic tool boundary before it reaches
                // the mutable UI block list.
                sessionEventEmitter.toolCall(
                    assistantId,
                    toolUseId,
                    chunk.name,
                    toolStartedAtMs,
                )
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                // Mark thinking block as done when tool use starts
                val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                    allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                }
                // T154: when the last few text deltas landed inside the 50ms throttle
                // window, the UI hadn't yet been pushed with the trailing text — and
                // adding the tool_use block before that push freezes the preceding
                // text fragment in StreamingMarkdownText (its `messageIsStreaming`
                // flag flips off the next layout pass) with chars chopped off the
                // end. Mirror iOS AnthropicAgentProvider.swift Step 1 / Step 2:
                // first push the latest accumulated text *unthrottled* so the text
                // block freezes at its complete value, yield to let Compose render
                // it, then add the tool_use block in a separate transaction. The
                // pendingChunkText/lastUiUpdateMs reset mirrors the throttle path
                // so the next text delta doesn't try to flush stale state.
                if (turnTextSb.isNotEmpty() && pendingChunkSb.isNotEmpty()) {
                    pendingChunkSb.setLength(0)
                    lastUiUpdateMs = System.currentTimeMillis()
                    lastFlushedLen = turnTextSb.length
                    // T307: pre-tool-use flush also materialises the
                    // active text block + a turn-text snapshot.
                    materializeActiveTextBlock()
                    // [T-android-tool-splits-reply-fix] Ordered mode:
                    // the tool block breaks the text run, so the next
                    // text delta opens a new block. Monolithic mode
                    // keeps the accumulator alive — same-response
                    // content deltas arriving after tool_calls merge
                    // back into the pre-tool text block instead.
                    if (!provider().streamTextIsMonolithic) {
                        currentTextBlockSb = null
                    }
                    val turnSnap = turnTextSb.toString()
                    host.onMain { host.updateMessage(assistantId, accumulatedText + turnSnap, allToolBlocks) }
                    yield()
                }
                // T256 tier 2: force the next ToolInputDelta to flush
                // immediately by zeroing both gate timestamps. iOS does the
                // same in .startToolUse (AIChatViewModel.swift:6075-6116) so
                // the user sees the pill name/title arrive without waiting
                // out the 1s/200ms gate.
                lastFileToolInputMs = 0L
                lastOtherToolInputMs = 0L
                // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                if (allToolBlocks.none { it.id == toolUseId }) {
                    allToolBlocks.add(AssistantBlock(
                        id = toolUseId,
                        kind = "tool_use",
                        toolName = chunk.name,
                        toolStatus = ToolBlockStatus.STREAMING,
                        toolTitle = friendlyToolTitle(chunk.name),
                        startTimeMs = toolStartedAtMs,
                    ))
                    host.onMain { host.updateMessage(assistantId, accumulatedText + turnTextSb.toString(), allToolBlocks) }
                }
            }
            is LLMStreamChunk.ToolInputDelta -> {
                // [T-dedupe-toolcallid] Translate to the currently-in-flight
                // renamed id so the per-tool ring + block lookup match
                // the block that ToolUseStart created.
                val toolInputId = dedupeToolInputId(chunk.id)
                sessionEventEmitter.rawToolInput(
                    assistantId,
                    toolInputId,
                    chunk.accumulated,
                )
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
                // Maintain a per-tool ring of the most recent `accumulated`
                // snapshots so the preflight validator below can dump them
                // when an empty/invalid call is detected. Cheap (single
                // append + bounded trim) and lives outside any throttle so
                // every delta lands here.
                val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                ring.add(chunk.accumulated)
                if (ring.size > TOOL_INPUT_CHUNK_RING_MAX) {
                    // Drop from the front so we keep the most recent N.
                    ring.subList(0, ring.size - TOOL_INPUT_CHUNK_RING_MAX).clear()
                }
                val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                if (idx >= 0) {
                    val prev = allToolBlocks[idx]
                    // Stream-parse partial JSON (mirrors iOS extractPartialStringValue):
                    //   - pull "tool_title" out early so the pill header updates live
                    //   - keep the raw accumulated JSON in toolArgs so detail-sheet
                    //     renderers (extractShellCommand, args.optString("command"), …)
                    //     can pick up fields as they appear.
                    //   - leave content empty during streaming (real output arrives
                    //     after ToolCallComplete).
                    val partialTitle = extractPartialStringValue("tool_title", chunk.accumulated)
                    val liveTitle = when {
                        !partialTitle.isNullOrEmpty() -> partialTitle
                        prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                        else -> friendlyToolTitle(prev.toolName)
                    }
                    allToolBlocks[idx] = prev.copy(
                        toolArgs = chunk.accumulated,
                        toolTitle = liveTitle,
                        content = "",
                    )
                    // T256 tier 2: gate UI push by tool kind. file_write/file_edit
                    // pump multi-KB JSON through the SSE — pushing every delta
                    // pegs the UI thread for no readable benefit (the user can't
                    // skim a partial JSON blob anyway). Mirrors iOS
                    // AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                    // Local state above is mutated unconditionally so when the
                    // gate eventually opens — or ToolCallComplete force-flushes —
                    // the latest accumulated args are pushed.
                    val toolName = prev.toolName
                    val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                    val gateMs = if (isHeavyFileTool) 1_000L else 200L
                    val nowMs = System.currentTimeMillis()
                    val lastTs = if (isHeavyFileTool) lastFileToolInputMs else lastOtherToolInputMs
                    if (nowMs - lastTs >= gateMs) {
                        if (isHeavyFileTool) lastFileToolInputMs = nowMs
                        else lastOtherToolInputMs = nowMs
                        host.onMain { host.updateMessage(assistantId, accumulatedText + turnTextSb.toString(), allToolBlocks) }
                    }
                }
            }
            is LLMStreamChunk.ToolCallComplete -> {
                // [T-dedupe-toolcallid] Rewrite duplicate id so the
                // persisted tool_calls list, the block lookup, and
                // the downstream tool-result join all key on the
                // same value (matches the rename applied at start).
                val toolCompleteId = dedupeToolCompleteId(chunk.id)
                // Providers that do not emit a final ToolInputDelta
                // still get a complete, replayable argument stream.
                sessionEventEmitter.rawToolInput(
                    assistantId,
                    toolCompleteId,
                    chunk.args.toString(),
                )
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${chunk.args.toString().take(300)}")
                toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                // [T-android-gemini3-thoughtsig / #179] Stash the Gemini
                // 3.x thought signature keyed by the (deduped) tool call id.
                chunk.thoughtSignature?.let { toolCallSignatures[toolCompleteId] = it }
                val idx = allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                if (idx >= 0) {
                    val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                    val title = providedTitle ?: friendlyToolTitle(chunk.name)
                    // PENDING — JSON params fully received, waiting for execution
                    // dispatcher to invoke the tool. executeTool() flips to RUNNING.
                    allToolBlocks[idx] = allToolBlocks[idx].copy(
                        toolStatus = ToolBlockStatus.PENDING,
                        toolTitle = title,
                        toolArgs = chunk.args.toString(),
                        content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                        // [T-android-gemini3-thoughtsig / #179] Persist the
                        // signature onto the block so buildTurnParts (the DB
                        // path) round-trips it. Preserve any prior value if
                        // this chunk lacked one.
                        thoughtSignature = chunk.thoughtSignature ?: allToolBlocks[idx].thoughtSignature,
                    )
                    host.onMain { host.updateMessage(assistantId, accumulatedText + turnTextSb.toString(), allToolBlocks) }
                }
            }
            is LLMStreamChunk.Usage -> {
                lastUsage = chunk.usage
                // Publish the same usage sample into the session journal
                // (DSH tokenUsage projection reads assistant/chunk
                // type=usage with data.turn/step). Real data only: no
                // fabricated latency/steps.
                runCatching {
                    sessionEventEmitter.rawUsageChunk(
                        assistantId,
                        org.json.JSONObject().apply {
                            put("inputTokens", chunk.usage.inputTokens)
                            put("outputTokens", chunk.usage.outputTokens)
                            put("cacheReadTokens", chunk.usage.cacheReadInputTokens ?: 0)
                            put("cacheWriteTokens", chunk.usage.cacheCreationInputTokens ?: 0)
                        },
                        turn,
                    )
                }
                // Update context token count for next turn's dynamicMaxTokens()
                // and publish to _lastTurnContextTokens so the ContextPolicy
                // gate in [checkContextBeforeSend] can see the latest pressure
                // without a DB round-trip.
                if (chunk.usage.latestContextTokens > 0) {
                    contextTokens = chunk.usage.latestContextTokens
                } else if (chunk.usage.inputTokens > 0) {
                    // Fallback when a provider omits latestContextTokens: inputTokens is
                    // now fresh-only (cached portion subtracted in the parser), so add the
                    // cache back to recover the true context size — otherwise a high
                    // cache-hit turn would under-report context pressure and skip offload.
                    contextTokens = chunk.usage.inputTokens +
                        (chunk.usage.cacheReadInputTokens ?: 0) +
                        (chunk.usage.cacheCreationInputTokens ?: 0)
                }
                if (contextTokens > 0) {
                    onContextTokens(contextTokens)
                }
            }
            is LLMStreamChunk.ReasoningContent -> {
                // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — record
                // on the last assistant turn so it echoes back on the next request.
                // Empty strings are preserved (DeepSeek V4 emits "" on non-thinking
                // turns and we must round-trip exactly that). No live UI surface;
                // the thinking panel is driven by ThinkingDelta events above.
                turnReasoningBlob = chunk.content
                pendingTurn.reasoningContent = chunk.content
            }
            is LLMStreamChunk.ProviderOutputItem -> {
                // [T-eta-responses-opaque-items] Keep the item verbatim for the
                // next request of this run; the transcript rebuilds text and
                // function_call items on its own, but cannot rebuild this one.
                turnProviderItems += chunk.json
            }
            is LLMStreamChunk.Started -> { /* no-op */ }
            is LLMStreamChunk.Finished -> {
                // T321: stash for empty-turn diagnostic logging below.
                turnFinishReason = chunk.stopReason
            }
            is LLMStreamChunk.MediaAttachment -> {
                materializeActiveTextBlock()
                val ref = try {
                    host.saveMedia(chunk.attachment.data, chunk.attachment.mimeType)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw java.io.IOException("Failed to save generated media", e)
                }
                val block = AssistantTurnCodec.mediaBlock(ref, host.mediaBaseDir)
                var published = false
                try {
                    host.onMain {
                        allToolBlocks.add(block)
                        host.updateMessage(assistantId, accumulatedText + turnTextSb, allToolBlocks)
                        published = true
                    }
                } finally {
                    if (!published) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            java.io.File(host.mediaBaseDir, ref.relativePath).delete()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG_STREAM = "ChatVMStream"

        /** How many recent `accumulated` snapshots of a streaming tool call are kept for the preflight dump. */
        const val TOOL_INPUT_CHUNK_RING_MAX = 10
    }
}

/**
 * Extract a string value for `key` from *partial* (possibly truncated) JSON
 * without needing a complete, parseable object. Mirrors iOS
 * `extractPartialStringValue(_:from:)` in AIChatViewModel.swift.
 *
 * Returns content up to the first unescaped `"`, or the remaining buffer
 * if the closing quote has not streamed yet.
 */
internal fun extractPartialStringValue(key: String, json: String): String? {
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        return unescapePartialJsonString(findUnescapedEnd(after))
    }
    return null
}

/** Return substring up to the first unescaped `"`, or the whole string if none. */
private fun findUnescapedEnd(s: String): String {
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        if (c == '\\') {
            // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
            i += 2
            continue
        }
        if (c == '"') return s.substring(0, i)
        i++
    }
    return s
}

/** Unescape common JSON string escapes. */
private fun unescapePartialJsonString(s: String): String =
    s.replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\\\", "\\")

internal fun friendlyToolTitle(toolName: String): String = when (toolName) {
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "subagent" -> "Delegate Subtask"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}
