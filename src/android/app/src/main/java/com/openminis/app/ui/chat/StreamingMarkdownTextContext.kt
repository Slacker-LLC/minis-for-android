package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.md.BlockParser
import com.openminis.app.ui.chat.md.MdDocument
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.ui.markdown.StreamingMarkdownProjectionSession
import com.openminis.app.ui.sandbox.FileCategory
import com.openminis.app.ui.sandbox.fileCategoryFor
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

// ─── MinisTextKit hook ────────────────────────────────────────────────────────
// Each markdown fragment renders inside a [MarkdownBlock] / [RenderBlock]
// scope that provides a [TextShardId] via [LocalShardId]. [MdText] reads it,
// registers a [TextShard] with the ambient [SelectionController] (if any),
// and draws the selection highlight inside its existing drawBehind. The
// indirection lets MdText stay markdown-agnostic — paragraph, heading,
// blockquote, table cell, etc. all participate uniformly without each
// caller having to plumb a per-text-node identifier.
val LocalShardId = compositionLocalOf<TextShardId?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Per-MdText sub-id allocator.
 *
 * A single markdown fragment ([MarkdownBlock] / [StreamingMarkdownText]) is
 * parsed into many [MdBlock]s and each is rendered by a [RenderBlock] that may
 * itself emit several [MdText]s (every list item, table cell, blockquote line,
 * heading, paragraph…). ALL of them read the same ambient [LocalShardId], so
 * before this fix they registered [TextShard]s under one identical
 * [TextShardId] key — and `SelectionController.shards` is a map keyed by id, so
 * each registration overwrote the previous one. Only the LAST MdText in a
 * fragment survived in the registry, so a long (multi-paragraph) reply was
 * un-selectable except for its final text node; short single-paragraph replies
 * happened to have exactly one MdText and worked, which is why the regression
 * looked length-dependent.
 *
 * This allocator hands each MdText a stable, unique index within its fragment.
 * `remember { allocator.next() }` runs once per MdText composition slot, so the
 * index is assigned in first-composition order and survives recomposition
 * (Compose re-runs `remember`s in the same slot order). The index is appended
 * to the base shardId so every text node gets a distinct [TextShardId] and all
 * register independently.
 */
internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndexAllocator = compositionLocalOf<ShardSubIndexAllocator?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Provide a per-fragment
 * [ShardSubIndexAllocator] so every [MdText] composed under [content] gets a
 * distinct shard sub-index. The allocator is `remember`ed once per fragment
 * composable instance (NOT keyed on the block list): its counter is monotonic,
 * so when blocks stream in / change, newly-added MdText slots draw fresh
 * indices while existing slots keep theirs — indices never collide. Each
 * MdText reads it via [LocalShardSubIndexAllocator].
 */
@Composable
internal fun ShardSubIndexScope(content: @Composable () -> Unit) {
    val allocator = remember { ShardSubIndexAllocator() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShardSubIndexAllocator provides allocator,
        content = content,
    )
}

/** Selection-highlight fill color. Resolved per-composition for theme support. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun currentSelectionHighlightColor(): Color {
    // Match Android's default textSelectHandle tint at ~30% alpha so it
    // visually overlays without obscuring the glyphs underneath.
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return accent.copy(alpha = 0.28f)
}

// ─── Markdown color palette — resolved per-composition via currentMdColors() ──
internal data class MdColors(
    val text: Color,
    val codeText: Color,
    val codeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBg: Color,
    val link: Color,
    val blockquote: Color,
    val divider: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun currentMdColors(): MdColors {
    val c = com.openminis.app.ui.theme.LocalChatPalette.current
    return MdColors(
        text = c.primaryText,
        codeText = c.codeBlockText,
        codeBg = c.codeBlockBg,
        inlineCodeText = c.inlineCodeText,
        inlineCodeBg = c.inlineCodeBg,
        link = c.link,
        blockquote = c.secondaryText,
        divider = c.separator,
        tableBorder = c.tableBorder,
        // The header is always the grey fill (the body is white on light), whatever the surface behind it.
        tableHeaderBg = if (c.isDark) c.secondaryBg else Color(0xFFF2F2F7),
    )
}

val LocalMarkdownFontScale = compositionLocalOf { 1f }

/** Handler invoked when a markdown URL span is tapped. Provided by ChatScreen. */
val LocalMarkdownUrlClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-markdown-image-gallery-cross-message] Handler invoked when a
 * markdown image (`![alt](src)`) inside an assistant message is tapped, with
 * the parent message id so the host can collect every sibling image across
 * the conversation and open a paged gallery (mirrors iOS
 * `handleMarkdownImageTap` in AIChatView.swift:2082).
 *
 * Distinct from [LocalMarkdownUrlClickHandler] so the existing url-only
 * routing keeps working unchanged. When null, the image renderer falls back
 * to [LocalMarkdownUrlClickHandler] (which routes a single-item open).
 *
 * The id corresponds to [TextShardId.messageId] supplied via [LocalShardId]
 * — every assistant text block already provides one, so the renderer reads
 * the id from the ambient shard rather than threading a separate prop.
 */
val LocalMarkdownImageTapHandler =
    compositionLocalOf<((messageId: String, url: String) -> Unit)?> { null }

/**
 * Session id that owns the currently-rendering markdown. Used by
 * `resolveMdMediaFile` to prefer `RuntimePathRegistry.resolveSessionHostPath` — the
 * session-scoped resolver — over the global `bindMounts` map, which is
 * last-writer-wins across sessions. Null in contexts that don't know the
 * owning session (e.g. standalone previews).
 */
val LocalMarkdownSessionId = compositionLocalOf<String?> { null }

// The chat redesign's body text: 17 / 27.2.
internal val BaseFontSizeDefault = 17.sp

internal val BaseLineHeightDefault = 27.2.sp

internal val BaseFontSize: TextUnit
    @Composable get() = BaseFontSizeDefault * LocalMarkdownFontScale.current

internal val BaseLineHeight: TextUnit
    @Composable get() = BaseLineHeightDefault * LocalMarkdownFontScale.current

internal val InlineCodeCornerRadius = 6.dp

/**
 * Streaming-friendly markdown renderer.
 *
 * Strategy:
 * - Parse markdown into a list of Block objects
 * - Each block is an independent composable — Compose's structural diff only
 *   recomposes blocks that actually changed
 * - The LAST block is the only one that changes during streaming (text appends to it)
 * - Completed blocks above are structurally stable → Compose skips them
 *
 * Update cadence: while [isStreaming] is true, content updates are coalesced
 * to at most one re-parse every [STREAMING_THROTTLE_MS] (~120 ms). Pixel 4a
 * traces showed every TextDelta (~5 ms cadence) was triggering a full
 * `parseMarkdownBlocks` over the entire accumulating string + a recompose of
 * every RenderBlock — a 5-row markdown table plus a few tool calls was enough
 * to ANR the main thread with 22 MB GC every 2 s. Throttling the *display*
 * content (not the underlying StateFlow) keeps the conversation visually
 * live (3-4 fps of growth is plenty for reading) while leaving 90 % of the
 * frame budget free.
 *
 * When the stream finishes ([isStreaming] flips to false), the final value
 * is published immediately so the user never sees a truncated last frame.
 */
// Adaptive streaming throttle, mirrors iOS CollectionViewMessageListV3
// `flushStreamingLayout` (100 ms when auto-scrolling, 3 s when away). On
// Android we don't have direct access to the chat-level scroll state from
// here, so substitute "doc length" as a proxy: long documents already cost
// more per parse pass, so amortize them by sampling less often. Crashes
// observed on Pixel 6 traced to ICU `RegexPattern::matcher` allocations
// piling up under Scudo (OOM at ~140s of streaming) — slowing parses on
// large bodies cuts native allocation pressure dramatically.
//
// [T-android-stream-flush-dualpath] Time-throttle tiers ported verbatim from
// iOS AIChatViewModel+SSEStream (the `throttle` ladder): the time path is one
// half of the dual-path flush — the other half is the newline fast-path below.
// Tiers scale with total length to hold the Pixel 4a ANR / Pixel 6 Scudo-OOM
// line on dense streams while keeping short replies responsive.
//   < 500  : 200ms   < 2000 : 300ms   < 32K : 500ms
//   < 64K  : 1000ms  < 128K : 1500ms  else  : 2000ms
internal fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}

@Composable
internal fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    // [T-android-streaming-projection] Parse a virtual-EOF projection while the
    // stream is open so an unclosed fence / inline marker / table candidate
    // keeps ONE node type instead of rendering as literal text and flipping to
    // rich text when the closing characters land. Render-only: the projection
    // is never written back to the message, and a finished stream bypasses it
    // entirely (StreamingMarkdownProjectionResult.verifiedTerminalSource).
    val projectionSession = remember { StreamingMarkdownProjectionSession() }
    // [T-android-streaming-state] Parses go through Eta's conflated-target
    // pipeline so an appended chunk never throws away the parse that is already
    // running (see StreamingMarkdownTargets).
    val parseTargets = remember { StreamingMarkdownTargets() }
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(parseTargets) {
        consumeStreamingMarkdownTargets(
            targets = parseTargets.receiveChannel,
            parse = { target ->
                withContext(Dispatchers.Default) {
                    val projection = projectionSession
                        .project(target.content, isComplete = !target.isStreaming)
                    val parsed = parseMarkdownBlocks(projection.renderedSource)
                    MarkdownParseCaches.prewarm(parsed, mdColors)
                    StreamingMarkdownSnapshot(
                        originalSource = projection.originalSource,
                        renderedSource = projection.renderedSource,
                        isComplete = projection.isComplete,
                        payload = parsed,
                    )
                }
            },
            publish = { snapshot -> blocks = snapshot.payload },
        )
    }
    LaunchedEffect(displayContent, isStreaming) {
        parseTargets.submit(displayContent, isStreaming)
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended
            // word ranges (mirrors iOS TextFadeAnimator). Every other block
            // — completed prefix, non-streaming sessions — renders opaque.
            val lastIdx = blocks.size - 1
            blocks.forEachIndexed { idx, block ->
                if (isStreaming && idx == lastIdx) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides true,
                    ) { RenderBlock(block) }
                } else {
                    RenderBlock(block)
                }
            }
        }
    }
}

/**
 * T285-md: full-document markdown viewer for FilePreviewScreen and any
 * other "open a `.md` file end-to-end" surface. Differs from
 * [StreamingMarkdownText] in two important ways:
 *
 *  1. Renders blocks via [LazyColumn] instead of [Column]. A 200-block
 *     document only composes the on-screen blocks on first frame, so
 *     `parseInline`/`collectInlineMathLatex` (still main-thread per
 *     RenderBlock) costs scale with viewport height, not document
 *     length. Critical for the chat-tap → preview transition: pre-T285-md
 *     a multi-KB markdown ran ~150-300ms of inline scanning across all
 *     blocks during the same frame the navigation animation started,
 *     stuttering the slide-in. (StreamingMarkdownText still uses Column
 *     because chat-side messages live inside ChatScreen's outer
 *     LazyColumn — putting a LazyColumn-in-LazyColumn there would hit
 *     the "infinite vertical constraint" runtime error.)
 *
 *  2. No streaming throttle / snapshotFlow plumbing — the file is
 *     loaded once and the content never mutates after publication, so
 *     the live-tail logic in [StreamingMarkdownText] would just be
 *     overhead.
 *
 * Pass the outer scroll [Modifier] (height/padding) to this composable;
 * do NOT wrap the call site in a `verticalScroll` — the LazyColumn
 * provides the scroll itself.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm —
    // the doc viewer benefits the same way: per-block inline scans become
    // cache hits as blocks scroll into view.
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(content).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Composite key: index disambiguates blocks with identical raw
        // bodies (multiple `---` HR lines, repeated empty paragraphs, etc.
        // would otherwise crash LazyColumn with "Key was already used"),
        // while raw still helps item reuse when the list is rebuilt with
        // the same content at the same position.
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}

// ─── Block-level splitting (Pattern A: ChatGPT/Claude-style scroll stability) ─
//
// Earlier the entire streaming markdown was rendered inside a single
// LazyColumn item. When that item's height grew mid-stream, LazyList's
// per-item anchor couldn't help — the user's scroll position drifted as the
// internal Column reflowed. Splitting the message into one LazyColumn item
// per markdown block shifts the anchor granularity down: completed blocks
// (anything before the trailing fence/blank-line boundary) become frozen
// items whose visual position is preserved by LazyList; only the trailing
// "live" block can change height.
//
// `splitMarkdownIntoBlockTexts` returns ordered raw text fragments. The
// boundary rule is:
//   - blank line OUTSIDE a fenced code block → split (paragraph end)
//   - fenced code block start/end → its own fragment
// Fence-internal blank lines never split. Tables and HR-only lines stay
// attached to their preceding/following fragment because the parser
// detects them at parse time anyway.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val doc = BlockParser(gfm = true).parse(content)
    // Footnote definitions are collected into one list at the end of the message, which needs the whole message.
    if (doc.footnoteLabels.isNotEmpty()) return listOf(content.trimEnd('\n'))
    // One fragment per top-level block, cut where the parser says each block starts, so a list with blank lines
    // between its items, an item holding several paragraphs, a fence with blank lines in it stay in one piece.
    val starts = doc.root.children.map { (it.startLine - 1).coerceIn(0, lines.size - 1) }.distinct().sorted()
    if (starts.isEmpty()) return listOf(content.trimEnd('\n'))
    val definitions = referenceDefinitionLines(doc)
    // the definitions' own lines render as nothing; they are re-added, canonical, where a fragment may use them
    val definitionLine = BooleanArray(lines.size)
    for (r in doc.refDefLines) for (n in r) if (n - 1 in definitionLine.indices) definitionLine[n - 1] = true
    val out = ArrayList<String>(starts.size)
    for ((k, start) in starts.withIndex()) {
        // text before the first block (blank lines, reference definitions) belongs to it
        val from = if (k == 0) 0 else start
        val to = if (k + 1 < starts.size) starts[k + 1] else lines.size
        var fragment = (from until to).filter { !definitionLine[it] }.joinToString("\n") { lines[it] }.trim('\n')
        if (fragment.isBlank()) continue
        // A reference link only resolves where its definition is, so the definitions travel with every fragment that
        // could use one (never into a code fence, where they would be text).
        if (definitions.isNotEmpty() && fragment.contains('[') && !isFenceFragment(fragment)) {
            fragment = fragment + "\n\n" + definitions
        }
        out += fragment
    }
    return out
}

/** The document's link reference definitions as canonical lines, to be appended to fragments that may use them. */
private fun referenceDefinitionLines(doc: MdDocument): String =
    doc.refmap.entries.joinToString("\n") { (label, ref) ->
        val title = if (ref.title.isEmpty()) "" else " \"" + ref.title.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        "[${label.replace("]", "\\]")}]: <${ref.destination}>$title"
    }

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
internal fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    val t = firstLine.trimStart()
    return t.startsWith("```") || t.startsWith("~~~")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

/**
 * Render a single markdown fragment (one or a few related blocks) inside
 * its own composable. Designed to be used as the body of an independent
 * LazyColumn item — each fragment is one item, so its height changes
 * cannot disturb the scroll position of any other fragment.
 *
 * `isStreaming` controls async re-parse: when false (frozen completed
 * fragment), the parse runs once on first composition and is never
 * recomputed. When true (the trailing live fragment), the parse is
 * re-run on every content tick, mirroring the original
 * StreamingMarkdownText behavior.
 */
@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /**
     * MinisTextKit shard id — when supplied, every MdText composed beneath
     * this fragment will register with the ambient [SelectionController].
     * The id should be stable across recompositions so the controller's
     * registry doesn't churn (e.g. "msg:abc:block:7"). Null = participate
     * in no selection (legacy behavior).
     */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            // [T-android-markdown-longtext-selection-broken] Disambiguate the
            // several MdTexts a multi-block fragment renders under this one
            // shardId so each registers its own shard.
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

@Composable
internal fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // For frozen blocks, parse once per distinct fragment text PROCESS-WIDE
    // ([MarkdownParseCaches.blocks]) — scroll-away/return and session re-entry
    // are cache hits instead of fresh main-thread parses. remember() keeps the
    // per-composition lookup free.
    //
    // [T-android-coldload-offmain-parse] Cold-load split: a cache HIT (or a
    // small fragment) still renders synchronously — no flicker on
    // scroll-back / re-entry, and small parses are sub-ms. A cache MISS on a
    // BIG fragment must NOT parse in composition: on session open the
    // viewport's fragments all miss at once and the synchronous
    // parseMarkdownBlocksBlocking + inline scans froze the main thread for
    // seconds (tester log: 3.5–8.5s on a 33K-char message; the streaming
    // breaker/degrade only cover isStreaming=true). Those parse off-main
    // with a bounded plain-text preview in the meantime — same structure as
    // the live branch below.
    if (!isStreaming) {
        val cached = remember(rawText) { MarkdownParseCaches.cachedBlocks(rawText) }
        // [T-android-longtext-anr] Synchronous render ONLY on a real cache HIT.
        // The old `|| rawText.length <= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS` clause
        // let a small fragment parse (block-split + per-block inline regex) on the
        // main thread during composition. Individually sub-ms, but on session open
        // the first screen holds ~20 messages split into dozens of small fragments;
        // when the parallel viewport prewarm (ChatScreen) loses the race, every one
        // of those misses parsed synchronously in the same frame and the aggregate
        // froze the main thread for 30s+ → ANR (minis-2026-07-09-anr.log: all hang
        // stacks in Matcher/Pattern via the inline parser, right after first compose).
        // A cold MISS now always goes off-main with a plain-text preview, bounding
        // the first-frame main-thread cost to cheap Text layouts regardless of how
        // many fragments miss at once. Cache HITs (scroll-back, re-entry, prewarmed
        // rows) stay synchronous and flicker-free.
        if (cached != null) {
            Column(modifier = modifier) {
                cached.forEach { RenderBlock(it) }
            }
            return
        }
        val mdColors = currentMdColors()
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        val blocks = parsed
        Column(modifier = modifier) {
            if (blocks == null) {
                // Bounded plain-text preview while the off-main parse runs —
                // one cheap Text layout, no markdown/regex/AnnotatedString.
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
        return
    }
    // [T-android-live-block-degrade] B-lite: a LIVE fragment that has grown
    // huge is almost always an unsplittable single block (the splitter keeps
    // tables/fences whole — exactly the MiniMax giant-table ANR load). Parsing
    // it in full on every publish is O(fragment) with no upper bound, so over
    // the threshold render a bounded plain-text tail instead and do the full
    // parse ONCE when the fragment freezes (isStreaming flips false above).
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.chat_stream_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = currentMdColors().blockquote,
            )
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    // Live (streaming tail) block.
    //
    // [T-android-stream-flush-dualpath] Throttling moved UP to the message
    // accumulation layer (ChatViewModel.updateAssistantMessage) where the
    // dual-path (time OR newline+chars) flush actually accumulates across the
    // high-frequency token calls. The earlier per-fragment throttle here was
    // structurally broken: streaming text is split into many short-lived
    // fragment items, so this produceState (and its lastFlushMs accumulator)
    // reset on every fragment rebuild and never throttled at all — diagnostics
    // showed every tick flushing. `rawText` arriving here is already paced by
    // the VM, so the fragment just renders it directly; parse stays off-main
    // below.
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    // [T-android-inline-parse-offmain] Snapshot the theme colors in
    // composition so the Default-thread parse below can PREWARM the inline
    // caches with the exact keys RenderBlock will look up — main-thread
    // composition of the live block becomes a pure cache hit.
    val mdColors = currentMdColors()
    // [T-android-streaming-projection] Virtual-EOF projection for the live tail
    // (see StreamingMarkdownTextBody) — the frozen path above never uses it.
    val projectionSession = remember { StreamingMarkdownProjectionSession() }
    val parseTargets = remember { StreamingMarkdownTargets() }
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // [T-android-streaming-state] One consumer owns the parses; appended chunks
    // are never dropped just because a newer target arrived.
    LaunchedEffect(parseTargets) {
        consumeStreamingMarkdownTargets(
            targets = parseTargets.receiveChannel,
            parse = { target ->
                // [T-android-stream-render-profile] Time the whole off-main tick
                // (block split + prewarm/incremental inline+math) — this is what
                // the incremental optimization shrinks.
                val parseStartNs = System.nanoTime()
                val snapshot = withContext(Dispatchers.Default) {
                    val projection = projectionSession.project(target.content, isComplete = false)
                    val parsed = parseMarkdownBlocks(projection.renderedSource)
                    // [T-android-streaming-incremental-inline] Prewarm the frozen
                    // blocks (all but the last) normally. The last block is the
                    // growing live tail: when it's a Paragraph, warm it
                    // incrementally (closed prefix reused + tiny fresh suffix) so
                    // the main-thread RenderBlock resolves to an exact HIT without
                    // re-scanning the whole accumulated paragraph; when it's a
                    // table/list/etc. (which RenderBlock parses non-incrementally)
                    // fall back to the normal per-block prewarm for it.
                    if (parsed.size > 1) MarkdownParseCaches.prewarm(parsed.dropLast(1), mdColors)
                    if (parsed.lastOrNull() is MdBlock.Paragraph) {
                        MarkdownParseCaches.prewarmLiveTail(parsed, mdColors)
                    } else {
                        parsed.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                    }
                    // [T-android-review-p1-fixes] F2(a): deposit the live parse
                    // into the blocks cache so the freeze edge (isStreaming →
                    // false recomposes into the frozen branch with this exact
                    // text) HITs synchronously — no plain-text preview flash, no
                    // off-main re-parse. Only for segments big enough to take
                    // the off-main MISS path at freeze; small ones parse sub-ms
                    // synchronously anyway, and skipping them keeps live ticks
                    // from churning the LRU. [T-android-streaming-projection] And
                    // only when the projection added nothing virtual: the frozen
                    // branch looks this entry up by the RAW fragment text.
                    if (projection.isIdentity && target.content.length > COLD_PARSE_OFFMAIN_THRESHOLD_CHARS) {
                        MarkdownParseCaches.putBlocks(target.content, parsed)
                    }
                    StreamingMarkdownSnapshot(
                        originalSource = projection.originalSource,
                        renderedSource = projection.renderedSource,
                        isComplete = projection.isComplete,
                        payload = parsed,
                    )
                }
                StreamRenderProfiler.recordParse(target.content.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
                snapshot
            },
            publish = { snapshot -> blocks = snapshot.payload },
        )
    }
    LaunchedEffect(displayContent) {
        parseTargets.submit(displayContent, isStreaming = true)
    }
    // [T-android-stream-grow-anim] No height/scroll animation here. We tried
    // animateContentSize to ease the bottom-pinned item's exposed height into a
    // smooth viewport follow, but diagnostics showed the live fragment's
    // composable identity is NOT stable across parse ticks (markdown re-blocks
    // every tick — blocks.size flips 1↔2, last-block position churns), so the
    // animation reset to initialH=0 almost every tick and "popped from zero"
    // instead of gliding, AND dragged single-frame cost to ~750–950ms (Davey).
    // Net regression. The smooth feel comes from reverseLayout's native bottom
    // pin (no jump, no extra layout cost) plus the per-word fade. A genuine
    // iOS-style continuous flow would require token-incremental rendering of the
    // streaming tail, not a height animation on an unstable item.
    Column(modifier = modifier) {
        // Wrap the last block in LocalAppendOnlyFade=true so MdText fades in
        // newly-appended words. [T-android-streaming-incremental-inline] Also
        // flag it as the LIVE tail so its Paragraph inline/math parse goes
        // through the incremental (frozen-prefix + fresh-suffix) path — this is
        // the only block whose raw grows every tick.
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            if (idx == lastIdx) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppendOnlyFade provides true,
                    LocalLiveIncremental provides true,
                ) { RenderBlock(block) }
            } else {
                RenderBlock(block)
            }
        }
    }
}

/**
 * [T-android-live-block-degrade] A LIVE fragment larger than this renders as a
 * bounded plain-text tail until it freezes. 8KB of markdown in one unsplit
 * block is far beyond normal prose paragraphs — only giant tables/fences get
 * here, and those were the per-tick full-re-parse ANR load.
 */
internal const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000

internal const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

/**
 * [T-android-coldload-offmain-parse] A FROZEN fragment above this size whose
 * block parse would be a cache MISS parses off-main (with a plain-text
 * preview in the meantime) instead of synchronously in composition. Below
 * it the parse is sub-ms and the placeholder swap would flicker for nothing.
 */
internal const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000

internal const val COLD_PARSE_PREVIEW_CHARS = 4_000

/**
 * [T-android-coldload-offmain-parse] Composition-snapshot prewarmer for the
 * chat flatten pipeline: returns a thread-safe lambda that block-parses each
 * raw fragment AND prewarms the inline/math caches with the palette captured
 * here. Lets ChatScreen (which cannot see the file-private MdBlock/MdColors
 * types) warm the exact keys RenderBlock will look up, off-main, before the
 * viewport rows first compose.
 */
@Composable
internal fun rememberMarkdownPrewarmer(): (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        { raws: List<String> ->
            for (raw in raws) {
                MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
            }
        }
    }
}

/**
 * Synchronous variant of [parseMarkdownBlocks] used for frozen blocks
 * where we don't need cooperative cancellation. Implemented by reusing
 * the suspend version under a runBlocking on the calling thread — frozen
 * blocks parse once and the input is small, so this is fine.
 */
internal fun parseMarkdownBlocksBlocking(content: String): List<MdBlock> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content) }

// ─── Block model ────────────────────────────────────────────────────────────

internal sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(
        raw: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        /** Column alignment from the delimiter row; empty or shorter than the columns means left. */
        val aligns: List<com.openminis.app.ui.chat.md.Align> = emptyList(),
    ) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    /** T155: display-mode LaTeX rendered via KaTeX (`$$…$$` or `\[…\]`). */
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
    /** Chat file attachment (compact card or inline preview) */
    class FileAttachment(raw: String, val title: String, val url: String) : MdBlock(raw)
}

/** Check if a URL points to a local or sandbox file rather than a standard web URL */
internal fun isSandboxOrLocalUrl(rawUrl: String): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")) {
        return false
    }
    if (lower.startsWith("file://") || lower.startsWith("minis://")) {
        if (lower.startsWith("minis://")) {
            val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull()
            if (uri != null) {
                val action = com.openminis.app.deeplink.DeepLinkHandler.parse(uri)
                if (action !is com.openminis.app.deeplink.DeepLinkAction.Unknown) {
                    return false
                }
            }
        }
        return true
    }
    val guestRoots = listOf("/var/minis", "/workspace", "/root", "/memory", "/skills", "/shared", "/home/minis")
    return guestRoots.any { trimmed == it || trimmed.startsWith("$it/") }
}

internal fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val category = fileCategoryFor(decoded)
    return when (category) {
        FileCategory.VIDEO -> MdBlock.Video(raw, alt, url)
        FileCategory.AUDIO -> MdBlock.Audio(raw, alt, url)
        FileCategory.IMAGE, FileCategory.GIF -> MdBlock.Image(raw, alt, url)
        else -> MdBlock.FileAttachment(raw, alt.ifEmpty { decoded }, url)
    }
}

/** Matches any `![alt](url)` anywhere in a line. Non-greedy to handle multiple per line. */
internal val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

/** Matches any `[text](url)` anywhere in a line (negative lookbehind for `!`). */
internal val inlineFileLinkRegex = Regex("""(?<!\!)\[([^\]\n]+)]\(([^)\s]+)\)""")

// ─── Hoisted block-parser regexes ─────────────────────────────────────────────

internal val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")

internal val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")

internal val standaloneFileLinkRegex = Regex("""^\[([^\]\n]+)]\(([^)\s]+)\)\s*$""")










/**
 * Split a paragraph's raw text at inline media (`![alt](url)`) and local/sandbox
 * file links (`[title](url)`), extracting them into dedicated preview blocks.
 * Plain web URLs (`https://...`) are preserved inline as clickable text.
 */
internal fun splitParagraphOnMediaAndFiles(text: String): List<MdBlock> {
    data class SplitTarget(val range: IntRange, val block: MdBlock)

    val targets = mutableListOf<SplitTarget>()
    for (m in inlineMediaRegex.findAll(text)) {
        val alt = m.groupValues[1]
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val category = fileCategoryFor(decoded)
        if (category == FileCategory.VIDEO ||
            category == FileCategory.AUDIO ||
            category == FileCategory.IMAGE ||
            category == FileCategory.GIF ||
            isSandboxOrLocalUrl(url)
        ) {
            targets.add(SplitTarget(m.range, mediaBlockFrom(m.value, alt, url)))
        }
    }
    for (m in inlineFileLinkRegex.findAll(text)) {
        val title = m.groupValues[1]
        val url = m.groupValues[2]
        if (isSandboxOrLocalUrl(url)) {
            targets.add(SplitTarget(m.range, MdBlock.FileAttachment(m.value, title, url)))
        }
    }

    if (targets.isEmpty()) return listOf(MdBlock.Paragraph(text))

    targets.sortBy { it.range.first }

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (target in targets) {
        if (target.range.first < cursor) continue
        val preceding = text.substring(cursor, target.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        result.add(target.block)
        cursor = target.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

/**
 * T208-4 part 3: heuristic for "wide" inline math that should be promoted
 * to a display-mode block instead of stuffed into Compose's fixed-size
 * `InlineTextContent` placeholder.
 *
 * Wide constructs (matrices, aligned, multi-row \\, large \frac, long
 * formulas) overflow the inline slot — Compose's Placeholder API can't
 * resize per-formula, so the only options inside an inline span are
 * "clip" or "scale-down to unreadable". Promoting to a display block
 * lets it render at its natural size on its own line (same shape that
 * Markwon and MathJax adopt for `\displaystyle` / `\begin{...}`).
 *
 * Short inline math (`$x$`, `$x_i$`, `$f(x)=5$`) stays inline so prose
 * still flows naturally.
 */
internal fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true        // bmatrix, pmatrix, aligned, cases…
    if (latex.contains("\\\\")) return true            // explicit LaTeX line break / matrix row sep
    if (latex.contains("\\frac")) return true          // fractions render two-line
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

/**
 * T208-4 part 3: split a paragraph at *wide* inline math spans, promoting
 * each one to a `MathDisplay` block. Mirrors the inline-media split: the
 * text before the math becomes a Paragraph, the math becomes its own
 * block, the trailing text becomes a Paragraph. Short math stays inline.
 *
 * Recognises the same delimiters as `parseInline`: `\(...\)` and
 * single-`$...$` (skipping `$$` which is already a block-level form).
 *
 * Walking the string by hand (rather than regex) so escape rules and
 * the "stop at newline" behavior of `findInlineMathClose` stay in sync
 * with the inline parser.
 */
internal fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        // Skip escaped chars inside prose so `\$5` doesn't open a math span.
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

internal data class ListItem(val text: String, val children: List<MdBlock> = emptyList())


internal data class TaskItem(val checked: Boolean, val text: String)

/**
 * [T-android-latex-code-mask] Find the line index that closes a multi-line
 * `$$` display-math block opened just before [from], or null when no
 * *plausible* closer exists.
 *
 * Mirrors the rules ported into MarkdownParser (521b2dc7 / iOS bce7e2ed):
 *  - stop at a blank line — that is a paragraph break, so the `$$` was never
 *    a formula opener;
 *  - stop at a fence marker (``` / ~~~) and never look past it, so a `$$`
 *    living inside a code block can never be mistaken for the closer;
 *  - require at least one LaTeX-ish glyph in the body, so runs of plain prose
 *    are not silently rendered as math.
 *
 * Returning null makes the caller emit the `$$` as literal text, which is what
 * the user typed and what every other markdown renderer does.
 */
internal fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
    var j = from
    val body = StringBuilder()
    while (j < lines.size) {
        val l = lines[j]
        val t = l.trimStart()
        // A fence starts/ends a code region — a `$$` beyond it is not our closer.
        if (t.startsWith("```") || t.startsWith("~~~")) return null
        // Blank line = paragraph break; real display math has no interior blank.
        if (t.isBlank()) return null
        val close = l.indexOf("$$")
        if (close >= 0) {
            body.append(l.substring(0, close))
            val text = body.toString()
            // A closer sitting alone on its own line is the conventional
            // `$$ … $$` block shape and is accepted unconditionally — that
            // covers glyph-free but perfectly valid math like "1 + 2 = 3",
            // which an "always require a LaTeX glyph" rule would wrongly
            // demote to plain text.
            if (t == "$$") return j
            // Degenerate empty body is harmless.
            if (text.isBlank()) return j
            // Otherwise the closer is mid-line (e.g. "… foo $$ bar"), which is
            // the shape a stray delimiter in prose produces. Only accept it
            // when the body actually looks like a formula.
            return if (text.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }) j else null
        }
        body.append(l).append('\n')
        j++
    }
    return null
}
