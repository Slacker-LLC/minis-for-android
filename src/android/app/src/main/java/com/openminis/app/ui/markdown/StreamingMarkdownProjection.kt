package com.openminis.app.ui.markdown

/**
 * [T-android-streaming-projection] Virtual-EOF projection for streaming GFM.
 *
 * The block parser has exactly one input — text — and no notion of "the model
 * may still be typing". While a stream is open, a half-arrived construct is
 * therefore parsed as its literal self and then flips to a real node the moment
 * the closing characters land (`**bold` renders as asterisks, then suddenly
 * becomes bold; a pipe line renders as a paragraph, then becomes a table).
 * That flip is the flicker this file removes.
 *
 * The projection is a single linear scan over the *rendered* copy:
 *
 *  - an open fence is closed with virtual markers of the same character and
 *    length, so the code block keeps its node type from the first line;
 *  - a pending link / image target is withheld entirely, so a half-typed URL
 *    is never shown;
 *  - open inline markers (`` ` ``, `**`, `__`, `~~`, `*`, `_`) are closed with
 *    virtual markers of the same kind;
 *  - a candidate table header is withheld until a legal delimiter row proves
 *    the table exists.
 *
 * Invariants (all enforced here, all unit-tested):
 *
 *  1. Virtual characters exist in the rendered copy only — they are never
 *     written back to the message, the transcript, or the parse cache.
 *  2. Terminal delivery verifies its input: [StreamingMarkdownProjectionResult]
 *     only hands out the rendered source for the exact text it was computed
 *     from, and only when the stream has ended.
 *  3. Non-append input (an upstream rewrite, a message switch) rebuilds the
 *     baseline instead of reusing stale state.
 *  4. Fail-closed: a projection that would drop content the user has already
 *     seen (a pending link that opens before the last settled block boundary,
 *     an empty result) is discarded and the raw source is rendered instead.
 *
 * Ported from Eta @ c15de97 `ui/markdown/StreamingGfmParser.kt`
 * (`StreamingGfmProjection` + `StreamingGfmParserSession`), re-expressed as a
 * pure text transform so this repo can keep its own block parser.
 */
internal object StreamingMarkdownProjection {

    /**
     * Project [source] for rendering. [isComplete] is the real-EOF switch: a
     * finished message is returned untouched, because a closed document has
     * nothing left to stabilise and must render exactly as written.
     */
    fun project(source: String, isComplete: Boolean): String {
        if (isComplete || source.isEmpty()) return source

        // Nothing before this offset may be dropped: it is settled output the
        // user has already seen (see [confirmedPrefixLength]).
        val tableSafeEnd = ambiguousTableStart(source) ?: source.length
        var projected = source.substring(0, tableSafeEnd)

        val openFence = findOpenFence(projected)
        if (openFence != null) {
            if (!projected.endsWith("\n")) projected += "\n"
            return projected + openFence.marker.toString().repeat(openFence.length)
        }

        val pendingLinkStart = findPendingLinkStart(projected)
        if (pendingLinkStart != null) {
            projected = projected.substring(0, pendingLinkStart)
        }

        return projected + findInlineClosures(projected)
    }

    /**
     * [project] with the fail-closed guard applied: when the projection would
     * drop settled content (or produce nothing), the pristine [source] is
     * returned. Callers that render may always use this; callers that need to
     * know whether virtual characters were introduced compare the result with
     * the input.
     */
    fun projectOrFallback(source: String, isComplete: Boolean): String {
        if (isComplete || source.isEmpty()) return source
        val projected = project(source, isComplete = false)
        return if (coversConfirmedPrefix(source, projected)) projected else source
    }

    /**
     * Length of the settled prefix: everything after the last blank line is
     * still in flight (the model may keep appending to it), everything before
     * it has been on screen already. Blank lines inside an open fence count as
     * boundaries too — deliberately conservative: the guard below is allowed
     * to be stricter than the projection, never looser.
     */
    private fun confirmedPrefixLength(source: String): Int {
        val lines = source.toLineSlices()
        val lastBlank = lines.indexOfLast { it.text.isBlank() && it.start > 0 }
        if (lastBlank < 0) return 0
        return lines[lastBlank].start + lines[lastBlank].text.length
    }

    private fun coversConfirmedPrefix(source: String, projected: String): Boolean {
        if (projected.isEmpty()) return false
        val confirmed = confirmedPrefixLength(source)
        if (confirmed <= 0) return true
        return projected.length >= confirmed && source.regionMatches(0, projected, 0, confirmed)
    }

    /**
     * Offset at which the current block must stop being published because it
     * might be a table header whose delimiter row has not arrived yet. Returns
     * null when the tail is unambiguous (a confirmed table, a blank line, or a
     * non-tabular line).
     */
    private fun ambiguousTableStart(source: String): Int? {
        val lines = source.toLineSlices()
        if (lines.isEmpty()) return null

        val blockStart = lines.indexOfLast { it.text.isBlank() }
            .let { blankIndex -> if (blankIndex == -1) 0 else blankIndex + 1 }
        if (blockStart >= lines.size) return null
        val blockLines = lines.subList(blockStart, lines.size)

        val confirmedTable = (1 until blockLines.size).any { index ->
            containsUnescapedPipe(blockLines[index - 1].text) &&
                isValidTableDelimiter(blockLines[index].text)
        }
        if (confirmedTable) return null

        val current = blockLines.last()
        val previous = blockLines.getOrNull(blockLines.lastIndex - 1)

        if (previous != null && containsUnescapedPipe(previous.text)) {
            if (current.text.isEmpty()) {
                // A blank line after a pipe row either ends the block (source
                // ends with \n\n — nothing left to decide) or is still the
                // in-between blank of a table separated by an empty line.
                return if (source.endsWith("\n\n")) null else previous.start
            }
            if (isTableDelimiterCandidate(current.text)) {
                return if (isValidTableDelimiter(current.text)) null else previous.start
            }
        }

        // Offset 0 is deliberately NOT withheld here: a projection that renders
        // nothing is rejected by [projectOrFallback] anyway (the roadmap's
        // "projection shorter than zero → render the raw source" rule), so a
        // single-line source is published as-is instead of blanking the row.
        return current.start.takeIf {
            it > 0 && current.text.isNotBlank() && containsUnescapedPipe(current.text)
        }
    }

    private fun findOpenFence(source: String): Fence? {
        var openFence: Fence? = null
        source.toLineSlices().forEach { line ->
            val marker = line.fenceMarker() ?: return@forEach
            val current = openFence
            if (current == null) {
                openFence = marker
            } else if (marker.marker == current.marker && marker.length >= current.length && marker.isClosing) {
                openFence = null
            }
        }
        return openFence
    }

    /**
     * Start offset of an unterminated link / image, or of a bare `[` that has
     * not been closed yet. Everything from here on is withheld so a half-typed
     * target never reaches the screen.
     */
    private fun findPendingLinkStart(source: String): Int? {
        val bracketStack = ArrayDeque<Int>()
        var inlineCodeTicks = 0
        var openLinkStart: Int? = null
        var linkParenthesisDepth = 0
        var lastClosedBracketStart: Int? = null
        var lastClosedBracketEnd = -1
        var index = 0

        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val char = source[index]
            if (openLinkStart != null) {
                when (char) {
                    '(' -> linkParenthesisDepth += 1
                    ')' -> {
                        linkParenthesisDepth -= 1
                        if (linkParenthesisDepth == 0) openLinkStart = null
                    }
                }
                index += 1
                continue
            }

            when (char) {
                '[' -> bracketStack.addLast(index)
                ']' -> if (bracketStack.isNotEmpty()) {
                    lastClosedBracketStart = bracketStack.removeLast()
                    lastClosedBracketEnd = index
                }
                '(' -> if (lastClosedBracketEnd == index - 1) {
                    openLinkStart = lastClosedBracketStart
                    linkParenthesisDepth = 1
                }
            }
            index += 1
        }

        val pendingStart = openLinkStart ?: bracketStack.lastOrNull()
        return pendingStart?.let { start ->
            if (start > 0 && source[start - 1] == '!') start - 1 else start
        }
    }

    /** Virtual closing markers for every inline construct still open at EOF. */
    private fun findInlineClosures(source: String): String {
        var inlineCodeTicks = 0
        val delimiterStack = ArrayDeque<String>()
        var index = 0

        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }

            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val delimiter = source.delimiterAt(index)
            if (delimiter == null) {
                index += 1
                continue
            }

            val previous = source.getOrNull(index - 1)
            val next = source.getOrNull(index + delimiter.length)
            val canOpen = next != null && !next.isWhitespace()
            val canClose = previous != null && !previous.isWhitespace()
            val intrawordUnderscore = delimiter.contains('_') &&
                previous?.isLetterOrDigit() == true &&
                next?.isLetterOrDigit() == true

            when {
                canClose && delimiterStack.lastOrNull() == delimiter -> delimiterStack.removeLast()
                canOpen && !intrawordUnderscore -> delimiterStack.addLast(delimiter)
            }
            index += delimiter.length
        }

        return buildString {
            if (inlineCodeTicks != 0) append("`".repeat(inlineCodeTicks))
            delimiterStack.reversed().forEach(::append)
        }
    }

    private fun String.delimiterAt(index: Int): String? = when {
        startsWith("**", index) -> "**"
        startsWith("__", index) -> "__"
        startsWith("~~", index) -> "~~"
        this[index] == '*' -> "*"
        this[index] == '_' -> "_"
        else -> null
    }

    private fun String.runLengthAt(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun containsUnescapedPipe(line: String): Boolean {
        var escaped = false
        line.forEach { char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '|' -> return true
            }
        }
        return false
    }

    private fun isTableDelimiterCandidate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() &&
            trimmed.all { char -> char == '|' || char == ':' || char == '-' || char.isWhitespace() }
    }

    private fun isValidTableDelimiter(line: String): Boolean {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = trimmed.split('|').map(String::trim)
        return cells.isNotEmpty() && cells.all { cell ->
            val withoutColons = cell.removePrefix(":").removeSuffix(":")
            withoutColons.length >= 3 && withoutColons.all { it == '-' }
        }
    }

    private fun String.toLineSlices(): List<LineSlice> {
        if (isEmpty()) return listOf(LineSlice(start = 0, text = ""))
        val result = mutableListOf<LineSlice>()
        var start = 0
        for (index in indices) {
            if (this[index] != '\n') continue
            val end = if (index > start && this[index - 1] == '\r') index - 1 else index
            result += LineSlice(start = start, text = substring(start, end))
            start = index + 1
        }
        result += LineSlice(start = start, text = substring(start))
        return result
    }

    private fun LineSlice.fenceMarker(): Fence? {
        var index = 0
        while (index < text.length && index < 4 && text[index] == ' ') index += 1
        if (index > 3) return null
        val marker = text.getOrNull(index)?.takeIf { it == '`' || it == '~' } ?: return null
        val runLength = text.runLengthAt(index, marker)
        if (runLength < 3) return null
        val suffix = text.substring(index + runLength)
        return Fence(marker = marker, length = runLength, isClosing = suffix.isBlank())
    }

    private data class LineSlice(val start: Int, val text: String)

    private data class Fence(val marker: Char, val length: Int, val isClosing: Boolean)
}

/**
 * One projection result. Carries both texts so the terminal path can verify
 * that what it renders still corresponds to the message it came from.
 */
internal data class StreamingMarkdownProjectionResult(
    val originalSource: String,
    val renderedSource: String,
    val isComplete: Boolean,
) {
    /** True when nothing virtual was introduced. */
    val isIdentity: Boolean get() = renderedSource == originalSource

    /**
     * [T-android-streaming-projection] Terminal delivery gate. Returns the
     * source the final (frozen) render must use — or null when this result may
     * not be delivered at all because the stream is still open, or because the
     * text changed since it was projected. Either way the caller re-renders the
     * real content; a stale projection is never shown as final.
     */
    fun verifiedTerminalSource(content: String): String? =
        renderedSource.takeIf { isComplete && originalSource == content }
}

/**
 * Append-only projection session. Remembers the last accepted source so a
 * non-append update (upstream rewrite, session switch, retry that reruns the
 * turn) rebuilds the baseline instead of projecting on top of stale state.
 */
internal class StreamingMarkdownProjectionSession {
    private var acceptedSource = ""

    /** Last text the projection was computed from — test/observability hook. */
    val baseline: String get() = acceptedSource

    fun project(source: String, isComplete: Boolean): StreamingMarkdownProjectionResult {
        if (!source.startsWith(acceptedSource)) {
            // Rebuild: nothing from the previous baseline may leak into this
            // document (there is no cross-text parser state here, but the
            // baseline itself must not be treated as a prefix of the new text).
            acceptedSource = ""
        }
        acceptedSource = source
        return StreamingMarkdownProjectionResult(
            originalSource = source,
            renderedSource = StreamingMarkdownProjection.projectOrFallback(source, isComplete),
            isComplete = isComplete,
        )
    }
}
