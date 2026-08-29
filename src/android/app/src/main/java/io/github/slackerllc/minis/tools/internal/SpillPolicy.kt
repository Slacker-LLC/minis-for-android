package io.github.slackerllc.minis.tools.internal

import java.io.File

/**
 * Spills oversized tool results to disk instead of inlining them.
 *
 * Port of the DeepSeek Harness dsh-spill-policy contract: plain text whose
 * UTF-8 size exceeds [maxInlineBytes] is written to
 * `spillDir/baseName-<timestamp>.txt` and the inline payload becomes a
 * head/tail preview (via [TextRetainer]) plus a pointer back to the full file,
 * so callers can re-read it with the file_read tool using offset/limit.
 *
 * Small results are returned verbatim with no file side effect. File write
 * failures ([IOException], or a spill directory that cannot be created)
 * propagate to the caller.
 */
object SpillPolicy {
    /** Default inline byte budget (50 KiB) used when no override is given. */
    const val DEFAULT_MAX_INLINE_BYTES = 50 * 1024

    private const val PREVIEW_HEAD_CHARS = 2_000
    private const val PREVIEW_TAIL_CHARS = 1_000

    /**
     * Result of a spill decision.
     *
     * @property inline The payload to show inline: the original text when not
     *   spilled, otherwise the preview + retrieval notice.
     * @property fullPath Absolute path of the spill file, or null when the
     *   text was small enough to stay inline.
     * @property spilled True when the text was written to a spill file.
     */
    data class SpillResult(
        val inline: String,
        val fullPath: String?,
        val spilled: Boolean,
    )

    /**
     * Writes [text] to disk when its UTF-8 size exceeds [maxInlineBytes].
     *
     * @param text The plain text to consider for spilling.
     * @param maxInlineBytes Maximum UTF-8 byte size allowed inline.
     * @param spillDir Directory the spill file is written into (created when
     *   missing).
     * @param baseName File name stem; the spill file is
     *   `baseName-<timestamp>.txt` (a numeric suffix is appended if the
     *   timestamp collides).
     * @return [SpillResult] — unspilled (inline = [text], fullPath = null) when
     *   within budget, spilled otherwise.
     */
    fun spillIfOversized(
        text: String,
        maxInlineBytes: Int = DEFAULT_MAX_INLINE_BYTES,
        spillDir: File,
        baseName: String,
    ): SpillResult {
        val totalBytes = text.toByteArray(Charsets.UTF_8).size
        if (totalBytes <= maxInlineBytes) {
            return SpillResult(inline = text, fullPath = null, spilled = false)
        }

        if (!spillDir.exists() && !spillDir.mkdirs()) {
            throw IllegalStateException("Unable to create spill directory: ${spillDir.absolutePath}")
        }

        val stamp = System.currentTimeMillis()
        var attempt = 0
        var target: File
        do {
            val suffix = if (attempt == 0) "$stamp" else "$stamp-$attempt"
            target = File(spillDir, "$baseName-$suffix.txt")
            attempt++
        } while (target.exists())
        target.writeText(text, Charsets.UTF_8)

        val preview = TextRetainer(
            maxChars = PREVIEW_HEAD_CHARS + PREVIEW_TAIL_CHARS,
            headChars = PREVIEW_HEAD_CHARS,
            tailChars = PREVIEW_TAIL_CHARS,
        ).also { it.push(text) }.finish().text
        val inline = "$preview\n\n(Omitted $totalBytes bytes. Full formatted result stored at: ${target.absolutePath}. Use file_read with offset/limit to search within it.)"

        return SpillResult(inline = inline, fullPath = target.absolutePath, spilled = true)
    }
}
