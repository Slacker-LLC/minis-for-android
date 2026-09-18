package com.openminis.app.tools.internal

/**
 * Output retention utilities ported from the DeepSeek Harness
 * dsh-output-retention contract.
 *
 * The retainers in this file bound how much of a (possibly unbounded) output
 * stream is kept in memory while still reporting exactly how much was
 * omitted. [TextRetainer] keeps a head/tail slice of character data,
 * [ItemRetainer] keeps a bounded list of items, and [formatRetentionNotice]
 * turns an [Omitted] summary into a human-readable recovery hint.
 *
 * This file is pure Kotlin/JVM (no Android dependencies) so the same code is
 * usable from local unit tests and from the compaction path alike.
 */

/**
 * Describes how much content was omitted from a retained output.
 *
 * @property kind One of "none" (nothing omitted), "exact" ([count] is the
 *   exact number of omitted units) or "unknown" (something was omitted but the
 *   count is not tracked).
 * @property count Number of omitted units (chars for [TextRetainer], items for
 *   [ItemRetainer]); null when nothing was omitted or the count is unknown.
 */
data class Omitted(
    val kind: String,
    val count: Long?,
) {
    init {
        require(kind == "none" || kind == "exact" || kind == "unknown") {
            "Omitted.kind must be one of \"none\", \"exact\", \"unknown\" but was \"$kind\""
        }
    }
}

/**
 * Result of a single [TextRetainer.push] / [ItemRetainer.push] call.
 *
 * @property kept True when at least part of the pushed content survives into
 *   the final retained output.
 * @property truncated True when part (or all) of the pushed content had to be
 *   dropped because capacity was exhausted.
 * @property omittedChars Number of characters of this push that were omitted
 *   (always 0 for [ItemRetainer], which reports counts only via [Omitted]).
 */
data class PushResult(
    val kept: Boolean,
    val truncated: Boolean,
    val omittedChars: Long = 0,
)

/**
 * Final retained text plus the omission summary produced by
 * [TextRetainer.finish].
 *
 * @property text The retained head + omission marker + tail text, or the full
 *   text when nothing was omitted.
 * @property omitted Summary of what had to be dropped, if anything.
 */
data class RetainedText(
    val text: String,
    val omitted: Omitted,
)

/**
 * Final retained item list plus the omission summary produced by
 * [ItemRetainer.finish].
 *
 * @property items Retained items in push order.
 * @property omitted Summary of how many pushed items were dropped (exact count
 *   when any were dropped, kind "none" otherwise).
 */
data class RetainedItems<T>(
    val items: List<T>,
    val omitted: Omitted,
)

/**
 * Keeps the first [headChars] and the last [tailChars] characters of a
 * character stream while counting exactly how many middle characters were
 * omitted. Text is fed in with [push] (usually one chunk per tool result) and
 * the final bounded text is produced by [finish].
 *
 * Surrogate-pair safety: the head/tail cuts are adjusted so a UTF-16 surrogate
 * pair is never split across a retention boundary. A pair straddling the
 * head/tail junction (when nothing was omitted between them) is kept whole in
 * the head (bounded one-char overshoot); a half whose partner was omitted is
 * dropped and counted as omitted.
 *
 * @property maxChars Upper bound on the character budget the retainer is
 *   configured within.
 * @property headChars Number of leading characters to keep.
 * @property tailChars Number of trailing characters to keep.
 */
class TextRetainer(
    val maxChars: Int,
    val headChars: Int = maxChars / 2,
    val tailChars: Int = maxChars / 4,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
        require(headChars >= 0 && headChars <= maxChars) {
            "headChars must be in 0..maxChars, was $headChars"
        }
        require(tailChars >= 0 && tailChars <= maxChars) {
            "tailChars must be in 0..maxChars, was $tailChars"
        }
        require(headChars + tailChars <= maxChars) {
            "headChars + tailChars ($headChars + $tailChars) must not exceed maxChars ($maxChars)"
        }
    }

    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var totalChars = 0L

    /**
     * Pushes one chunk of text into the retainer.
     *
     * The per-push omitted count is exact: a character of this push survives
     * iff it falls inside the head region `[0, headChars)` or inside the tail
     * region `[max(headChars, newTotal - tailChars), newTotal)` of the whole
     * stream after this push.
     *
     * @return [PushResult] describing whether (part of) the chunk was kept and
     *   how many characters of it were omitted.
     */
    fun push(text: String): PushResult {
        val n = text.length
        if (n == 0) return PushResult(kept = false, truncated = false, omittedChars = 0)

        val start = totalChars
        val newTotal = start + n

        // Exact per-push accounting (see KDoc above).
        val headRetained = maxOf(0L, minOf(newTotal, headChars.toLong()) - minOf(start, headChars.toLong()))
        val tailStart = maxOf(headChars.toLong(), newTotal - tailChars)
        val tailRetained = maxOf(0L, newTotal - maxOf(start, tailStart))
        val retained = headRetained + tailRetained
        val omittedChars = (n - retained).coerceAtLeast(0L)

        totalChars = newTotal

        // Fill the head buffer up to headChars with the leading characters.
        var idx = 0
        val headRoom = headChars - head.length
        if (headRoom > 0 && idx < n) {
            val take = minOf(headRoom, n)
            head.append(text, 0, take)
            idx = take
        }
        // Everything beyond the head flows into the tail buffer, which is
        // trimmed to its last tailChars characters.
        if (idx < n) {
            tail.append(text, idx, n)
            if (tail.length > tailChars) {
                tail.delete(0, tail.length - tailChars)
            }
        }

        return PushResult(kept = retained > 0L, truncated = omittedChars > 0L, omittedChars = omittedChars)
    }

    /**
     * Produces the final retained text: `head + "\n\n…[omitted N chars]…\n\n" + tail`
     * when characters were omitted, or the full concatenated text otherwise.
     * The head/tail cuts are adjusted so no UTF-16 surrogate pair is split.
     *
     * @return [RetainedText] with the bounded text and the exact [Omitted]
     *   summary (kind "none" with a null count when nothing was omitted).
     */
    fun finish(): RetainedText {
        var headText = head.toString()
        var tailText = tail.toString()

        // Never split a surrogate pair at a retention boundary.
        if (headText.isNotEmpty() && isHighSurrogate(headText.last())) {
            val middleEmpty = totalChars - headText.length - tailText.length == 0L
            if (middleEmpty && tailText.isNotEmpty() && isLowSurrogate(tailText.first())) {
                // The pair straddles the head/tail junction with nothing omitted
                // between: absorb the low surrogate into the head (bounded
                // one-char overshoot) so the pair stays intact.
                headText = headText + tailText.first()
                tailText = tailText.substring(1)
            } else {
                // The partner half was omitted (or the input is malformed):
                // drop the dangling high surrogate from the head.
                headText = headText.dropLast(1)
            }
        }
        if (tailText.isNotEmpty() && isLowSurrogate(tailText.first())) {
            // Tail begins with a low surrogate whose high half is not retained.
            tailText = tailText.substring(1)
        }

        val omittedChars = totalChars - headText.length - tailText.length
        val omitted = if (omittedChars > 0) Omitted("exact", omittedChars) else Omitted("none", null)
        val text = if (omittedChars > 0) {
            val marker = if (omitted.count != null) "…[omitted ${omitted.count} chars]…" else "…[omitted chars]…"
            headText + "\n\n" + marker + "\n\n" + tailText
        } else {
            headText + tailText
        }
        return RetainedText(text, omitted)
    }

    private companion object {
        fun isHighSurrogate(c: Char): Boolean = c in '\uD800'..'\uDBFF'
        fun isLowSurrogate(c: Char): Boolean = c in '\uDC00'..'\uDFFF'
    }
}

/**
 * Keeps at most [maxItems] pushed items (the first ones) and counts exactly
 * how many later items were dropped.
 *
 * @param maxItems Maximum number of items retained; pushes beyond it are
 *   dropped and counted. Zero keeps nothing.
 */
class ItemRetainer<T>(private val maxItems: Int) {
    init {
        require(maxItems >= 0) { "maxItems must be >= 0, was $maxItems" }
    }

    private val retained = ArrayList<T>(maxItems)
    private var omittedCount = 0L

    /**
     * Pushes one item. While capacity remains the item is retained; afterwards
     * it is dropped and the omitted counter is incremented.
     *
     * @return [PushResult] with [PushResult.omittedChars] always 0 — the exact
     *   dropped count is reported by [finish] via [Omitted].
     */
    fun push(item: T): PushResult {
        return if (retained.size < maxItems) {
            retained.add(item)
            PushResult(kept = true, truncated = false, omittedChars = 0)
        } else {
            omittedCount += 1
            PushResult(kept = false, truncated = true, omittedChars = 0)
        }
    }

    /**
     * Produces the final retained items plus the exact [Omitted] summary
     * (kind "exact" with the dropped count, or "none" with a null count).
     *
     * @return [RetainedItems] with a snapshot of the retained items in push
     *   order.
     */
    fun finish(): RetainedItems<T> {
        val omitted = if (omittedCount > 0) Omitted("exact", omittedCount) else Omitted("none", null)
        return RetainedItems(retained.toList(), omitted)
    }
}

/**
 * Formats a human-readable retention notice for an [Omitted] summary.
 *
 * Produces "…omitted N chars…" (or "…omitted N items…" when [unit] is
 * "items"), appending [recovery] (e.g. "Use file_read with offset/limit to
 * search within it.") when it is not blank. Returns an empty string when
 * nothing was omitted, and omits the count when it is unknown.
 *
 * @param omitted The omission summary to describe.
 * @param recovery Recovery guidance appended after the marker.
 * @param unit Unit label used in the notice: "chars" (default) or "items".
 * @return The notice text, or "" when [omitted] reports nothing was dropped.
 */
fun formatRetentionNotice(omitted: Omitted, recovery: String, unit: String = "chars"): String {
    if (omitted.kind == "none") return ""
    val notice = if (omitted.kind == "exact" && omitted.count != null) {
        "…omitted ${omitted.count} $unit…"
    } else {
        "…omitted $unit…"
    }
    return if (recovery.isBlank()) notice else "$notice $recovery"
}
