package io.github.slackerllc.minis.tools.internal

/** Pure edit engine extracted so the exact matching behavior is JVM-testable. */
object FileEditEngine {
    data class Edit(val oldText: String, val newText: String)
    data class Result(
        val newContent: String,
        val replacementCount: Int,
        val fuzzyMatchCount: Int,
        val firstChangedLine: Int?,
        val diff: String,
    )

    private data class Match(val start: Int, val end: Int, val edit: Edit, val fuzzy: Boolean)

    fun apply(originalRaw: String, edits: List<Edit>, path: String): Result {
        require(edits.isNotEmpty()) { "edits must contain at least one replacement" }
        edits.forEachIndexed { index, e ->
            require(e.oldText.isNotEmpty()) { "edits[$index].old_text cannot be empty" }
        }

        val bom = if (originalRaw.startsWith('\uFEFF')) "\uFEFF" else ""
        val original = if (bom.isEmpty()) originalRaw else originalRaw.substring(1)
        val lineEnding = detectLineEnding(original)
        val normalized = normalizeLf(original)

        val matches = edits.mapIndexed { index, edit ->
            findUniqueMatch(normalized, normalizeLf(edit.oldText), edit, index, path)
        }.sortedBy { it.start }

        for (i in 1 until matches.size) {
            if (matches[i].start < matches[i - 1].end) {
                throw IllegalArgumentException(
                    "edits overlap in $path: edit regions ${matches[i - 1].start}-${matches[i - 1].end} and ${matches[i].start}-${matches[i].end}"
                )
            }
        }

        val out = StringBuilder(normalized)
        for (m in matches.asReversed()) {
            out.replace(m.start, m.end, normalizeLf(m.edit.newText))
        }
        val newNormalized = out.toString()
        val restored = bom + restoreLineEnding(newNormalized, lineEnding)
        val first = matches.minOfOrNull { 1 + normalized.substring(0, it.start).count { c -> c == '\n' } }
        return Result(
            newContent = restored,
            replacementCount = matches.size,
            fuzzyMatchCount = matches.count { it.fuzzy },
            firstChangedLine = first,
            diff = unifiedDiff(path, normalized, newNormalized),
        )
    }

    private fun findUniqueMatch(content: String, old: String, edit: Edit, editIndex: Int, path: String): Match {
        val exact = allIndices(content, old)
        if (exact.size == 1) return Match(exact[0], exact[0] + old.length, edit, false)
        if (exact.size > 1) {
            throw IllegalArgumentException("edits[$editIndex].old_text found ${exact.size} times in $path; make it unique")
        }

        // Fuzzy fallback copied in spirit from Pi: tolerate Unicode punctuation,
        // special spaces, and trailing whitespace without making arbitrary edits.
        // The trailing-newline rule is unified for both sides: each side is
        // reduced to its newline-trimmed body for the content comparison, and
        // the trailing-newline counts may differ by at most one. (trimEnd of ALL
        // trailing newlines used to let a multi-line block match a shorter
        // needle no matter how many blank lines followed — the replacement
        // region then swallowed those blank lines.)
        val oldTrimmed = old.trimEnd('\n')
        val oldTrailingNewlines = old.length - oldTrimmed.length
        val fuzzyNeedle = normalizeFuzzy(oldTrimmed)
        val lineSpans = lineSpans(content)
        val oldLineCount = old.split('\n').size
        val fuzzyMatches = mutableListOf<Pair<Int, Int>>()
        for (startLine in lineSpans.indices) {
            val endLine = (startLine + oldLineCount - 1).coerceAtMost(lineSpans.lastIndex)
            val start = lineSpans[startLine].first
            val end = lineSpans[endLine].second
            val block = content.substring(start, end)
            val blockTrimmed = block.trimEnd('\n')
            val blockTrailingNewlines = block.length - blockTrimmed.length
            if (normalizeFuzzy(blockTrimmed) == fuzzyNeedle &&
                kotlin.math.abs(blockTrailingNewlines - oldTrailingNewlines) <= 1
            ) {
                // Keep only as many trailing newlines as the needle itself has
                // (capped by what the block provides) so blank lines that merely
                // followed the match are not deleted by the edit.
                val effectiveEnd = start + blockTrimmed.length +
                    minOf(oldTrailingNewlines, blockTrailingNewlines)
                fuzzyMatches += start to effectiveEnd
            }
        }
        if (fuzzyMatches.size == 1) {
            val (start, end) = fuzzyMatches[0]
            return Match(start, end, edit, true)
        }
        if (fuzzyMatches.size > 1) {
            throw IllegalArgumentException("edits[$editIndex].old_text fuzzy-matched ${fuzzyMatches.size} times in $path; make it unique")
        }
        throw IllegalArgumentException("edits[$editIndex].old_text not found in $path")
    }

    private fun allIndices(haystack: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (from <= haystack.length - needle.length) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            out += idx
            from = idx + needle.length.coerceAtLeast(1)
        }
        return out
    }

    private fun lineSpans(text: String): List<Pair<Int, Int>> {
        if (text.isEmpty()) return listOf(0 to 0)
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl + 1
            spans += start to end
            start = end
        }
        return spans
    }

    fun detectLineEnding(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"
    fun normalizeLf(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
    fun restoreLineEnding(text: String, ending: String): String = if (ending == "\r\n") text.replace("\n", "\r\n") else text

    fun normalizeFuzzy(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("[\\u2018\\u2019\\u201A\\u201B]"), "'")
        .replace(Regex("[\\u201C\\u201D\\u201E\\u201F]"), "\"")
        .replace(Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]"), "-")
        .replace(Regex("[\\u00A0\\u2002-\\u200A\\u202F\\u205F\\u3000]"), " ")

    /** Small deterministic unified-style diff; capped by callers before tool return. */
    fun unifiedDiff(path: String, before: String, after: String, context: Int = 3): String {
        if (before == after) return ""
        val a = before.split('\n')
        val b = after.split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
        val aStart = (prefix - context).coerceAtLeast(0)
        val bStart = (prefix - context).coerceAtLeast(0)
        val aEnd = (a.size - suffix + context).coerceAtMost(a.size)
        val bEnd = (b.size - suffix + context).coerceAtMost(b.size)
        return buildString {
            append("--- a/").append(path).append('\n')
            append("+++ b/").append(path).append('\n')
            append("@@ -").append(aStart + 1).append(',').append(aEnd - aStart)
                .append(" +").append(bStart + 1).append(',').append(bEnd - bStart).append(" @@\n")
            for (i in aStart until prefix) append(' ').append(a[i]).append('\n')
            for (i in prefix until a.size - suffix) append('-').append(a[i]).append('\n')
            for (i in prefix until b.size - suffix) append('+').append(b[i]).append('\n')
            for (i in (a.size - suffix).coerceAtLeast(prefix) until aEnd) append(' ').append(a[i]).append('\n')
        }
    }
}
