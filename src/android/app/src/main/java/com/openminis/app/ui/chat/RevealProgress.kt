package com.openminis.app.ui.chat

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max

/**
 * [T-android-reveal-monotonic] Monotonic reveal progress for streamed text.
 *
 * The streaming tail is re-parsed on every tick, and a tick can legitimately
 * make the *rendered* text SHORTER than the previous tick: a closing inline
 * marker rewrites `**bold` into bold text, a link target lands, a table header
 * resolves. The previous reveal implementation treated any non-append update as
 * "brand new block" and dropped all in-flight fade ranges — already-faded
 * characters then flashed back to full opacity, then faded again as the same
 * text was re-revealed. The invariant that fixes it is one line: progress may
 * only move forward, and a shrink is clamped, never rewound.
 *
 * Ported from Eta @ c15de97 `ui/components/SmoothTextReveal.kt`
 * (`graphemeBoundaries`, `updateGraphemeBoundaries`, `AppendOnlyGraphemeIndex`,
 * `commonUtf16PrefixLength`, `smoothRevealSpeed`, `advanceSmoothReveal`) with
 * the drawing/layout half of that file deliberately left out — this repo keeps
 * its own word-fade renderer (see [FadeController]).
 *
 * Everything here is Android-free and unit-tested: boundary maths, the
 * incremental index, the speed function, and the clamp rules.
 */

/** Reveal cadence floor: never slower than this, whatever the backlog. */
internal const val BASE_REVEAL_GRAPHEMES_PER_SECOND = 36f

/** A backlog this size is always caught up within this window. */
internal const val TARGET_CATCH_UP_SECONDS = 0.20f

/** Hard per-frame budget so a paused/lagging frame cannot jump the reveal. */
internal const val MAX_REVEAL_FRAME_SECONDS = 0.05f

/**
 * Grapheme cluster boundaries of [text], starting at 0 and always ending at
 * `text.length`. Uses [BreakIterator]'s character instance so combining marks,
 * ZWJ emoji, regional-indicator flags and CRLF stay single boundaries.
 */
internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * Incremental boundary update for append-only text.
 *
 * New content can EXTEND the previous last grapheme (combining accent, ZWJ
 * emoji, flag, CRLF), so the last old boundary is not trustworthy: keep
 * everything before the second-to-last boundary and rescan from there. Any
 * non-append input (or an inconsistent previous index) falls back to a full
 * rebuild.
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

/**
 * Length of the shared UTF-16 prefix of [first] and [second], never splitting a
 * surrogate pair (a lone high surrogate is not a usable offset).
 */
internal fun commonUtf16PrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index += 1
    if (
        index in 1 until limit &&
        first[index - 1].isHighSurrogate() &&
        first[index].isLowSurrogate()
    ) {
        index -= 1
    }
    return index
}

/** Append-only grapheme index: `update` in, boundary-aligned offsets out. */
internal class AppendOnlyGraphemeIndex {
    private var indexedText = ""
    private var boundaries = intArrayOf(0)

    /** Total graphemes currently indexed. */
    val graphemeCount: Int get() = boundaries.lastIndex

    fun update(text: String) {
        if (text == indexedText) return
        boundaries = updateGraphemeBoundaries(
            previousText = indexedText,
            previousBoundaries = boundaries,
            text = text,
        )
        indexedText = text
    }

    /**
     * Largest boundary offset that is `<= offset` — the grapheme-safe cut a
     * shrink clamp may reuse without splitting a cluster (a combining accent,
     * a ZWJ emoji sequence or a CRLF pair must survive whole).
     */
    fun alignedOffsetFloor(offset: Int): Int {
        val clamped = offset.coerceIn(0, indexedText.length)
        if (clamped == 0) return 0
        val found = boundaries.binarySearch(clamped)
        return if (found >= 0) clamped else boundaries[-found - 2].coerceAtLeast(0)
    }

    /** Number of whole graphemes inside [offset] — the progress a cut leaves intact. */
    fun graphemeCountUpTo(offset: Int): Int {
        val clamped = offset.coerceIn(0, indexedText.length)
        if (clamped == 0 || boundaries.size <= 1) return 0
        val found = boundaries.binarySearch(clamped)
        val index = if (found >= 0) found else -found - 2
        return index.coerceIn(0, graphemeCount)
    }
}

/** Adaptive cadence: floor at [BASE_REVEAL_GRAPHEMES_PER_SECOND], catch up a backlog in [TARGET_CATCH_UP_SECONDS]. */
internal fun smoothRevealSpeed(totalBacklog: Float): Float =
    max(BASE_REVEAL_GRAPHEMES_PER_SECOND, totalBacklog / TARGET_CATCH_UP_SECONDS)

/**
 * Advance `current` toward `target`, never past it and never backwards.
 * `elapsedSeconds` must already be clamped to [MAX_REVEAL_FRAME_SECONDS] by the
 * caller so a stalled frame cannot dump a whole paragraph in one tick.
 */
internal fun advanceSmoothReveal(
    current: Float,
    target: Float,
    elapsedSeconds: Float,
    totalBacklog: Float,
): Float {
    if (current >= target) return target
    val advance = (smoothRevealSpeed(totalBacklog) * elapsedSeconds).coerceAtLeast(0f)
    return (current + advance).coerceAtMost(target)
}

/** What a [RevealProgress.ingest] call did to the tracked text. */
internal enum class RevealIngestKind {
    /** Same text as before — nothing to do. */
    UNCHANGED,
    /** Previous text is a prefix of the new text (the normal streaming case). */
    APPENDED,
    /** New text is shorter than the previous one (markers closed, links landed). */
    SHRANK,
    /** New text diverged from the previous one — baseline rebuilt. */
    REBUILT,
}

/** Outcome of one [RevealProgress.ingest]: what changed, and what may survive. */
internal data class RevealUpdate(
    val kind: RevealIngestKind,
    /**
     * Grapheme-aligned offset up to which previously revealed content is still
     * valid. Ranges at or beyond this offset belong to text that no longer
     * exists (or was rewritten) and must be dropped; everything before it keeps
     * its progress.
     */
    val survivingPrefixEnd: Int,
)

/**
 * Monotonic reveal progress over a text that is re-published on every tick.
 *
 * The owner (see [FadeController]) feeds it the rendered text; the class owns
 * the grapheme index and the "how much has been revealed" counter. The counter
 * is clamped on shrink and rebuilt on divergence — it never decreases because of
 * a text update, which is the invariant that stops already-revealed characters
 * from disappearing and fading back in.
 */
internal class RevealProgress {
    private val index = AppendOnlyGraphemeIndex()
    private var text = ""

    /** Graphemes of the current text (the target). */
    var totalGraphemes: Int = 0
        private set

    /** Graphemes already revealed — monotonic across updates of one stream. */
    var revealedGraphemes: Int = 0
        private set

    val backlogGraphemes: Int get() = (totalGraphemes - revealedGraphemes).coerceAtLeast(0)

    val renderedText: String get() = text

    fun ingest(newText: String): RevealUpdate {
        if (newText == text) {
            return RevealUpdate(RevealIngestKind.UNCHANGED, newText.length)
        }
        val previousText = text
        val kind = when {
            text.isEmpty() && newText.isNotEmpty() -> RevealIngestKind.APPENDED
            newText.startsWith(text) -> RevealIngestKind.APPENDED
            newText.length < text.length && text.startsWith(newText) -> RevealIngestKind.SHRANK
            else -> RevealIngestKind.REBUILT
        }
        index.update(newText)
        text = newText
        totalGraphemes = index.graphemeCount
        val survivingPrefix = when (kind) {
            // Appended: every previously laid-out offset still exists.
            RevealIngestKind.APPENDED -> newText.length
            // Shrink / rebuild: only the shared prefix survives, cut on a
            // grapheme boundary so a partially-rewritten cluster is not kept.
            else -> index.alignedOffsetFloor(commonUtf16PrefixLength(previousText, newText))
        }
        // Clamp only, and only as far as the text actually moved. A shrink must
        // not rewind progress past what survived, and a rebuild (a different
        // message reusing this controller) must not claim the replacement text
        // was already revealed either.
        revealedGraphemes = if (kind == RevealIngestKind.APPENDED) {
            revealedGraphemes.coerceAtMost(totalGraphemes)
        } else {
            revealedGraphemes.coerceAtMost(index.graphemeCountUpTo(survivingPrefix))
        }
        return RevealUpdate(kind, survivingPrefix)
    }

    /** Reveal the whole target immediately (page not visible, node unmounted). */
    fun snapToTarget() {
        revealedGraphemes = totalGraphemes
    }

    /**
     * Advance the reveal clock by [elapsedSeconds] (clamped by the caller).
     * Returns the number of graphemes revealed by this step.
     */
    fun advance(elapsedSeconds: Float): Int {
        val before = revealedGraphemes
        // A shrink may have clamped the target below the old progress; retreat
        // to it without revealing anything (never below the target, never a
        // negative step).
        if (before >= totalGraphemes) {
            revealedGraphemes = totalGraphemes
            return 0
        }
        val next = advanceSmoothReveal(
            current = revealedGraphemes.toFloat(),
            target = totalGraphemes.toFloat(),
            elapsedSeconds = elapsedSeconds.coerceIn(0f, MAX_REVEAL_FRAME_SECONDS),
            totalBacklog = backlogGraphemes.toFloat(),
        )
        revealedGraphemes = next.toInt().coerceIn(before, totalGraphemes)
        return revealedGraphemes - before
    }
}
