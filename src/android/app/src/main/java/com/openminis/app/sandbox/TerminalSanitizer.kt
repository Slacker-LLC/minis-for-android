package com.openminis.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output in two passes:
     * 1. CR folding — simulate carriage return overwriting
     * 2. Strip remaining ANSI/VT escape sequences
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: CR folding
        val crFolded = foldCarriageReturns(raw)

        // Pass 2: Strip ANSI sequences
        val stripped = ANSI_REGEX.replace(crFolded, "")

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = stripped.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2).
        // Trim only line-feeds, NOT spaces: an erase-to-end (ESC[K) or a
        // carriage-return overwrite can legitimately leave trailing spaces
        // that the column model produced (e.g. "complete   "), and trimming
        // them would lie about the terminal state.
        return noNullLines.replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior with a per-line COLUMN buffer, like a real
     * terminal:
     *  - \r resets the column to 0; later text overwrites earlier text
     *    character by character (so "AAAA\rBB" → "BBAA").
     *  - ESC[K (erase to end of line) fills the rest of the written line
     *    with spaces, matching what the terminal actually renders.
     *  - Other ANSI sequences are skipped here (their content never reaches
     *    the buffer) and stripped again in the regex pass for safety.
     * The buffer is capped at [MAX_COLS] columns so hostile output cannot
     * blow up memory.
     */
    private const val MAX_COLS = 4096

    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')
            result.append(foldLine(line))
        }
        return result.toString()
    }

    private fun foldLine(line: String): String {
        // Fast path: no CR and no ESC means nothing to fold.
        if ('\r' !in line && '\u001B' !in line) return line

        val cols = CharArray(MAX_COLS) { ' ' }
        var col = 0
        var maxWritten = 0
        var i = 0
        while (i < line.length) {
            when (val ch = line[i]) {
                '\r' -> col = 0
                '\u001B' -> {
                    val m = ANSI_REGEX.find(line, i)
                    if (m == null) {
                        i++
                        continue
                    }
                    if (m.value == "\u001B[K") {
                        // Erase to end of line: fill the written region with
                        // spaces so a shorter redraw fully covers the old text.
                        val end = maxWritten.coerceAtMost(MAX_COLS)
                        for (c in col until end) cols[c] = ' '
                    }
                    i = m.range.last + 1
                    continue
                }
                else -> {
                    if (ch != '\n' && col < MAX_COLS) {
                        cols[col] = ch
                        if (col + 1 > maxWritten) maxWritten = col + 1
                    }
                    col++
                }
            }
            i++
        }
        return String(cols, 0, maxWritten)
    }
}
