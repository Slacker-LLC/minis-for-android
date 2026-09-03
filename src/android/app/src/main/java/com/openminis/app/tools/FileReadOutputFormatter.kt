package com.openminis.app.tools

/**
 * Formats the line-range metadata and content returned by [FileReadTool].
 * Keeping this pure makes the truncation contract testable without a Context
 * or a live minisd instance.
 */
internal object FileReadOutputFormatter {
    fun format(
        path: String,
        size: Long,
        totalLines: Int,
        selectedLines: List<String>,
        showStart: Int,
        direction: String,
        maxLength: Int,
    ): String {
        val showEnd = showStart + selectedLines.size - 1
        var content = selectedLines.joinToString("\n")

        // [T-fileread-truncation-header] The header used to report the line
        // range chosen BEFORE truncation, and said nothing about having
        // truncated at all — only the body gained a trailing
        // "... (truncated)". So a cut-off read still announced
        // "showing 1-1324 of 1324", which the agent took as the whole file
        // and never paged on.
        //
        // Recompute the range that actually survived and hand back the
        // offset to resume from. Confined to the truncating branch; a read
        // that fits is byte-identical to before.
        var effectiveStart = showStart
        var effectiveEnd = showEnd
        var nextOffset: Int? = null
        var wasTruncated = false
        if (content.length > maxLength) {
            wasTruncated = true
            if (direction == "tail") {
                // tail asks for the END of the file; take() returned the
                // start of the tail window instead — the opposite.
                content = content.takeLast(maxLength)
                // Drop a leading partial line so the first line is whole.
                val firstNewline = content.indexOf('\n')
                if (firstNewline in 0 until content.length - 1) {
                    content = content.substring(firstNewline + 1)
                }
                effectiveStart = effectiveEnd - content.count { it == '\n' }
                // No next_offset for tail: paging forward from the end of
                // the file is meaningless.
            } else {
                content = content.take(maxLength)
                // Back off to the last complete line, so the next page does
                // not re-read or split a line.
                val lastNewline = content.lastIndexOf('\n')
                if (lastNewline > 0) content = content.substring(0, lastNewline)
                effectiveEnd = showStart + content.count { it == '\n' }
                if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
            }
        }

        var header = "[$path | $size bytes | $totalLines lines | " +
            "showing $effectiveStart-$effectiveEnd of $totalLines"
        if (wasTruncated) {
            header += " | truncated at $maxLength chars"
            // Named to match the tool's own `offset` parameter so the model
            // can copy it straight into the next call.
            header += if (nextOffset != null) ", next_offset=$nextOffset"
                      else ", retry with a smaller lines value"
        }
        header += "]"
        return "$header\n$content"
    }
}
