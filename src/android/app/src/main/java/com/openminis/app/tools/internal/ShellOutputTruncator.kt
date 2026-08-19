package com.openminis.app.tools.internal

object ShellOutputTruncator {
    const val DEFAULT_MAX_LINES = 2_000
    const val DEFAULT_MAX_BYTES = 50 * 1024

    data class Result(
        val output: String,
        val truncated: Boolean,
        val totalLines: Int,
        val totalBytes: Int,
        val outputLines: Int,
        val outputBytes: Int,
    )

    fun truncateTail(
        text: String,
        maxLines: Int = DEFAULT_MAX_LINES,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Result {
        val totalBytes = text.toByteArray(Charsets.UTF_8).size
        val allLines = text.split('\n')
        val totalLines = allLines.size
        if (totalBytes <= maxBytes && totalLines <= maxLines) {
            return Result(text, false, totalLines, totalBytes, totalLines, totalBytes)
        }

        var candidate = allLines.takeLast(maxLines).joinToString("\n")
        if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes) {
            // Walk backward by Unicode code point. Slicing the raw UTF-8 byte
            // array can split a Chinese character / emoji, creating U+FFFD on
            // decode and even exceeding the requested byte budget.
            var start = candidate.length
            var usedBytes = 0
            while (start > 0) {
                val cp = Character.codePointBefore(candidate, start)
                val chars = Character.charCount(cp)
                val cpBytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (usedBytes + cpBytes > maxBytes) break
                start -= chars
                usedBytes += cpBytes
            }
            candidate = candidate.substring(start)
            // When the byte cut landed in the middle of a line and there is a
            // later complete line, discard the partial prefix. For one very
            // long line keep the valid UTF-8 suffix instead of returning empty.
            val nl = candidate.indexOf('\n')
            if (start > 0 && nl >= 0 && nl < candidate.lastIndex) candidate = candidate.substring(nl + 1)
        }
        val outBytes = candidate.toByteArray(Charsets.UTF_8).size
        return Result(candidate, true, totalLines, totalBytes, candidate.split('\n').size, outBytes)
    }
}
