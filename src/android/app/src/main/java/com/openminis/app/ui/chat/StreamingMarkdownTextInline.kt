package com.openminis.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MarkdownParseCaches {
    // [T-android-parse-lru-char-budget] (#759) Per-cache character budget,
    // replacing the previous fixed entry count of 768.
    //
    // The old cap-by-count rule blew up on Larky-class sessions: a 33KB
    // assistant message produces hundreds of cached entries (one per
    // paragraph / table / list item / math snippet), and 768 entries × an
    // average per-entry text length of 100KB+ of inline `AnnotatedString`
    // backing arrays cleared tens of megabytes of resident heap before
    // eviction kicked in. Cap on source characters instead so the cache
    // bytes scale with the source bytes, not with the entry count.
    //
    // Why 2 MB (= 2,000,000 chars) per cache:
    //   - For Larky's worst case (1.9 MB session, single message up to
    //     ~33 KB): the budget holds ~60 distinct 33 KB messages or
    //     ~1500 distinct 1 KB chat blocks — plenty for the tail-window
    //     scroll range (200 messages × ~50 paragraphs avg).
    //   - For typical sessions (1–2 KB messages): ~1000+ entries, ≥ the
    //     previous count-based cap; hit-rate effectively identical to
    //     the 768-entry cap.
    //   - Across the three caches that's 6 MB worst-case (chars only;
    //     AnnotatedString backing arrays are larger but scale with the
    //     same source). On a Pixel-class device with ~96 MB heap budget,
    //     6 MB is acceptable; was previously unbounded by chars on the
    //     33 KB tail of the distribution.
    private const val CHAR_BUDGET_PER_CACHE = 2_000_000

    /**
     * Access-order LinkedHashMap with eviction driven by a running
     * character total rather than entry count. [sizer] returns the number
     * of source characters each (key, value) pair contributes (we count
     * source-side bytes — the canonical input — rather than
     * AnnotatedString output, because output sizes are not easily
     * computable and source length is the dominant correlate anyway).
     *
     * `get` continues to refresh recency via LinkedHashMap's accessOrder;
     * `put` is overridden to maintain [totalChars] and trim trailing
     * eldest entries until the running total fits in [budget], while
     * always keeping at least one entry — see the put loop guard. This
     * preserves the "single oversized message lands in the cache without
     * an infinite eviction loop" invariant required by the spec.
     */
    private class Lru<K, V>(
        private val budget: Int,
        private val sizer: (K, V) -> Int,
    ) : LinkedHashMap<K, V>(64, 0.75f, true) {
        var totalChars: Int = 0
            private set

        override fun put(key: K, value: V): V? {
            val prior = super.put(key, value)
            // The map call above may have replaced an existing entry —
            // adjust the running total by the difference, not the new
            // value alone. Also defends against accidental double-count
            // when the same key is re-put with a different value.
            if (prior != null) totalChars -= sizer(key, prior).coerceAtLeast(0)
            totalChars += sizer(key, value).coerceAtLeast(0)
            // Trim eldest entries until under budget. Always keep at
            // least one entry alive so a single message larger than
            // budget still gets cached (its first parse is the
            // expensive one; we eat the over-budget transient until
            // any other put displaces it).
            while (totalChars > budget && size > 1) {
                val eldest = entries.iterator().next()
                totalChars -= sizer(eldest.key, eldest.value).coerceAtLeast(0)
                entries.remove(eldest)
            }
            return prior
        }

        override fun remove(key: K): V? {
            val v = super.remove(key) ?: return null
            totalChars -= sizer(key, v).coerceAtLeast(0)
            return v
        }

        override fun clear() {
            super.clear()
            totalChars = 0
        }

        // Suppress the count-based eviction path inherited from
        // LinkedHashMap; our own put() does the work and removeEldestEntry
        // would race with our totalChars bookkeeping if both fired.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false
    }

    private val inlineLru = Lru<Pair<String, MdColors>, AnnotatedString>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.first.length },
    )
    private val mathLru = Lru<String, List<String>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )
    private val blocksLru = Lru<String, List<MdBlock>>(
        budget = CHAR_BUDGET_PER_CACHE,
        sizer = { k, _ -> k.length },
    )

    // Double-checked get: the lock is held only for map access, never during a
    // parse — a slow parse on Default must not block a main-thread hit on a
    // DIFFERENT key. A concurrent miss on the same key computes twice and the
    // results are equal immutable values; harmless.
    fun inline(text: String, colors: MdColors): AnnotatedString {
        val key = text to colors
        synchronized(inlineLru) { inlineLru[key] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseInline(text, colors)
        maybeLogSlowParse("inline", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(inlineLru) { inlineLru[key] = computed }
        return computed
    }

    fun mathLatex(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = collectInlineMathLatex(text)
        maybeLogSlowParse("math", text.length, (System.nanoTime() - t0) / 1_000_000)
        synchronized(mathLru) { mathLru[text] = computed }
        return computed
    }

    // ─── [T-android-streaming-incremental-inline] Live-tail incremental parse ──
    //
    // The streaming live fragment is (usually) a single growing paragraph. Its
    // `raw` changes every throttle tick, so `inline()` / `mathLatex()` MISS the
    // cache every tick and re-scan the WHOLE accumulated paragraph char-by-char
    // — the confirmed 81%-of-hang-stacks inline/math regex hotspot + Matcher
    // allocation GC storm (see minis-2026-07-06.log analysis).
    //
    // Incremental fix: find a SAFE closed boundary (a newline where every inline
    // construct — bold/italic/strike/code/math/link — is balanced), parse the
    // frozen prefix ONCE (it only advances in discrete jumps, so it's a cache
    // HIT across ticks), and re-parse only the short unclosed suffix each tick,
    // then splice. Correctness rests on `safeInlineSplitOffset` never splitting
    // inside an open construct, so parse(prefix) ++ parse(suffix) == parse(full).

    /** Below this length the whole-fragment parse is sub-ms; incremental
     *  splitting/splicing overhead isn't worth it. */
    private const val INCREMENTAL_MIN_CHARS = 1_500

    /** Incremental inline parse for the live streaming tail. Returns the exact
     *  same AnnotatedString as `inline(text, colors)` would, but reuses a cached
     *  parse of the closed prefix and only scans the unclosed suffix. Falls back
     *  to the plain cached path for short text or when no safe boundary exists. */
    fun inlineIncremental(text: String, colors: MdColors): AnnotatedString {
        // Exact-match cache hit (e.g. a re-publish of the same content) — free.
        synchronized(inlineLru) { inlineLru[text to colors] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return inline(text, colors)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return inline(text, colors)
        // Prefix goes through the normal cache: identical across ticks until the
        // boundary advances, so this is a HIT on all but the (rare) advance tick.
        val prefixAnn = inline(text.substring(0, split), colors)
        val t0 = System.nanoTime()
        val suffixAnn = parseInline(text.substring(split), colors)
        maybeLogSlowParse("inline-incr", text.length - split, (System.nanoTime() - t0) / 1_000_000)
        return androidx.compose.ui.text.buildAnnotatedString {
            append(prefixAnn)
            append(suffixAnn)
        }
    }

    /** Incremental math-latex collection for the live streaming tail. Same
     *  contract as `mathLatex(text)`; reuses the closed prefix's list. */
    fun mathLatexIncremental(text: String): List<String> {
        synchronized(mathLru) { mathLru[text] }?.let { return it }
        if (text.length < INCREMENTAL_MIN_CHARS) return mathLatex(text)
        val split = safeInlineSplitOffset(text)
        if (split <= 0) return mathLatex(text)
        val prefix = mathLatex(text.substring(0, split))
        val suffix = collectInlineMathLatex(text.substring(split))
        return if (suffix.isEmpty()) prefix else prefix + suffix
    }

    /**
     * [T-android-jank-diag-logging] Threshold-gated slow-parse visibility:
     * silent in normal operation, one INFO line when a SINGLE parse exceeds
     * [SLOW_PARSE_LOG_MS]. thread=main is exactly the signal a future jank
     * report needs — it means a parse escaped every off-main path.
     */
    private const val SLOW_PARSE_LOG_MS = 80L
    private fun maybeLogSlowParse(layer: String, chars: Int, ms: Long, extra: String = "") {
        if (ms < SLOW_PARSE_LOG_MS) return
        val thread = if (android.os.Looper.getMainLooper().isCurrentThread) {
            "main"
        } else {
            Thread.currentThread().name
        }
        com.openminis.app.logging.AppLogger.info(
            "JankDiag",
            "[JankDiag] slow markdown parse layer=$layer chars=$chars ms=$ms thread=$thread$extra",
        )
    }

    /** [T-android-coldload-offmain-parse] Lock-only cache peek — never
     *  computes. Lets the frozen render path keep cache HITs synchronous
     *  while routing big MISSes off-main. */
    fun cachedBlocks(raw: String): List<MdBlock>? =
        synchronized(blocksLru) { blocksLru[raw] }

    /** [T-android-review-p1-fixes] F2(a): freeze-edge handoff — the live
     *  branch deposits its parse result here so the frozen branch HITs
     *  synchronously when the segment freezes (stream end / live→frozen
     *  migration) instead of flashing the plain-text preview while an
     *  off-main re-parse runs. */
    fun putBlocks(raw: String, blocks: List<MdBlock>) {
        synchronized(blocksLru) { blocksLru[raw] = blocks }
    }

    /** Block-level parse for a FROZEN fragment. First parse may run on the
     *  caller's thread (once per distinct fragment text process-wide); scroll
     *  away/return and session re-entry are hits. */
    fun blocks(raw: String): List<MdBlock> {
        synchronized(blocksLru) { blocksLru[raw] }?.let { return it }
        val t0 = System.nanoTime()
        val computed = parseMarkdownBlocksBlocking(raw)
        maybeLogSlowParse(
            "blocks", raw.length, (System.nanoTime() - t0) / 1_000_000,
            extra = " blocks=${computed.size}",
        )
        synchronized(blocksLru) { blocksLru[raw] = computed }
        return computed
    }

    /** Pre-compute everything RenderBlock will ask for, off-main. Walks the
     *  same texts the RenderBlock branches feed to inline()/mathLatex();
     *  anything missed simply computes lazily on first composition (once). */
    fun prewarm(blocks: List<MdBlock>, colors: MdColors) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Paragraph -> { inline(b.raw, colors); mathLatex(b.raw) }
                is MdBlock.Heading -> { inline(b.text, colors); mathLatex(b.text) }
                is MdBlock.UnorderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.OrderedList -> b.items.forEach {
                    inline(it.text, colors); mathLatex(it.text); prewarm(it.children, colors)
                }
                is MdBlock.TaskList -> b.items.forEach { inline(it.text, colors); mathLatex(it.text) }
                is MdBlock.Table -> {
                    b.headers.forEach { inline(it, colors); mathLatex(it) }
                    b.rows.forEach { row -> row.forEach { inline(it, colors); mathLatex(it) } }
                }
                is MdBlock.BlockQuote -> prewarm(b.innerBlocks, colors)
                else -> Unit // code blocks / media / HR / math-display don't inline-parse
            }
        }
    }

    /**
     * [T-android-streaming-incremental-inline] Off-main prewarm for the LIVE
     * tail block. Warms the closed-prefix inline/math cache so the main-thread
     * `inlineIncremental` / `mathLatexIncremental` (which RenderBlock calls for
     * the live block) resolve to a prefix HIT + a tiny suffix scan. Only the
     * LAST block is the live one; earlier blocks are frozen and handled by the
     * normal [prewarm] above. No-op for non-Paragraph tails (they don't take
     * the incremental path in RenderBlock).
     */
    fun prewarmLiveTail(blocks: List<MdBlock>, colors: MdColors) {
        val last = blocks.lastOrNull() as? MdBlock.Paragraph ?: return
        // inlineIncremental/mathLatexIncremental internally split at the safe
        // boundary and reuse inline(prefix)/mathLatex(prefix). Computing them
        // here (off-main) both warms the prefix AND memoizes the exact full-text
        // result under the plain key, so the main-thread call is a clean HIT.
        val ann = inlineIncremental(last.raw, colors)
        synchronized(inlineLru) { inlineLru[last.raw to colors] = ann }
        val math = mathLatexIncremental(last.raw)
        synchronized(mathLru) { mathLru[last.raw] = math }
    }
}

// ─── Inline markdown parser → AnnotatedString ───────────────────────────────

/**
 * [T-android-streaming-incremental-inline] Largest safe offset to split [text]
 * for incremental inline re-parse of a streaming tail. The result `p` satisfies:
 *   - `p` sits immediately AFTER a `\n` (so it lands on an inline-parse "reset"
 *     line boundary — inline code / `$…$` / `\(…\)` all stop at `\n`), and
 *   - `text[0, p)` has every multi-line-capable inline construct CLOSED, i.e.
 *     an even number of `**`, `__`, `~~`, `` ` `` runs and no dangling
 *     `[…](…` link, and no trailing `\` escape.
 *
 * This guarantees `parseInline(prefix) ++ parseInline(suffix) == parseInline(text)`
 * because no inline span crosses the split point. It's a single forward linear
 * scan (cheaper than the parse it saves). Returns 0 when no safe split exists
 * (caller then parses the whole thing) — conservative by construction: any
 * doubt about closure keeps the boundary earlier, never inside an open marker.
 *
 * We keep a [TAIL_MARGIN] of trailing chars unsplit so the still-growing tail
 * (where the model may still be mid-token, mid-`**`, mid-`$`) is always fully
 * re-scanned; only well-settled earlier content is frozen.
 */
internal const val INCR_TAIL_MARGIN = 256

@androidx.annotation.VisibleForTesting
internal fun safeInlineSplitOffset(text: String): Int {
    // Track parity of the multi-line-capable delimiters. Single-line
    // constructs (inline code `…`, `$…$`, `\(…\)`, links) reset at every '\n'
    // (their close-scanners stop at newline), so at a line boundary they are
    // never "open" — we only need to prove the multi-line ones are balanced
    // AND that we're not sitting on a trailing escape.
    var boldStar = false   // ** run open  (also covers *** via two toggles)
    var boldUnder = false  // __ run open
    var strike = false     // ~~ run open
    // Link/image `[label](url` state: parseInline's [text](url) / ![alt](url)
    // use plain indexOf for `]`/`)` and thus CAN span newlines — a newline
    // inside an open link/image is NOT a safe split point.
    var inLabel = false    // seen unmatched `[` (or `![`)
    var inUrl = false      // seen `](`, awaiting `)`
    var lastSafeNewlineEnd = 0 // offset AFTER the last balanced '\n'
    val limit = text.length - INCR_TAIL_MARGIN
    if (limit <= 0) return 0

    var i = 0
    while (i < limit) {
        val c = text[i]
        when {
            // Escape — skip the escaped char so `\*`, `\[` etc. don't toggle.
            c == '\\' && i + 1 < text.length -> { i += 2; continue }
            // Skip the contents of a closed inline-code span. parseInline gives
            // inline code priority over emphasis markers, so `**` inside code
            // is literal text and must not toggle the streaming-state counters.
            // An unclosed backtick is intentionally treated as a literal, just
            // like findInlineCodeClose and parseInline.
            c == '`' -> {
                val close = findInlineCodeClose(text, i + 1)
                i = if (close != -1) close + 1 else i + 1
                continue
            }
            text.startsWith("~~", i) -> { strike = !strike; i += 2; continue }
            text.startsWith("**", i) -> { boldStar = !boldStar; i += 2; continue }
            text.startsWith("__", i) -> { boldUnder = !boldUnder; i += 2; continue }
            // `](` transitions label -> url (only when a label is open).
            inLabel && text.startsWith("](", i) -> { inLabel = false; inUrl = true; i += 2; continue }
            c == '[' -> { inLabel = true; i++ }             // `![` also lands here on the `[`
            c == ']' && inLabel -> { inLabel = false; i++ } // `]` not followed by `(`
            c == ')' && inUrl -> { inUrl = false; i++ }
            c == '\n' -> {
                // Safe only when every newline-spanning construct is closed.
                // `$…$` / `\(…\)` don't need tracking: their close-scanners stop
                // at '\n', so an unclosed one renders literally on both sides of
                // the split. Inline-code contents are skipped above so emphasis
                // markers inside a code span cannot poison this state.
                if (!boldStar && !boldUnder && !strike && !inLabel && !inUrl) {
                    lastSafeNewlineEnd = i + 1
                }
                i++
            }
            else -> i++
        }
    }
    return lastSafeNewlineEnd
}

/**
 * T156: find the closing backtick for an inline-code span starting at
 * [from]. Streaming chunks can deliver an odd backtick ahead of its
 * real partner; if a naive `indexOf` walks past a hard line break to
 * pair it with a backtick on a later line, the intervening prose gets
 * highlighted as code (the user-reported "first half of the sentence turns into code" bug). The
 * CommonMark spec already disallows newlines inside inline code, so
 * stopping at `\n` matches the canonical parser AND defends against
 * mid-stream pairings — the orphan backtick falls back to a literal
 * character until the real closer streams in.
 *
 * Returns -1 when no close is available before the next newline,
 * mirroring `indexOf` so the call site falls into the existing
 * "literal backtick" branch.
 */
internal fun findInlineCodeClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '`') return k
        if (c == '\n') return -1
        k++
    }
    return -1
}

// ─── T155: inline math helpers ──────────────────────────────────────────────

/** Compose annotation tag for inline KaTeX placeholders. */
/**
 * T208-4 part 4: per-latex inline-content tag. Each unique latex span gets
 * its own InlineTextContent slot so the placeholder can be sized to the
 * formula's predicted dimensions instead of one fixed-size slot for all
 * formulas (which forced ContentScale.Fit to shrink every taller-than-slot
 * formula to ~85% scale and clipped wider-than-slot ones).
 */
internal const val KATEX_INLINE_TAG_PREFIX = "katex_inline:"

internal fun katexInlineTagFor(latex: String): String = KATEX_INLINE_TAG_PREFIX + latex

/**
 * T208-4 part 4: estimate the on-screen dp size of an inline KaTeX render
 * BEFORE it has actually rendered, so we can size the InlineTextContent
 * placeholder appropriately. Compose's Placeholder API requires a size at
 * construction time and the inline Text layout reserves exactly that
 * amount of space — so we have to predict.
 *
 * Heuristics calibrated against the T208-DBG logs collected from a real
 * device: KaTeX produces ~`fontSize * 1.6` dp wide per visible character
 * for ordinary glyphs at 16 sp / density 2.625, and ~`fontSize * 1.65` dp
 * tall for one-line formulas (descenders + sub/superscript whitespace).
 * Stacked constructs (\frac, \begin, \sqrt with fraction inside) need
 * 2-3× the height. These numbers are intentionally generous — Compose
 * will draw the bitmap at its natural dp size centered inside the slot,
 * so an over-sized slot just produces extra whitespace, but an
 * under-sized slot triggers shrink/clip.
 */
internal fun estimateInlineMathSize(latex: String, fontSize: TextUnit): Pair<TextUnit, TextUnit> {
    val visibleCharCount = run {
        var c = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\' && i + 1 < latex.length) {
                // Skip a TeX command name; count the command as ~1.5 visible chars.
                i++
                while (i < latex.length && latex[i].isLetter()) i++
                c += 1
                continue
            }
            if (ch == '{' || ch == '}' || ch == ' ') { i++; continue }
            c++
            i++
        }
        c.coerceAtLeast(1)
    }

    // Width: ~0.95 em per visible char for typical math glyphs. KaTeX's
    // measured widths run ~0.95 em/char for ordinary symbols and >1 em/char
    // when `\text{...}` switches to a proportional sans/serif body face;
    // tuning down to 0.65 underestimated formulas like `W_c^{\text{non-private}}`
    // (244 dp natural wide vs 166 dp slot → Image got clipped/shrunk).
    // Cap at a generous upper bound — wide-math splitter has already
    // promoted truly wide formulas (length>30 OR `\begin{...}` etc.) to
    // display blocks, so anything reaching this estimator is short-ish
    // inline math; the cap is just defensive against pathological input.
    val charWidthEm = 0.95f
    val widthEm = (visibleCharCount * charWidthEm).coerceIn(1.5f, 22f)

    // Height: ~1.7 em base (matches measured ~26 dp for 16 sp).
    // Stacked constructs need vertical room for numerator+bar+denominator.
    var heightEm = 1.7f
    if (latex.contains("\\frac") || latex.contains("\\binom") ||
        latex.contains("\\sum") || latex.contains("\\int") ||
        latex.contains("\\prod") || latex.contains("\\sqrt[")
    ) heightEm = 3.2f
    if (latex.contains("\\begin{") || latex.contains("\\\\")) heightEm = 4.5f

    return (fontSize * widthEm) to (fontSize * heightEm)
}

/**
 * T208-4 part 4: scan a markdown line for inline-math spans (same delimiter
 * logic as parseInline) so we can pre-register a sized placeholder for
 * each unique latex BEFORE the AnnotatedString is laid out.
 */
internal fun collectInlineMathLatex(text: String): List<String> {
    if (!text.contains('$') && !text.contains("\\(")) return emptyList()
    val root = com.openminis.app.ui.chat.md.INode(com.openminis.app.ui.chat.md.IType.TEXT)
    newChatInlineParser().parse(text, root)
    val out = mutableListOf<String>()
    fun walk(n: com.openminis.app.ui.chat.md.INode) {
        var c = n.first
        while (c != null) {
            if (c.type == com.openminis.app.ui.chat.md.IType.MATH) out += c.literal else walk(c)
            c = c.next
        }
    }
    walk(root)
    return out
}

/**
 * Find the closing `$` for an inline-math span starting at [from].
 * Mirrors [findInlineCodeClose]: stop at a newline so streaming chunks
 * never pair a stray `$` with the next dollar that arrives later, and
 * skip `\$` (escaped) and `$$` (which would be display math).
 */
internal fun findInlineMathClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < text.length) { k += 2; continue }
        if (c == '$') {
            // `$$` here is the start of display math, not a single-dollar close.
            if (k + 1 < text.length && text[k + 1] == '$') return -1
            return k
        }
        k++
    }
    return -1
}

/**
 * Crude heuristic to skip plain currency like `$5`, `$1,000` and avoid
 * turning prose dollar signs into KaTeX renders. Real LaTeX math nearly
 * always carries a backslash command, a brace, a math operator, or a
 * superscript/subscript marker. iOS uses the same idea
 * (MinisMarkdownParser.looksLikeMath).
 */
internal fun looksLikeMath(latex: String): Boolean {
    if (latex.isBlank()) return false
    if (latex.contains('\\')) return true
    if (latex.contains('{') || latex.contains('}')) return true
    if (latex.contains('^') || latex.contains('_')) return true
    val mathChars = "=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"
    if (latex.any { it in mathChars }) return true
    // [T-latex-inline] Bare short spans like `$x$`, `$pi$`, `$abc$` carry no
    // LaTeX glyph but ARE math. Mirror iOS MinisMarkdownParser.looksLikeMath,
    // which accepts `count > 2`, and additionally accept a single alphanumeric
    // token (`$x$`, `$n$`) — the strict "needs a math char" rule was the drift
    // that made single-variable inline math leak as literal `$…$`. Currency
    // (`$5`, `$1,000`) is filtered by the leading-digit guard, and `$$`/space
    // openers never reach here (gated by the caller).
    if (latex[0].isDigit()) return false            // currency: `$5`, `$10.99`
    if (latex.first().isWhitespace() || latex.last().isWhitespace()) return false
    if (latex.length <= 30 && latex.all { it.isLetterOrDigit() }) return true
    return latex.length > 2
}

/**
 * [T-latex-inline] True when a candidate inline-math span is really a markdown
 * table-cell artifact (a `$` that paired across `|` column separators) rather
 * than a formula. Mirrors iOS MinisMarkdownParser.isTablePipeArtifact so a row
 * like `| 月付 | $20|$ **3** |` doesn't capture `20|` as fake math (which would
 * eat the bold `**3**`). Two signals a real formula avoids: an unescaped pipe
 * with whitespace on a side (the ` | ` column separator), or an ODD number of
 * unescaped pipes (abs-value / norm bars always come in balanced pairs).
 * Escaped `\|` (LaTeX norm) is never counted.
 */
internal fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue      // escaped norm bar
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}
