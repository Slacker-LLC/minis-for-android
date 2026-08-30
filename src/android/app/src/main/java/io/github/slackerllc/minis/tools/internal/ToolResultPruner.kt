package io.github.slackerllc.minis.tools.internal

/**
 * Prunes oversized tool results for the compaction path.
 *
 * Port of the DeepSeek Harness dsh-compaction-tool-result-pruner contract:
 * results longer than [THRESHOLD_CHARS] are replaced by a head + omission
 * marker + tail preview built with [TextRetainer], so the retained text stays
 * well under [THRESHOLD_CHARS] and pruning is idempotent (pruning an already
 * pruned result returns null).
 *
 * This object only provides the capability — it is not yet wired into the
 * compaction call sites — and is intentionally unit-test friendly.
 */
object ToolResultPruner {
    /** Results at or below this many characters are returned untouched. */
    const val THRESHOLD_CHARS = 8192

    /** Leading characters kept in a pruned result. */
    const val HEAD_CHARS = 4096

    /** Trailing characters kept in a pruned result. */
    const val TAIL_CHARS = 1024

    /**
     * Prunes [text] when it exceeds [THRESHOLD_CHARS] characters.
     *
     * The returned text is `head (HEAD_CHARS) + marker + tail (TAIL_CHARS)`
     * produced by a [TextRetainer], which guarantees surrogate-pair safety and
     * an output length below [THRESHOLD_CHARS] (so re-pruning is a no-op).
     *
     * @return The pruned preview text, or null when [text] is within the
     *   threshold and needs no pruning.
     */
    fun prune(text: String): String? {
        if (text.length <= THRESHOLD_CHARS) return null
        val retainer = TextRetainer(maxChars = THRESHOLD_CHARS, headChars = HEAD_CHARS, tailChars = TAIL_CHARS)
        retainer.push(text)
        return retainer.finish().text
    }
}
