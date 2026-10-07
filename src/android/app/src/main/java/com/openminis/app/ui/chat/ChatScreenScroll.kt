package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.key
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample

@Composable
internal fun rememberIsNearBottom(listState: androidx.compose.foundation.lazy.LazyListState, tagScroll: kotlin.String, nearBottomThresholdPx: kotlin.Float): androidx.compose.runtime.State<kotlin.Boolean> {
    return remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            val viewportEnd = info.viewportEndOffset
            val itemBottom = bottomItem?.let { it.offset + it.size } ?: Int.MIN_VALUE
            val gap = viewportEnd - itemBottom
            // T173: when the bottom row is taller than the viewport (e.g. one
            // big assistant message with code blocks), `gap` is permanently
            // hugely negative even when the user is anchored at the bottom —
            // because reverseLayout pins index 0's *bottom* to the viewport
            // bottom, but the item's geometric bottom is below the viewport
            // (it extends downward off-screen in layoutInfo terms). The
            // earlier `gap < threshold` test happened to be true in that
            // case, but it ALSO stayed true as the user scrolled up by
            // thousands of px — settle-after-interaction then snapped them
            // right back. Use the LazyListState anchor instead: under
            // reverseLayout, "at bottom" ⇔ index 0 is the first item AND
            // its scroll offset is within `threshold` px. Any drag upward
            // grows firstVisibleItemScrollOffset past threshold instantly,
            // so the user's intent flips into userScrolledAway.
            val firstIdx = listState.firstVisibleItemIndex
            val firstOff = listState.firstVisibleItemScrollOffset
            // [T-android-scroll-isnearbottom-bug] Anchor authority lives on
            // listState.firstVisibleItem(Index|ScrollOffset). Previous form
            // required `bottomItem != null` too, but during a fresh measure
            // pass visibleItemsInfo can transiently be empty even when the
            // user IS at the bottom (firstIdx=0 / firstOff=0). The empty
            // window read as "not at bottom" and propagated to:
            //   - reserve-change SKIP at the bottom (recovery yank lost)
            //   - scroll-to-bottom FAB shown on a session that's actually
            //     bottom-anchored
            //   - trailing-row pin gated on isNearBottom failing
            // See /tmp/fix_scroll_diagnosis.md. Anchor on firstIdx/firstOff
            // alone — they survive the measure window.
            val result = firstIdx == 0 && firstOff <= nearBottomThresholdPx.toInt()
            // T-android-jank-profile: was logging on every scroll frame (this
            // is a derivedStateOf body — it re-runs when any of
            // listState.layoutInfo / firstVisibleItemIndex /
            // firstVisibleItemScrollOffset / canScrollForward / etc. change,
            // i.e. ~60 times/second during a scroll fling). String-building
            // + file write per frame measurably contributed to scroll jank.
            // Gate behind a debug toggle so the log path stays available for
            // future scroll-debugging sessions but doesn't ship by default.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "isNearBottom: bottomVisible=${bottomItem != null} itemBottom=$itemBottom viewportEnd=$viewportEnd gap=$gap threshold=${nearBottomThresholdPx.toInt()} firstVisible=$firstIdx firstOffset=$firstOff canScrollForward=${listState.canScrollForward} canScrollBackward=${listState.canScrollBackward} totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} isScrollInProgress=${listState.isScrollInProgress} → $result",
                )
            }
            result
        }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
}

@Composable
internal fun rememberContentOverflows(listState: androidx.compose.foundation.lazy.LazyListState, tagScroll: kotlin.String, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>): androidx.compose.runtime.State<kotlin.Boolean> {
    return remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [T-android-scrollbtn-turn-walk] Per-index observed item sizes. These once
    // backed the up-button's isFarFromTop/isFarFromBottom gate (now removed in
    // favour of the shared !isNearBottom condition); they are retained because
    // the streaming-content glide still uses the running average to size its
    // per-frame scroll steps.
    //
    // The list is reverseLayout=true: index 0 is the NEWEST message (visual
    // bottom), the highest index is the OLDEST (visual top).
    // [T-android-scroll-to-first-message] Compose's LazyListLayoutInfo exposes
    // sizes of CURRENTLY visible items only — no contentSize / contentOffset
    // equivalent to iOS's UIScrollView. Earlier estimations (off-screen item
    // count × avg/min visible-item size) misfired badly because one assistant
    // message expands into many FlatChatItems (header, several markdown
    // blocks, tool blocks, typing indicator); the index count balloons out
    // of proportion to actual pixel distance, so the up-button kept popping
    // up right above the input bar when only a tool-block + header lay
    // off-screen.
    //
    // Instead we OBSERVE: every time an item enters the viewport, cache its
    // (index → size). As the user scrolls we accumulate ground truth for
    // every index we've ever seen. Distance to either end then = sum of
    // cached sizes for the off-screen indices we know about, plus the
    // visible items' real partial overhang. Indices we've never seen still
    // contribute zero — that's a strict lower bound, so we can only
    // under-show the button, never flash it near an end.
}

@Composable
internal fun rememberScrollToPreviousUserTurn(messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>, listState: androidx.compose.foundation.lazy.LazyListState, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, lastJumpedUserIdState: androidx.compose.runtime.MutableState<kotlin.String?>): suspend () -> kotlin.Unit {
    val messages by messagesState
    var lastJumpedUserId by lastJumpedUserIdState
    return scrollToPreviousUserTurn@{
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@scrollToPreviousUserTurn
        // Ordered oldest → newest list of user-message ids, matching the order
        // the user reads the conversation in.
        val userIds = messages.filter { it.role == "user" }.map { it.id }
        if (userIds.isEmpty()) {
            // No user turns (rare) — fall back to the oldest item (the HIGHEST
            // index; see the orientation note below) so the button is never a
            // dead no-op.
            tracedScrollToItem("FAB-UP/no-user-turns", (info.totalItemsCount - 1).coerceAtLeast(0), 0)
            return@scrollToPreviousUserTurn
        }
        // Row-index orientation — measured on device, not inferred. Earlier
        // rounds of this feature flip-flopped because "reverseLayout=true" was
        // reasoned about instead of observed; the authoritative signal is each
        // visible item's `offset` (its pixel position in the viewport).
        //
        // A real dump (7-turn session, viewport spanning rows 8..23):
        //     idx  offset  kind      msg
        //      11     122  user      c4061ef7  (TURN5)
        //      15     461  user      4e2a5ad2  (TURN4)
        //      19     800  user      bfa090b0  (TURN3)
        //      23    1403  user      345b4a6c  (TURN2)
        //
        // Offset ASCENDS with index, so a HIGHER index sits LOWER on screen; and
        // the turn numbers DESCEND, so a HIGHER index is also OLDER. Both facts
        // hold at once because this list renders newest-at-top.
        //
        // Therefore:
        //   • visual top      = LOWEST visible index   (used here)
        //   • older / "back"  = HIGHER index           (used by the seek below)
        // Conflating those two — assuming the visual top must also be the
        // "back" direction — is what produced the wrong anchor in the previous
        // implementation.
        val topKey = visible.minByOrNull { it.index }?.key as? String
        // Map the top row back to its position in `messages`. Row keys are
        // "<kind>:<messageId>[:extra]", and some kinds append their own suffix
        // after the id (e.g. "mdblock:<id>:text_<id>_0:1"), so the id is the
        // segment between the FIRST and SECOND colon — not everything after the
        // first one.
        val topMessageId = topKey
            ?.split(':')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        // `messages` is a TAIL WINDOW (ChatViewModel.uiMessages + loadOlderMessages),
        // so the visible top row can belong to a message that is not loaded yet.
        // The top of the viewport is the OLDEST content on screen, so when it
        // resolves to nothing the user is at/above the start of the window —
        // anchor on the oldest loaded message (index 0) and let the walk proceed
        // from there.
        val topMsgIdx = topMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        // The current turn's anchor = nearest user message AT OR ABOVE the top
        // row (searching backwards through the conversation).
        val currentAnchor = messages.take(topMsgIdx + 1).lastOrNull { it.role == "user" }?.id
            ?: userIds.first()
        // Decide the target — the rule from iOS `scrollToPreviousUserTurn`: if
        // the viewport is already at the anchor we last jumped to (the user has
        // seen this turn's start), step to the previous turn; otherwise land on
        // the current turn's anchor first.
        //
        // Android cannot re-derive the walk position from the viewport the way
        // iOS does, for two independent reasons — so once a walk has started we
        // always continue from `lastJumpedUserId`:
        //
        //  1. A LazyColumn CLAMPS at the end of its content. Near the oldest rows
        //     the target can't reach the top, `currentAnchor` recomputes to the
        //     same turn every tap, and the walk oscillates (device: taps 6/7
        //     flipping bfa090b0 <-> 345b4a6c).
        //  2. Top-aligning the landing (below) deliberately anchors the viewport
        //     on a NEWER row than the target, so the recomputed `currentAnchor`
        //     reads a turn NEWER than the one we just jumped to — the walk then
        //     bounced 9 -> 22 -> 9 -> 22 forever.
        //
        // `lastJumpedUserId` is cleared on drag / jump-to-bottom / new messages /
        // session switch, so this only ever chains genuine repeated taps; the
        // first tap after any of those still anchors off the viewport.
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) {
            userIds[pos - 1]
        } else {
            walkFrom
        }
        // [T-android-fab-up-skip-visible] Never "scroll" to a turn the user is
        // already looking at.
        //
        // `currentAnchor` is the nearest user message AT OR ABOVE the viewport
        // top, so when a user bubble is already on screen it IS the anchor —
        // and on the first tap (lastJumpedUserId == null) the target is the
        // anchor itself. The button then scrolls to a bubble that is already
        // visible, which reads as a dead tap: the transcript barely moves and
        // the user has to tap twice to go back one turn.
        //
        // Fix: treat every user turn currently rendered in the viewport as
        // "already seen" and walk further back until we find one that is not.
        // Only fully-visible bubbles count — a turn scrolled half off the top
        // edge is one the user has NOT finished reading, and jumping to it to
        // align its top edge is a genuine, useful move.
        //
        // Visibility is read from the pre-seek layout on purpose: the seek
        // below mutates the viewport, so anything derived afterwards would
        // describe where the search happened to stop, not where the user was.
        val viewportTop = info.viewportStartOffset
        val viewportBottom = info.viewportEndOffset
        val fullyVisibleUserIds: Set<String> = visible
            .asSequence()
            .filter { item ->
                val k = item.key as? String ?: return@filter false
                k.startsWith("user:") &&
                    item.offset >= viewportTop &&
                    item.offset + item.size <= viewportBottom
            }
            .mapNotNull { (it.key as? String)?.removePrefix("user:")?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val target = if (steppedTarget in fullyVisibleUserIds) {
            // Walk back (toward older turns = LOWER index in `userIds`, which is
            // ordered oldest → newest) past every turn already on screen. If all
            // of them are visible we stop at the oldest — `scrollToItem` clamps,
            // so this stays a harmless no-op at the start of the conversation
            // rather than an overscroll.
            var i = userIds.indexOf(steppedTarget)
            while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
            userIds[i]
        } else {
            steppedTarget
        }
        // Resolve the target user bubble's row index by its stable key.
        //
        // The flat-item list is built inside the LazyColumn's own scope and is
        // not reachable from here, so an off-screen target has to be found by
        // walking the viewport toward it. Two things make that delicate, and
        // both were observed failing on device before this shape:
        //
        //  1. The seek MUTATES the viewport. The anchor must therefore be
        //     computed BEFORE any seeking (it is — `currentAnchor` above is
        //     derived from the pre-seek layout), or every tap re-anchors to
        //     wherever the previous tap's seek happened to stop and the walk
        //     never advances.
        //  2. A seek that overshoots to the oldest row leaves the list unable
        //     to scroll further; if the target still isn't found we must
        //     RESTORE the original position rather than strand the user at the
        //     top of the transcript.
        val targetKey = "user:$target"
        fun indexOfTargetKey(): Int? =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

        val restoreIndex = listState.firstVisibleItemIndex
        val restoreOffset = listState.firstVisibleItemScrollOffset
        var targetIndex = indexOfTargetKey()
        var guard = 0
        // Scan every row until the target's key shows up.
        //
        // The previous seek walked only toward HIGHER indices in viewport-sized
        // strides, and that produced the user's dead tap: opening the 读屏
        // session and dragging slightly left the viewport on rows 0..8 with the
        // target user bubble at row 9 — just BELOW it. The stride seek jumped
        // 17 -> 41, straight past the target, found nothing, and fell into
        // RESTORE (a visible no-op).
        //
        // Direction genuinely cannot be assumed: `currentAnchor` is the nearest
        // user message at or above the viewport TOP, and how far its row sits
        // from the current window depends entirely on how tall the intervening
        // tool / thinking / shell-output blocks are — which in real
        // conversations is wildly variable (one assistant message in this
        // session spans rows 23..46). Sweeping the whole list from the top is
        // direction-free and cannot step over the target; `scrollToItem` takes
        // any index directly, so each step is just a layout pass.
        if (targetIndex == null) {
            val maxIdx = (info.totalItemsCount - 1).coerceAtLeast(0)
            var probe = 0
            while (targetIndex == null && probe <= maxIdx && guard++ < 200) {
                tracedScrollToItem("FAB-UP/seek", probe, 0)
                targetIndex = indexOfTargetKey()
                // Advance past whatever is now on screen rather than one row at
                // a time, but never skip ahead of the rows we have inspected.
                val hi = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
                probe = (hi ?: probe) + 1
            }
        }
        if (targetIndex == null) {
            // Target never materialised — undo the seek so the button is a
            // no-op rather than a jump to the very top.
            tracedScrollToItem("FAB-UP/restore", restoreIndex, restoreOffset)
            return@scrollToPreviousUserTurn
        }
        lastJumpedUserId = target
        val landIndex: Int = targetIndex
        // Land the user bubble's TOP edge just under the header — iOS's
        // `scrollToItem(at: .top)`.
        //
        // Uses the scrollOffset overload directly: it is defined against the
        // layout direction, so no sign has to be inferred and no stepping /
        // scrollBy feedback loop is needed (both were tried and failed — anchor
        // granularity is ~130px, far coarser than the residual gap).
        //
        // Calibrated on device (Pixel 4a, 读屏 session, 148px row, 1646px
        // viewport) by sweeping the parameter and reading the bubble's physical
        // top-y from uiautomator:
        //     scrollOffset  -400  ->  y=1254
        //     scrollOffset  -800  ->  y= 854
        //     scrollOffset -1200  ->  y= 454
        // A clean line, slope +1: screen_y = scrollOffset + 1654. Solving for a
        // top edge just under the header (header bottom y=304) gives -1324,
        // which measured EXACTLY y=330 height=65 (unclipped), and -1300 measured
        // y=354 — both matching the model.
        //
        // Rewritten against runtime quantities so nothing is device-specific:
        // scrollOffset = rowSize - viewportHeight + beforeContentPadding, where
        // beforeContentPadding is the space the list already reserves for the
        // floating header.
        val vpH = listState.layoutInfo.viewportSize.height
        // Row height must come from the item itself: it varies per bubble, and
        // the offset has to account for it. Position once to materialise the row,
        // read its size, then place it precisely.
        tracedScrollToItem("FAB-UP/turn-walk", landIndex, 0)
        val rowSize = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == targetKey }?.size ?: 0
        // The inset is the LazyColumn's own top content padding, which is exactly
        // the space reserved for the floating header — so the bubble comes to
        // rest just below it rather than behind it. Taken from layoutInfo rather
        // than hardcoded, so it follows the header/status-bar height on any
        // device.
        val headerInset = listState.layoutInfo.beforeContentPadding
        val topOffset = rowSize - vpH + headerInset
        tracedScrollToItem("FAB-UP/turn-walk-top", landIndex, topOffset)
    }
}

@Composable
internal fun ScrollEffect1(listState: androidx.compose.foundation.lazy.LazyListState, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>, lastJumpedUserIdState: androidx.compose.runtime.MutableState<kotlin.String?>, lastInterruptMsState: androidx.compose.runtime.MutableState<kotlin.Long>, isUserDraggingState: androidx.compose.runtime.MutableState<kotlin.Boolean>) {
    var userScrolledAway by userScrolledAwayState
    var lastJumpedUserId by lastJumpedUserIdState
    var lastInterruptMs by lastInterruptMsState
    var isUserDragging by isUserDraggingState
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    // [T-android-scrollbtn-turn-walk] A manual drag breaks the
                    // up-button's turn-walk chain: the next tap should re-anchor
                    // to wherever the user landed, not continue the old sequence.
                    lastJumpedUserId = null
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                    lastInterruptMs = System.currentTimeMillis()
                    val nowAtBottom = isNearBottom.value
                    val newScrolledAway = !nowAtBottom
                    if (newScrolledAway != userScrolledAway) {
                        userScrolledAway = newScrolledAway
                    }
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel ->
                    isUserDragging = false
                else -> Unit
            }
        }
    }
}

@Composable
internal fun ScrollEffect2(messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>, lastUserAppendMsState: androidx.compose.runtime.MutableState<kotlin.Long>, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>) {
    val messages by messagesState
    var lastUserAppendMs by lastUserAppendMsState
    var userScrolledAway by userScrolledAwayState
    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull() ?: return@LaunchedEffect
        if (lastMsg.role != "user") return@LaunchedEffect
        // T255: catch-all reset so any user-message append path
        // (send button / Enter / enqueue-while-streaming / future
        // entry points) restores auto-follow even if a call-site reset
        // was missed.
        lastUserAppendMs = System.currentTimeMillis()
        userScrolledAway = false
        tracedScrollToItem("LE(messages.size)USER-SEND-SNAP", 0, 0)
    }
}

@Composable
internal fun ScrollEffect3(viewModel: com.openminis.app.ui.chat.ChatViewModel, messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>, listState: androidx.compose.foundation.lazy.LazyListState, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, avgItemSize: androidx.compose.runtime.State<kotlin.Int>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>, lastInterruptMsState: androidx.compose.runtime.MutableState<kotlin.Long>) {
    val messages by messagesState
    var userScrolledAway by userScrolledAwayState
    var lastInterruptMs by lastInterruptMsState
    LaunchedEffect(listState, userScrolledAway) {
        // T-streaming-side-channel: combine the canonical messages flow
        // with streamingById so growth signals (content length, toolBlocks
        // count, awaiting flag) reflect the live stream — otherwise the
        // auto-follow scroll-to-bottom stops firing during a turn because
        // messages no longer ticks per token.
        kotlinx.coroutines.flow.combine(
            snapshotFlow { messages },
            viewModel.streamingById,
        ) { msgs, stream ->
            val effective = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
            val m = effective.lastOrNull { it.role == "assistant" } ?: return@combine null
            // [T-android-tool-autoscroll] Trigger tuple includes a per-block
            // signature (FNV-1a hash over id/kind/status/length) so the
            // collector wakes on RUNNING→SUCCESS transitions, new-block
            // appearances, kind flips, and per-token content growth alike.
            // Without blockSig the previous (lastIdx, growth, size, awaiting)
            // tuple missed several mid-loop transitions and the auto-follow
            // skipped scroll ticks during tool swaps.
            var growth: Long = m.content.length.toLong()
            var blockSig: Long = 1469598103934665603L // FNV-1a 64-bit offset basis
            for (b in m.toolBlocks) {
                growth += b.content.length.toLong()
                blockSig = blockSig xor b.id.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.kind.hashCode().toLong()
                blockSig *= 1099511628211L
                blockSig = blockSig xor (b.toolStatus?.ordinal?.toLong() ?: -1L)
                blockSig *= 1099511628211L
                blockSig = blockSig xor b.content.length.toLong()
                blockSig *= 1099511628211L
            }
            ScrollFollowKey(
                lastIndex = effective.lastIndex,
                growth = growth,
                toolBlockCount = m.toolBlocks.size,
                awaiting = m.isAwaitingModelResponse,
                blockSig = blockSig,
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            // [T-android-stream-grow-anim] Follow the bottom often enough that
            // the viewport never falls more than a fraction of one item behind.
            // Diagnostics with a 350ms sample showed GLIDE starting from
            // fIdx=1..5 — the viewport was whole items behind, and
            // animateScrollToItem across multiple items snaps most of the
            // distance instantly then animates only the last sliver, so it
            // read as "no animation". With the VM-side dual-path flush already
            // pacing content updates to 200–500ms, a 120ms scroll sample keeps
            // the viewport within the SAME item (fIdx=0, small fOff), where
            // animateScrollToItem is a genuine smooth glide. (The "accumulate
            // then glide" the user asked for now lives in the VM flush; here we
            // just keep up smoothly.)
            .sample(120L)
            .collect {
                if (!viewModel.isStreaming.value) return@collect
                if (userScrolledAway) return@collect
                if (listState.isScrollInProgress) return@collect
                val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
                if (sinceInterrupt < 1000L) return@collect
                // [T-android-stream-grow-anim] Frame-driven glide to the bottom.
                // animateScrollToItem(0) was the problem: when the viewport had
                // fallen >= 1 item behind (diagnostics showed fIdx=1..5 during
                // fast streams), it snaps most of the distance instantly and
                // animates only the final sliver — reading as "no animation".
                // Instead, scroll toward the bottom a bounded amount per frame
                // inside one scroll session until index 0 is fully pinned
                // (fIdx==0 && fOff==0). Every frame moves, so the whole catch-up
                // is visibly animated regardless of how many items behind we
                // are. We never measure item heights (the source of earlier
                // stutter) — we just step toward the bottom and stop when the
                // pin condition is met. In reverseLayout, the bottom (newest,
                // index 0) is the NEGATIVE scroll direction.
                if (listState.firstVisibleItemIndex != 0 ||
                    listState.firstVisibleItemScrollOffset != 0
                ) {
                    // [T-android-stream-grow-anim review] Cold start: item sizes
                    // not measured yet → avgItemSize==0 → the distance estimate
                    // is bogus and the glide would under-scroll. Snap instead;
                    // by the next sample the cache is warm and glides resume.
                    if (avgItemSize.value <= 0) {
                        tracedScrollToItem("LE(streaming-content)cold", 0, 0)
                        return@collect
                    }
                    // [T-android-stream-grow-anim] Ease-out frame-driven glide
                    // to the bottom. Each frame moves a fraction of the
                    // estimated remaining distance so the motion decelerates as
                    // it lands (curveEaseOut, the shape iOS uses for its 0.2s
                    // contentOffset animate). Two caps keep it smooth on the
                    // matched matters:
                    //   • per-frame step is capped well BELOW a typical
                    //     streaming fragment (~tens of px) so a single frame
                    //     can never leap a whole item — that leap was the
                    //     residual "frames=1 jump" in earlier diagnostics
                    //     (avg-estimated remaining over-shot, step hit the cap,
                    //     one frame crossed an item).
                    //   • a gentler 0.22 fraction + 14px floor stretches even a
                    //     short catch-up across several frames, so it always
                    //     reads as a glide rather than a hop.
                    // Distance uses the running average visible-item height (an
                    // aggregate, not a per-item delta, so no re-block noise).
                    val avg = avgItemSize.value.toFloat().coerceAtLeast(1f)
                    // Step ceiling: ~40% of the average item, so >= ~3 frames
                    // cross any one item. Bounded to a sane absolute window.
                    val stepCeil = (avg * 0.40f).coerceIn(28f, 80f)
                    runCatching {
                        listState.scroll {
                            var guard = 0
                            while (
                                (listState.firstVisibleItemIndex != 0 ||
                                    listState.firstVisibleItemScrollOffset != 0) &&
                                guard < 120
                            ) {
                                guard++
                                val remaining = listState.firstVisibleItemIndex * avg +
                                    listState.firstVisibleItemScrollOffset
                                val step = (remaining * 0.22f).coerceIn(14f, stepCeil)
                                // Negative = toward newest/bottom in reverseLayout.
                                val consumed = withFrameNanos { scrollBy(-step) }
                                if (consumed == 0f) break
                            }
                        }
                    }
                }
            }
    }
}

@Composable
internal fun ScrollEffect4(isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>, listState: androidx.compose.foundation.lazy.LazyListState, lastStreamEndMsState: androidx.compose.runtime.MutableState<kotlin.Long>, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>, lastInterruptMsState: androidx.compose.runtime.MutableState<kotlin.Long>, streamingNowFlag: kotlin.Boolean) {
    val isStreaming by isStreamingState
    var lastStreamEndMs by lastStreamEndMsState
    var userScrolledAway by userScrolledAwayState
    var lastInterruptMs by lastInterruptMsState
    LaunchedEffect(streamingNowFlag) {
        if (streamingNowFlag) return@LaunchedEffect  // edge fires only on streaming→idle
        // [T-android-stream-end-arm-race] Stamp the edge BEFORE the 220 ms
        // delay below: the final reflow that would otherwise arm
        // userScrolledAway (and turn this very LE into a no-op) fires inside
        // that window, so the grace has to already be open when it lands.
        lastStreamEndMs = System.currentTimeMillis()
        // [T-android-stream-end-reflow-flicker-v18] The user-was-reading
        // branch is a HARD NO-OP. After the v18 ChatFlatItems fix (keep
        // rawFragments on the last assistant turn regardless of
        // isStreaming), the mdblock key set no longer changes across
        // stream-end, so LazyColumn's key reconciliation preserves the
        // user's visual position natively. Versions v6..v17 of this LE
        // tried every cross-key restore strategy (exact, fuzzy, neighbor,
        // numeric, totalItems-delta) and every one made the symptom
        // worse — see commits cd08a334 and 9873a3ed for the analysis.
        kotlinx.coroutines.delay(220)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        val sinceInterrupt = System.currentTimeMillis() - lastInterruptMs
        if (sinceInterrupt < 1000L) return@LaunchedEffect
        // At-bottom settle: user was following the stream; re-pin to
        // index 0 so async self-sizing (code blocks / tables / images)
        // finishing after our last per-token scrollToItem still leaves
        // the bottom item flush with the viewport bottom.
        tracedScrollToItem("stream-end/AT-BOTTOM-RE-PIN", 0, 0)
        withFrameNanos { }
        kotlinx.coroutines.delay(900)
        if (userScrolledAway || listState.isScrollInProgress) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0) {
            val sinceDrag = System.currentTimeMillis() - lastInterruptMs
            if (sinceDrag > 1500L) tracedScrollToItem("stream-end/LATE-REPIN", 0, 0)
        }
    }
}

@Composable
internal fun ScrollEffect5(listState: androidx.compose.foundation.lazy.LazyListState, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, tracedScrollBy: suspend (kotlin.String, kotlin.Float) -> kotlin.Unit, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>) {
    var userScrolledAway by userScrolledAwayState
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Track (bottom-item-size, total-content-size, viewport-end) so
            // any height change re-fires.
            Triple(
                bottomItem?.size ?: -1,
                info.visibleItemsInfo.sumOf { it.size },
                info.viewportEndOffset,
            )
        }.distinctUntilChanged().collectLatest { _ ->
            if (userScrolledAway) return@collectLatest
            if (listState.isScrollInProgress) return@collectLatest
            if (!isNearBottom.value) return@collectLatest
            // Pin only if the current offset has actually drifted from
            // the bottom (avoid no-op scroll spam).
            val info = listState.layoutInfo
            val bottomItemNow = info.visibleItemsInfo.firstOrNull { it.index == 0 }
            // T180/T181: in reverseLayout=true, the bottom item (index 0,
            // newest) anchors to viewport bottom when its offset == 0.
            // When NEW items are inserted at index 0 and the LazyList does
            // NOT auto-shift older items, the bottom item ends up below
            // the viewport (negative offset). That's the only drift we
            // need to fix. Don't react to gap<0 — for a tall bottom item
            // that's the correct anchored-at-bottom state.
            val bottomOffsetNow = bottomItemNow?.offset ?: 0
            if (bottomOffsetNow >= 0) return@collectLatest
            tracedScrollToItem("LAYOUT-DRIFT-SNAP", 0, 0)
            val infoAfter = listState.layoutInfo
            val bottomNow = infoAfter.visibleItemsInfo.firstOrNull { it.index == 0 }
            if (bottomNow != null && bottomNow.offset < 0) {
                tracedScrollBy("LAYOUT-DRIFT-CLIP", bottomNow.offset.toFloat())
            }
        }
    }
}

@Composable
internal fun ScrollEffect6(viewModel: com.openminis.app.ui.chat.ChatViewModel, isStreamingState: androidx.compose.runtime.State<kotlin.Boolean>, listState: androidx.compose.foundation.lazy.LazyListState, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>, lastInterruptMsState: androidx.compose.runtime.MutableState<kotlin.Long>) {
    val isStreaming by isStreamingState
    var userScrolledAway by userScrolledAwayState
    var lastInterruptMs by lastInterruptMsState
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                // [T-android-small-drag-snaps-back] Re-pin to bottom after a
                // drag-end ONLY while actively STREAMING. T170's purpose is to
                // catch content that grew during a drag we suppressed the
                // streaming-follow LE for — that only matters mid-stream. When
                // NOT streaming, a finger lift near the bottom must NOT yank the
                // viewport back: that was the "small upward drag snaps back to
                // bottom" bug (logged: settle-after-interaction firing with
                // firstOff=27/34 right after a sub-threshold peek). The earlier
                // bottom-item-offset<0 guard could not distinguish a user's
                // upward peek from content drift — in reverseLayout BOTH push the
                // bottom item's offset negative — so it never actually gated.
                // isStreaming is the real discriminator. The streaming-content LE
                // (gated by lastInterruptMs + isScrollInProgress) handles the
                // follow itself; this is just the post-drag settle for it.
                if (!inProgress && !userScrolledAway && isNearBottom.value &&
                    viewModel.isStreaming.value
                ) {
                    tracedScrollToItem("settle-after-interaction", 0, 0)
                }
                // [T-android-scroll-fling-stale-userscrolledaway #49] Catch
                // the stale-userScrolledAway window left by a fling-from-
                // bottom: DragInteraction.Stop fires at finger lift while
                // isNearBottom is still true, so the DragStop handler sets
                // userScrolledAway=false; the fling then carries the
                // viewport off-bottom. Use the isScrollInProgress→false
                // edge (past drag AND fling settle) as the authoritative
                // checkpoint and re-arm userScrolledAway.
                if (!inProgress && !isNearBottom.value && !userScrolledAway) {
                    userScrolledAway = true
                }
            }
    }
}

@Composable
internal fun ScrollEffect7(viewModel: com.openminis.app.ui.chat.ChatViewModel, listState: androidx.compose.foundation.lazy.LazyListState, lastStreamEndMsState: androidx.compose.runtime.MutableState<kotlin.Long>, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>) {
    var lastStreamEndMs by lastStreamEndMsState
    var userScrolledAway by userScrolledAwayState
    LaunchedEffect(listState) {
        snapshotFlow { isNearBottom.value }
            .distinctUntilChanged()
            .collect { nearBottom ->
                val firstIdx = listState.firstVisibleItemIndex
                // Content-growth drift during a live turn is not a user intent.
                val sinceStreamEnd = System.currentTimeMillis() - lastStreamEndMs
                val streamingDrift = viewModel.isStreaming.value ||
                    (lastStreamEndMs > 0L && sinceStreamEnd <= STREAM_END_ARM_GRACE_MS)
                if (!nearBottom && !userScrolledAway && !streamingDrift) {
                    userScrolledAway = true
                    // [T-android-stream-follow-dies-after-first-paragraph]
                    // Arming kills streaming auto-follow, so record WHY. If a
                    // "reply stopped scrolling" report ever recurs, this line
                    // is the first thing to grep: streaming=true here means
                    // the drift heuristic let a non-gesture through.
                    AppLogger.debug(
                        "ScrollFollow",
                        "userScrolledAway ARMED inProgress=${listState.isScrollInProgress} " +
                            "firstIdx=$firstIdx streaming=${viewModel.isStreaming.value}",
                    )
                } else if (!nearBottom && streamingDrift) {
                    val why = if (viewModel.isStreaming.value) "streaming content growth"
                              else "stream-end reflow (+${sinceStreamEnd}ms)"
                    AppLogger.debug(
                        "ScrollFollow",
                        "arm SUPPRESSED ($why) firstIdx=$firstIdx",
                    )
                }
            }
    }
}
