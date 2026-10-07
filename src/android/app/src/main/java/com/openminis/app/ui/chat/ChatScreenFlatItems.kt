package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.key
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FlatItemsBuildEffect(
    sessionId: kotlin.String,
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    streamingByIdState: androidx.compose.runtime.State<kotlin.collections.Map<kotlin.String, com.openminis.app.ui.chat.StreamingDelta>>,
    flatItemsState: androidx.compose.runtime.MutableState<kotlin.collections.List<com.openminis.app.ui.chat.FlatChatItem>>,
    prewarmMarkdown: kotlin.Function1<kotlin.collections.List<kotlin.String>, kotlin.Unit>,
    lastColdPrewarmMsState: androidx.compose.runtime.MutableState<kotlin.Long>,
    stepsPresentationStateState: androidx.compose.runtime.State<com.openminis.app.data.StepsPresentation>,
    flattenThinkingEnabled: kotlin.Boolean,
) {
    val messages by messagesState
    val streamingById by streamingByIdState
    var flatItems by flatItemsState
    var lastColdPrewarmMs by lastColdPrewarmMsState
    val stepsPresentationState by stepsPresentationStateState
    LaunchedEffect(messages, sessionId, stepsPresentationState, flattenThinkingEnabled) {
        // [T-android-stream-pipeline-incremental] Frozen/live split.
        //
        // `messages` is CONSTANT within this effect (the effect is
        // keyed on it and the streaming turn writes high-frequency
        // fields into the streamingById side-channel, never the
        // canonical list). So the rows for every message BEFORE the
        // first streamed one (= the frozen prefix) can be computed
        // ONCE per effect lifetime and reused by reference on every
        // tick. Per tick we only rebuild the live suffix (usually a
        // single message). Pre-split, every 80ms tick re-flattened
        // ALL messages (1146 rows on the ANR-loop session), re-ran
        // splitMarkdownIntoBlockTexts over every frozen message,
        // and allocated the whole row set fresh — the 130–180MB/s
        // GC storm and the 100s builds in minis-2026-06-10.log.
        //
        // Row-for-row equivalence with the old full build holds by
        // construction: buildFlatChatItems' neighbor lookbacks
        // (precededByUser / isResumeContinuation) only ever read
        // EARLIER messages, the live suffix is built against the
        // full merged list with fromIndex (lookbacks cross the
        // boundary), and dedupe continuity is preserved via
        // seedKeys. Frozen rows are the same instances every tick,
        // so LazyColumn's key+equals skip path sees ZERO change.
        //
        // Throttle (unchanged): conflate() + sample(80) keeps UI
        // publication at ~12fps regardless of token rate.
        var frozenRows: List<FlatChatItem> = emptyList()
        var frozenKeys: Set<String> = emptySet()
        var frozenSplitIdx = -1
        var streamWasActive = false
        // [T-android-stream-pipeline-incremental] Flush the perf
        // turn when this effect is CANCELLED mid-turn: the
        // turn-end drain emits `_messages` FIRST (restarting this
        // messages-keyed effect) and clears the side-channel
        // after, so the cancelled collector never sees the
        // empty-stream tick that would fire turnEnd — without the
        // finally, same-session turns accumulate forever and no
        // [StreamPerf] summary is ever emitted.
        try {
        kotlinx.coroutines.flow.combine(
            kotlinx.coroutines.flow.flowOf(messages),
            viewModel.streamingById,
        ) { msgs, stream -> msgs to stream }
            .conflate()
            .sample(80L)
            .collect { (msgs, stream) ->
                val tickStartNs = System.nanoTime()
                if (stream.isNotEmpty() && !streamWasActive) {
                    streamWasActive = true
                    com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                }
                // First message carrying a live overlay; everything
                // before it is frozen. Empty stream → whole list is
                // frozen (covers cold open and post-drain ticks).
                val splitIdx = if (stream.isEmpty()) {
                    msgs.size
                } else {
                    val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                    if (i < 0) msgs.size else i
                }
                val frozenReused = splitIdx == frozenSplitIdx
                if (!frozenReused) {
                    val tBuildStart = System.nanoTime()
                    val wasEmptyPre = flatItems.isEmpty()
                    // [T-android-perf-logging] Mark the start of a
                    // full (first / non-streaming) build so the
                    // gap to buildFlatChatItems.firstBuild bounds
                    // the construction cost in isolation.
                    if (wasEmptyPre && stream.isEmpty()) {
                        com.openminis.app.diagnostics.PerfLongCtx.step(
                            sessionId,
                            "buildFlatChatItems.start",
                            "msgCount=${msgs.size}",
                        )
                    }
                    val rows = withContext(Dispatchers.Default) {
                        // [T-android-flatitems-sublist-cme] Pass a
                        // SNAPSHOT COPY, not msgs.subList(...). A
                        // subList is a live VIEW backed by msgs and
                        // shares its modCount; building off-main
                        // (Dispatchers.Default) while msgs is
                        // concurrently replaced — and the nested
                        // messages.subList(idx+1, …).all{} inside
                        // buildFlatChatItems iterating that view —
                        // threw ConcurrentModificationException from
                        // a later frame's SubList.equals. Copying
                        // severs the view so it can't comodify.
                        buildFlatChatItems(
                            msgs.take(splitIdx),
                            sessionId,
                            stepsPresentation = stepsPresentationState,
                            currentThinkingEnabled = flattenThinkingEnabled,
                        )
                    }
                    val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                    frozenRows = rows
                    frozenKeys = rows.mapTo(HashSet()) { it.key }
                    frozenSplitIdx = splitIdx
                    // [T-android-coldload-offmain-parse] Parallel
                    // viewport prewarm: block-parse + inline-warm
                    // the newest (viewport-candidate) markdown
                    // fragments off-main so the first frame's rows
                    // compose as cache HITs. Deliberately launched
                    // in PARALLEL with the flatItems publish, not
                    // before it — blocking the publish would add
                    // the parse latency to time-to-first-frame,
                    // the exact thing this task removes; rows the
                    // prewarm hasn't reached yet just take the
                    // placeholder-then-swap path in
                    // MarkdownBlockBody. Cold/full builds only
                    // (stream empty) — live ticks never get here.
                    if (stream.isEmpty() && rows.isNotEmpty()) {
                        val prewarmRowLimit = 16
                        val prewarmCharBudget = 96_000
                        val raws = mutableListOf<String>()
                        var charSum = 0
                        for (item in rows.asReversed()) {
                            if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                            val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                            raws.add(raw)
                            charSum += raw.length
                        }
                        if (raws.isNotEmpty()) {
                            launch(Dispatchers.Default) {
                                val tPrewarmNs = System.nanoTime()
                                prewarmMarkdown(raws)
                                val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                lastColdPrewarmMs = prewarmMs
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "coldPrewarm.done",
                                    "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                )
                            }
                        }
                    }
                    // Only emit on the first non-streaming build per
                    // session (cheap reentry-path marker) or whenever
                    // build takes >50 ms (i.e. real work).
                    if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                        com.openminis.app.diagnostics.PerfLongCtx.step(
                            sessionId,
                            if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                            else "buildFlatChatItems.slow",
                            "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                        )
                        // [T-android-perf-logging] Low-memory risk
                        // flag: a very high row count is the single
                        // biggest contributor to cold-open GC
                        // pressure.
                        if (rows.size > 3000) {
                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                sessionId,
                                "buildFlatChatItems.highRowCount",
                                "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                            )
                        }
                    }
                }
                // Live suffix: only the streamed message(s). Built
                // against the merged FULL list so neighbor lookbacks
                // across the frozen/live boundary stay correct.
                // sessionId = null keeps the hot path log-free.
                val liveRows = if (splitIdx >= msgs.size) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        val merged = mergeStreamingOverlay(msgs, stream)
                        buildFlatChatItems(
                            merged,
                            null,
                            fromIndex = splitIdx,
                            seedKeys = frozenKeys,
                            stepsPresentation = stepsPresentationState,
                            currentThinkingEnabled = flattenThinkingEnabled,
                        )
                    }
                }
                flatItems = if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows
                com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                    flattenNanos = System.nanoTime() - tickStartNs,
                    frozenReused = frozenReused,
                    frozenRows = frozenRows.size,
                    liveRows = liveRows.size,
                )
                if (stream.isEmpty() && streamWasActive) {
                    streamWasActive = false
                    com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                }
            }
        } finally {
            // Effect cancelled (turn-end drain emit / session
            // switch / screen dispose) — flush the open turn.
            if (streamWasActive) {
                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
            }
        }
    }
}

@Composable
internal fun TrailingPinEffect(
    viewModel: com.openminis.app.ui.chat.ChatViewModel,
    messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastUserAppendMsState: androidx.compose.runtime.MutableState<kotlin.Long>,
    tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit,
    userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>,
    lastInterruptMsState: androidx.compose.runtime.MutableState<kotlin.Long>,
    hasFloatingTools: kotlin.Boolean,
    bottomReserve: androidx.compose.ui.unit.Dp,
    flatItemsState: androidx.compose.runtime.MutableState<kotlin.collections.List<com.openminis.app.ui.chat.FlatChatItem>>,
    lastTrailingPinKeyState: androidx.compose.runtime.MutableState<kotlin.String?>,
) {
    val messages by messagesState
    var lastUserAppendMs by lastUserAppendMsState
    var userScrolledAway by userScrolledAwayState
    var lastInterruptMs by lastInterruptMsState
    var flatItems by flatItemsState
    var lastTrailingPinKey by lastTrailingPinKeyState
    LaunchedEffect(flatItems) {
        // Pin once per new trailing tool/typing row, key-deduped,
        // not on every flatten publish, so streaming tool-arg
        // ticks don't fight the user. flatItems is oldest-first
        // (rendered via asReversed()), so the newest row is at
        // the end.
        val newest = flatItems.lastOrNull() ?: return@LaunchedEffect
        // [T-android-queued-bubble-behind-toolbar] UserBubble is in
        // the accept-list too, for the queued ("candidate") message
        // the user sends WHILE a turn is streaming.
        //
        // enqueuePrompt() appends the bubble to `_messages` without
        // going through the normal send path, so the only scrolls it
        // gets are the two position-0 pins (SEND-PATH/* and
        // LE(messages.size)USER-SEND-SNAP). Those fire — the logs
        // show all three landing `idx=0 off=0` — but position 0
        // under reverseLayout is the LazyColumn's own bottom edge,
        // which the floating tool bar (65dp thumbnail + overhang)
        // covers. contentPadding.bottom already reserves that space
        // via bottomReserve, yet the reserve does NOT change here:
        // a tool bar was already on screen before the enqueue, so
        // hasFloatingTools stays true and LE(bottomReserve) never
        // re-fires (verified: zero `reserve-change` events at the
        // enqueue moment). The bubble is laid out inside the
        // reserved band and stays half-occluded.
        //
        // This effect re-pins AFTER flatItems republishes with the
        // new row measured, which is exactly the missing step: the
        // earlier pins ran against a flatItems that did not yet
        // contain the bubble. Restricting it to isQueued keeps
        // ordinary user sends (already handled by the send path,
        // and never appended mid-stream) off this path.
        val isQueuedBubble =
            newest is FlatChatItem.UserBubble && newest.message.isQueued
        if (newest !is FlatChatItem.AssistantToolUse &&
            newest !is FlatChatItem.AssistantTyping &&
            // [T-android-work-process] In grouped mode the trailing
            // row of a live run is the work-process row; it needs
            // the same follow-pin the tool pill gets, otherwise the
            // running step is left behind the floating bar.
            newest !is FlatChatItem.WorkProcessRow &&
            !isQueuedBubble
        ) return@LaunchedEffect
        if (newest.key == lastTrailingPinKey) return@LaunchedEffect
        if (userScrolledAway) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
        if (sinceInterrupt < 1000L) return@LaunchedEffect
        // Pin fires when EITHER (a) we're inside the send-grace
        // window (the original "freshly sent, snap the typing
        // row up" case), OR (b) the agent loop is actively
        // streaming AND the user hasn't manually scrolled away.
        // History readers fail (b) on userScrolledAway.
        val sinceSendForPin = System.currentTimeMillis() - lastUserAppendMs
        val sendGrace = lastUserAppendMs > 0L && sinceSendForPin <= SEND_FOLLOW_GRACE_MS
        val streamingActive = viewModel.isStreaming.value && !userScrolledAway
        if (!sendGrace && !streamingActive) return@LaunchedEffect
        lastTrailingPinKey = newest.key
        tracedScrollToItem("trailing-row/${newest.contentType}", 0, 0)
    }
}
