package com.openminis.app.ui.chat.md

/** The parsed document: block tree plus what inline parsing needs from it. */
internal class MdDocument(
    val root: BNode,
    val refmap: Map<String, LinkRef>,
    /** Normalised labels of the footnote definitions, in source order. */
    val footnoteLabels: List<String>,
    /** Source lines (1-based, inclusive) taken up by link reference definitions, which render as nothing. */
    val refDefLines: List<IntRange> = emptyList(),
)

/**
 * Block structure of a CommonMark + GFM document, line by line: containers (block quotes, list items, footnote
 * definitions) are matched first, then a new block may start, then the rest of the line is text for the
 * innermost open block. This is the reference implementation's algorithm.
 */
internal class BlockParser(private val gfm: Boolean = true) {
    private val doc = BNode(BType.DOCUMENT)
    private var tip: BNode = doc
    private var oldtip: BNode = doc
    private var lastMatchedContainer: BNode = doc
    private var allClosed = true

    private var currentLine = ""
    private var lineNumber = 0
    private var offset = 0
    private var column = 0
    private var nextNonspace = 0
    private var nextNonspaceColumn = 0
    private var indent = 0
    private var indented = false
    private var blank = false
    private var partiallyConsumedTab = false

    private val blockStarts: List<(BlockParser, BNode) -> Int> =
        ALL_STARTS.filter { gfm || !it.first }.map { it.second }

    private val refmap = LinkedHashMap<String, LinkRef>()
    private val footnoteLabels = ArrayList<String>()
    private val refDefRanges = ArrayList<IntRange>()
    private val inline = InlineParser(emptyMap(), gfm = gfm)

    fun parse(input: String): MdDocument {
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        // a trailing newline does not make a final empty line
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        for (line in lines) incorporateLine(MdText.sanitizeLine(line))
        while (tip !== doc) finalize(tip)
        finalize(doc)
        return MdDocument(doc, refmap, footnoteLabels, refDefRanges)
    }

    // ─── line helpers ───────────────────────────────────────────────────────

    private fun peek(ln: String, pos: Int): Int = if (pos < ln.length) ln[pos].code else -1

    private fun findNextNonspace() {
        var i = offset
        var cols = column
        var c: Int
        while (true) {
            c = peek(currentLine, i)
            if (c == ' '.code) {
                i++
                cols++
            } else if (c == '\t'.code) {
                i++
                cols += 4 - (cols % 4)
            } else {
                break
            }
        }
        blank = c == '\n'.code || c == '\r'.code || c == -1
        nextNonspace = i
        nextNonspaceColumn = cols
        indent = nextNonspaceColumn - column
        indented = indent >= CODE_INDENT
    }

    private fun advanceNextNonspace() {
        offset = nextNonspace
        column = nextNonspaceColumn
        partiallyConsumedTab = false
    }

    private fun advanceOffset(count0: Int, columns: Boolean) {
        var count = count0
        while (count > 0 && offset < currentLine.length) {
            val c = currentLine[offset]
            if (c == '\t') {
                val charsToTab = 4 - (column % 4)
                if (columns) {
                    partiallyConsumedTab = charsToTab > count
                    val charsToAdvance = if (charsToTab > count) count else charsToTab
                    column += charsToAdvance
                    offset += if (partiallyConsumedTab) 0 else 1
                    count -= charsToAdvance
                } else {
                    partiallyConsumedTab = false
                    column += charsToTab
                    offset += 1
                    count -= 1
                }
            } else {
                partiallyConsumedTab = false
                offset += 1
                column += 1
                count -= 1
            }
        }
    }

    private fun addLine() {
        if (partiallyConsumedTab) {
            offset += 1
            val charsToTab = 4 - (column % 4)
            tip.content.append(" ".repeat(charsToTab))
        }
        tip.content.append(currentLine, offset.coerceAtMost(currentLine.length), currentLine.length).append('\n')
    }

    private fun canContain(parent: BType, child: BType): Boolean = when (parent) {
        BType.DOCUMENT, BType.BLOCK_QUOTE, BType.ITEM, BType.FOOTNOTE_DEFINITION -> child != BType.ITEM
        BType.LIST -> child == BType.ITEM
        else -> false
    }

    private fun acceptsLines(t: BType) = when (t) {
        BType.PARAGRAPH, BType.CODE_BLOCK, BType.HTML_BLOCK, BType.TABLE -> true
        else -> false
    }

    private fun addChild(type: BType, offsetAt: Int): BNode {
        while (!canContain(tip.type, type)) finalize(tip)
        val node = BNode(type)
        node.startLine = lineNumber
        tip.append(node)
        tip = node
        @Suppress("UNUSED_VARIABLE") val unused = offsetAt
        return node
    }

    private fun closeUnmatchedBlocks() {
        if (!allClosed) {
            while (oldtip !== lastMatchedContainer) {
                val parent = oldtip.parent!!
                finalize(oldtip)
                oldtip = parent
            }
            allClosed = true
        }
    }

    // ─── the main loop ──────────────────────────────────────────────────────

    private fun incorporateLine(ln: String) {
        var allMatched = true
        var container: BNode = doc
        oldtip = tip
        offset = 0
        column = 0
        blank = false
        partiallyConsumedTab = false
        lineNumber++
        currentLine = ln

        // 1. which open containers does this line continue?
        while (true) {
            val lastChild = container.lastChild
            if (lastChild == null || !lastChild.open) break
            container = lastChild
            findNextNonspace()
            when (continueBlock(container)) {
                0 -> Unit
                1 -> allMatched = false
                2 -> return
            }
            if (!allMatched) {
                container = container.parent!!
                break
            }
        }
        allClosed = container === oldtip
        lastMatchedContainer = container

        var matchedLeaf = container.type != BType.PARAGRAPH && container.type != BType.TABLE && acceptsLines(container.type)

        // 2. does a new block start here?
        while (!matchedLeaf) {
            findNextNonspace()
            if (!indented && !MAYBE_SPECIAL.matches(currentLine.substring(nextNonspace.coerceAtMost(currentLine.length)).take(1).ifEmpty { " " })) {
                advanceNextNonspace()
                break
            }
            var started = 0
            for (start in blockStarts) {
                val res = start(this, container)
                if (res == 1) {
                    container = tip
                    started = 1
                    break
                } else if (res == 2) {
                    container = tip
                    matchedLeaf = true
                    started = 2
                    break
                }
            }
            if (started == 0) {
                advanceNextNonspace()
                break
            }
        }

        // 3. what remains is text for the innermost block
        if (!allClosed && !blank && tip.type == BType.PARAGRAPH) {
            // lazy paragraph continuation
            addLine()
        } else {
            closeUnmatchedBlocks()
            if (blank && container.lastChild != null) container.lastChild!!.lastLineBlank = true
            val t = container.type
            val lastLineBlank = blank && !(
                t == BType.BLOCK_QUOTE || (t == BType.CODE_BLOCK && container.isFenced) ||
                    (t == BType.ITEM && container.firstChild == null && container.startLine == lineNumber)
                )
            var cont: BNode? = container
            while (cont != null) {
                cont.lastLineBlank = lastLineBlank
                cont = cont.parent
            }
            if (acceptsLines(t)) {
                addLine()
                if (t == BType.HTML_BLOCK && container.htmlBlockType in 1..5 &&
                    HTML_BLOCK_CLOSE[container.htmlBlockType]!!.containsMatchIn(currentLine.substring(offset.coerceAtMost(currentLine.length)))
                ) {
                    finalize(container)
                }
            } else if (offset < currentLine.length && !blank) {
                container = addChild(BType.PARAGRAPH, offset)
                advanceNextNonspace()
                addLine()
            }
        }
    }

    // ─── continuing open blocks ─────────────────────────────────────────────

    /** 0 = the line continues [container]; 1 = it does not; 2 = the line closed it (end of a fence). */
    private fun continueBlock(container: BNode): Int = when (container.type) {
        BType.DOCUMENT, BType.LIST -> 0
        BType.BLOCK_QUOTE -> {
            if (!indented && peek(currentLine, nextNonspace) == '>'.code) {
                advanceNextNonspace()
                advanceOffset(1, false)
                if (isSpaceOrTab(peek(currentLine, offset))) advanceOffset(1, true)
                0
            } else 1
        }
        BType.ITEM -> {
            val data = container.list!!
            if (blank) {
                if (container.firstChild == null) 1 else {
                    advanceNextNonspace()
                    0
                }
            } else if (indent >= data.markerOffset + data.padding) {
                advanceOffset(data.markerOffset + data.padding, true)
                0
            } else 1
        }
        BType.FOOTNOTE_DEFINITION -> {
            if (blank) {
                advanceNextNonspace()
                0
            } else if (indent >= 4) {
                advanceOffset(4, true)
                0
            } else 1
        }
        BType.HEADING, BType.THEMATIC_BREAK -> 1
        BType.CODE_BLOCK -> {
            val ln = currentLine
            if (container.isFenced) {
                val rest = if (nextNonspace <= ln.length) ln.substring(nextNonspace) else ""
                val m = if (indent <= 3 && rest.isNotEmpty() && rest[0] == container.fenceChar) CLOSING_FENCE.find(rest) else null
                if (m != null && m.value.length >= container.fenceLength) {
                    finalize(container)
                    2
                } else {
                    var i = container.fenceOffset
                    while (i > 0 && isSpaceOrTab(peek(ln, offset))) {
                        advanceOffset(1, true)
                        i--
                    }
                    0
                }
            } else {
                if (indent >= CODE_INDENT) {
                    advanceOffset(CODE_INDENT, true)
                    0
                } else if (blank) {
                    advanceNextNonspace()
                    0
                } else 1
            }
        }
        BType.HTML_BLOCK -> if (blank && (container.htmlBlockType == 6 || container.htmlBlockType == 7)) 1 else 0
        BType.PARAGRAPH -> if (blank) 1 else 0
        BType.TABLE -> if (blank) 1 else 0
    }

    // ─── finalizing ─────────────────────────────────────────────────────────

    private fun finalize(block: BNode) {
        val above = block.parent
        block.open = false
        when (block.type) {
            BType.PARAGRAPH -> finalizeParagraph(block)
            BType.CODE_BLOCK -> {
                val content = block.content.toString()
                if (block.isFenced) {
                    val nl = content.indexOf('\n')
                    val first = if (nl >= 0) content.substring(0, nl) else content
                    val rest = if (nl >= 0) content.substring(nl + 1) else ""
                    block.info = MdText.unescapeString(first.trim())
                    block.literal = rest
                } else {
                    block.literal = content.replace(Regex("(\\n *)+$"), "\n")
                }
            }
            BType.HTML_BLOCK -> block.literal = block.content.toString().replace(Regex("(\\n *)+$"), "")
            BType.LIST -> finalizeList(block)
            BType.TABLE -> finalizeTable(block)
            BType.FOOTNOTE_DEFINITION -> if (block.label.isNotEmpty() && block.label !in footnoteLabels) footnoteLabels += block.label
            else -> Unit
        }
        if (above != null) tip = above
    }

    private fun finalizeParagraph(block: BNode) {
        val original = block.content.toString()
        var s = original
        var hasDefs = false
        while (s.isNotEmpty() && s[0] == '[') {
            val pos = inline.parseReference(s, refmap)
            if (pos == 0) break
            s = s.substring(pos)
            hasDefs = true
        }
        if (hasDefs) {
            val lines = original.substring(0, original.length - s.length).count { it == '\n' }
            if (lines > 0) refDefRanges += block.startLine until block.startLine + lines

            block.content.setLength(0)
            block.content.append(s)
            if (MdText.isBlank(s)) block.unlink()
        }
    }

    private fun finalizeList(block: BNode) {
        val data = block.list!!
        var itemIdx = 0
        while (itemIdx < block.children.size) {
            val item = block.children[itemIdx]
            val hasNext = itemIdx + 1 < block.children.size
            if (endsWithBlankLine(item) && hasNext) {
                data.tight = false
                break
            }
            var subIdx = 0
            while (subIdx < item.children.size) {
                val sub = item.children[subIdx]
                val subHasNext = subIdx + 1 < item.children.size
                if (endsWithBlankLine(sub) && (hasNext || subHasNext)) {
                    data.tight = false
                    break
                }
                subIdx++
            }
            if (!data.tight) break
            itemIdx++
        }
    }

    private fun endsWithBlankLine(start: BNode): Boolean {
        var block: BNode? = start
        while (block != null) {
            if (block.lastLineBlank) return true
            val t = block.type
            if (!block.lastLineChecked && (t == BType.LIST || t == BType.ITEM)) {
                block.lastLineChecked = true
                block = block.lastChild
            } else {
                block.lastLineChecked = true
                break
            }
        }
        return false
    }

    // ─── GFM tables ─────────────────────────────────────────────────────────

    private fun finalizeTable(block: BNode) {
        val lines = block.content.toString().split('\n').filter { it.isNotEmpty() }
        val header = splitRow(block.tableHeader.firstOrNull() ?: "")
        val ncols = header.size
        val rows = ArrayList<List<String>>()
        for (line in lines) {
            val cells = splitRow(line).toMutableList()
            while (cells.size < ncols) cells += ""
            rows += cells.take(ncols)
        }
        block.tableHeader = header
        block.tableRows = rows
    }

    // ─── block starts ───────────────────────────────────────────────────────

    private fun isSpaceOrTab(c: Int) = c == ' '.code || c == '\t'.code

    private fun startBlockQuote(container: BNode): Int {
        if (!indented && peek(currentLine, nextNonspace) == '>'.code) {
            advanceNextNonspace()
            advanceOffset(1, false)
            if (isSpaceOrTab(peek(currentLine, offset))) advanceOffset(1, true)
            closeUnmatchedBlocks()
            addChild(BType.BLOCK_QUOTE, nextNonspace)
            return 1
        }
        return 0
    }

    private fun startAtxHeading(container: BNode): Int {
        if (indented) return 0
        val rest = currentLine.substring(nextNonspace)
        val m = ATX_MARKER.find(rest) ?: return 0
        advanceNextNonspace()
        advanceOffset(m.value.length, false)
        closeUnmatchedBlocks()
        val heading = addChild(BType.HEADING, nextNonspace)
        heading.level = m.value.trim().length
        var text = currentLine.substring(offset)
        text = text.replace(Regex("^[ \\t]*#+[ \\t]*$"), "").replace(Regex("[ \\t]+#+[ \\t]*$"), "")
        heading.content.append(text)
        advanceOffset(currentLine.length - offset, false)
        return 2
    }

    private fun startFencedCode(container: BNode): Int {
        if (indented) return 0
        val rest = currentLine.substring(nextNonspace)
        val m = CODE_FENCE.find(rest) ?: return 0
        val fenceLength = m.value.length
        closeUnmatchedBlocks()
        val code = addChild(BType.CODE_BLOCK, nextNonspace)
        code.isFenced = true
        code.fenceLength = fenceLength
        code.fenceChar = m.value[0]
        code.fenceOffset = indent
        advanceNextNonspace()
        advanceOffset(fenceLength, false)
        return 2
    }

    private fun startHtmlBlock(container: BNode): Int {
        if (!indented && peek(currentLine, nextNonspace) == '<'.code) {
            val s = currentLine.substring(nextNonspace)
            for (blockType in 1..7) {
                if (HTML_BLOCK_OPEN[blockType]!!.containsMatchIn(s) &&
                    (blockType < 7 || (container.type != BType.PARAGRAPH && !(!allClosed && !blank && tip.type == BType.PARAGRAPH)))
                ) {
                    closeUnmatchedBlocks()
                    val b = addChild(BType.HTML_BLOCK, offset)
                    b.htmlBlockType = blockType
                    return 2
                }
            }
        }
        return 0
    }

    private fun startSetextHeading(container: BNode): Int {
        if (!indented && container.type == BType.PARAGRAPH) {
            val m = SETEXT_LINE.find(currentLine.substring(nextNonspace)) ?: return 0
            closeUnmatchedBlocks()
            val original = container.content.toString()
            var s = original
            while (s.isNotEmpty() && s[0] == '[') {
                val pos = inline.parseReference(s, refmap)
                if (pos == 0) break
                s = s.substring(pos)
            }
            val consumedLines = original.substring(0, original.length - s.length).count { it == '\n' }
            if (consumedLines > 0) refDefRanges += container.startLine until container.startLine + consumedLines
            if (s.isNotEmpty()) {
                val heading = BNode(BType.HEADING)
                heading.level = if (m.value[0] == '=') 1 else 2
                heading.content.append(s)
                heading.startLine = container.startLine
                val parent = container.parent!!
                val idx = parent.children.indexOf(container)
                parent.children[idx] = heading
                heading.parent = parent
                container.parent = null
                tip = heading
                advanceOffset(currentLine.length - offset, false)
                return 2
            }
        }
        return 0
    }

    private fun startThematicBreak(container: BNode): Int {
        if (!indented && THEMATIC_BREAK.matches(currentLine.substring(nextNonspace))) {
            closeUnmatchedBlocks()
            addChild(BType.THEMATIC_BREAK, nextNonspace)
            advanceOffset(currentLine.length - offset, false)
            return 2
        }
        return 0
    }

    private fun parseListMarker(container: BNode): ListData? {
        val rest = currentLine.substring(nextNonspace)
        if (indent >= 4) return null
        var ordered = false
        var bullet: Char? = null
        var start = 0
        var delimiter: Char? = null
        val markerLen: Int
        val bm = BULLET_MARKER.find(rest)
        if (bm != null) {
            bullet = bm.value[0]
            markerLen = 1
        } else {
            val om = ORDERED_MARKER.find(rest)
            if (om != null && (container.type != BType.PARAGRAPH || om.groupValues[1].toInt() == 1)) {
                ordered = true
                start = om.groupValues[1].toInt()
                delimiter = om.groupValues[2][0]
                markerLen = om.value.length
            } else {
                return null
            }
        }
        val nextc = peek(currentLine, nextNonspace + markerLen)
        if (!(nextc == -1 || nextc == '\t'.code || nextc == ' '.code)) return null
        // an item that interrupts a paragraph may not start empty
        if (container.type == BType.PARAGRAPH &&
            NON_SPACE.find(currentLine.substring((nextNonspace + markerLen).coerceAtMost(currentLine.length))) == null
        ) return null
        val markerOffset = indent
        advanceNextNonspace()
        advanceOffset(markerLen, true)
        val spacesStartCol = column
        val spacesStartOffset = offset
        do {
            advanceOffset(1, true)
        } while (column - spacesStartCol < 5 && isSpaceOrTab(peek(currentLine, offset)))
        val blankItem = peek(currentLine, offset) == -1
        val spacesAfterMarker = column - spacesStartCol
        val padding: Int
        if (spacesAfterMarker >= 5 || spacesAfterMarker < 1 || blankItem) {
            padding = markerLen + 1
            column = spacesStartCol
            offset = spacesStartOffset
            if (isSpaceOrTab(peek(currentLine, offset))) advanceOffset(1, true)
        } else {
            padding = markerLen + spacesAfterMarker
        }
        return ListData(ordered, bullet, start, delimiter, padding, markerOffset)
    }

    private fun startListItem(container: BNode): Int {
        if (!indented || container.type == BType.LIST) {
            val data = parseListMarker(container) ?: return 0
            closeUnmatchedBlocks()
            val tipList = tip.list
            if (tip.type != BType.LIST || tipList == null || !listsMatch(tipList, data)) {
                val list = addChild(BType.LIST, nextNonspace)
                list.list = data
            }
            val item = addChild(BType.ITEM, nextNonspace)
            item.list = data
            // GFM task list marker: "[ ]" or "[x]" followed by a space, at the start of the item
            val rest = currentLine.substring(offset.coerceAtMost(currentLine.length))
            val tm = TASK_MARKER.find(rest)
            if (gfm && tm != null) {
                item.task = tm.groupValues[1] != " "
                advanceOffset(tm.value.length, true)
            }
            return 1
        }
        return 0
    }

    private fun listsMatch(a: ListData, b: ListData) =
        a.ordered == b.ordered && a.delimiter == b.delimiter && a.bulletChar == b.bulletChar

    private fun startIndentedCode(container: BNode): Int {
        if (indented && tip.type != BType.PARAGRAPH && !blank) {
            advanceOffset(CODE_INDENT, true)
            closeUnmatchedBlocks()
            addChild(BType.CODE_BLOCK, offset)
            return 2
        }
        return 0
    }

    private fun startFootnoteDefinition(container: BNode): Int {
        if (indented) return 0
        val rest = currentLine.substring(nextNonspace)
        val m = FOOTNOTE_DEF.find(rest) ?: return 0
        closeUnmatchedBlocks()
        val def = addChild(BType.FOOTNOTE_DEFINITION, nextNonspace)
        def.label = MdText.normalizeReference(m.groupValues[1])
        advanceNextNonspace()
        advanceOffset(m.value.length, false)
        return 1
    }

    /** A GFM table starts when a paragraph's last line is a header row and this line is its delimiter row. */
    private fun startTable(container: BNode): Int {
        if (indented || container.type != BType.PARAGRAPH) return 0
        val rest = currentLine.substring(nextNonspace)
        val aligns = parseDelimiterRow(rest) ?: return 0
        val content = container.content.toString().trimEnd('\n')
        val lastNl = content.lastIndexOf('\n')
        val headerLine = content.substring(lastNl + 1)
        val headerCells = splitRow(headerLine)
        if (headerCells.size != aligns.size) return 0
        if (!headerLine.contains('|') && !rest.contains('|')) return 0
        closeUnmatchedBlocks()
        val before = if (lastNl >= 0) content.substring(0, lastNl + 1) else ""
        val table = BNode(BType.TABLE)
        table.startLine = lineNumber - 1 // the header row, the line before this delimiter row
        table.tableAligns = aligns
        table.tableHeader = listOf(headerLine)
        val parent = container.parent!!
        if (before.isBlank()) {
            val idx = parent.children.indexOf(container)
            parent.children[idx] = table
            table.parent = parent
            container.parent = null
        } else {
            container.content.setLength(0)
            container.content.append(before)
            finalize(container)
            parent.append(table)
        }
        tip = table
        advanceOffset(currentLine.length - offset, false)
        return 2
    }

    // ─── tables: row splitting ──────────────────────────────────────────────

    companion object {
        const val CODE_INDENT = 4

        private val MAYBE_SPECIAL = Regex("^[#`~*+_=<>0-9:|\\-\\[]")
        private val ATX_MARKER = Regex("^#{1,6}(?:[ \\t]+|$)")
        private val CODE_FENCE = Regex("^`{3,}(?!.*`)|^~{3,}")
        private val CLOSING_FENCE = Regex("^(?:`{3,}|~{3,})(?=[ \\t]*$)")
        private val SETEXT_LINE = Regex("^(?:=+|-+)[ \\t]*$")
        private val THEMATIC_BREAK = Regex("^(?:(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,}|(?:-[ \\t]*){3,})$")
        private val BULLET_MARKER = Regex("^[*+-]")
        private val ORDERED_MARKER = Regex("^(\\d{1,9})([.)])")
        private val NON_SPACE = Regex("[^ \\t\\f\\v\\r\\n]")
        private val TASK_MARKER = Regex("^\\[([ xX])\\](?=[ \\t]|$)[ \\t]?")
        private val FOOTNOTE_DEF = Regex("^\\[\\^([^\\]\\s]+)\\]:[ \\t]?")

        private val HTML_BLOCK_OPEN: Map<Int, Regex> = mapOf(
            1 to Regex("^<(?:script|pre|textarea|style)(?:\\s|>|$)", RegexOption.IGNORE_CASE),
            2 to Regex("^<!--"),
            3 to Regex("^<[?]"),
            4 to Regex("^<![A-Za-z]"),
            5 to Regex("^<!\\[CDATA\\["),
            6 to Regex(
                "^<[/]?(?:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[123456]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?:\\s|[/]?[>]|$)",
                RegexOption.IGNORE_CASE,
            ),
            7 to Regex("^(?:${InlineParser.OPENTAG}|${InlineParser.CLOSETAG})\\s*$", RegexOption.IGNORE_CASE),
        )
        private val HTML_BLOCK_CLOSE: Map<Int, Regex> = mapOf(
            1 to Regex("</(?:script|pre|textarea|style)>", RegexOption.IGNORE_CASE),
            2 to Regex("-->"),
            3 to Regex("\\?>"),
            4 to Regex(">"),
            5 to Regex("\\]\\]>"),
        )

        private val DELIMITER_CELL = Regex("^[ \\t]*:?-+:?[ \\t]*$")

        /** A GFM delimiter row ("| --- | :-: |"); its alignments, or null when [line] is not one. */
        fun parseDelimiterRow(line: String): List<Align>? {
            val t = line.trim()
            if (t.isEmpty() || !(t.contains('-'))) return null
            if (!t.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }) return null
            val cells = splitRow(t)
            if (cells.isEmpty() || !cells.all { DELIMITER_CELL.matches(it) }) return null
            // A lone "---" without any pipe is a thematic break / setext underline, not a table.
            if (!t.contains('|') && cells.size < 2) return null
            return cells.map { c ->
                val s = c.trim()
                val l = s.startsWith(":")
                val r = s.endsWith(":")
                when {
                    l && r -> Align.CENTER
                    l -> Align.LEFT
                    r -> Align.RIGHT
                    else -> Align.NONE
                }
            }
        }

        /** Cells of one table row: leading/trailing pipes optional, `\|` and pipes inside code spans are literal. */
        fun splitRow(row: String): List<String> {
            var s = row.trim()
            if (s.startsWith("|")) s = s.substring(1)
            val cells = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            var inCode = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                        cur.append('|')
                        i += 2
                    }
                    c == '\\' && i + 1 < s.length -> {
                        cur.append(c).append(s[i + 1])
                        i += 2
                    }
                    c == '`' -> {
                        var j = i
                        while (j < s.length && s[j] == '`') j++
                        val run = j - i
                        inCode = if (inCode == 0) run else if (inCode == run) 0 else inCode
                        cur.append(s, i, j)
                        i = j
                    }
                    c == '|' && inCode == 0 -> {
                        cells += cur.toString().trim()
                        cur.setLength(0)
                        i++
                    }
                    else -> {
                        cur.append(c)
                        i++
                    }
                }
            }
            val last = cur.toString().trim()
            // a trailing pipe leaves an empty last cell that is not a cell
            if (!(last.isEmpty() && s.trimEnd().endsWith("|") && cells.isNotEmpty())) cells += last
            return cells
        }

        private val ALL_STARTS: List<Pair<Boolean, (BlockParser, BNode) -> Int>> = listOf(
            false to { p, c -> p.startBlockQuote(c) },
            true to { p, c -> p.startFootnoteDefinition(c) },
            false to { p, c -> p.startAtxHeading(c) },
            false to { p, c -> p.startFencedCode(c) },
            false to { p, c -> p.startHtmlBlock(c) },
            false to { p, c -> p.startSetextHeading(c) },
            false to { p, c -> p.startThematicBreak(c) },
            true to { p, c -> p.startTable(c) },
            false to { p, c -> p.startListItem(c) },
            false to { p, c -> p.startIndentedCode(c) },
        )
    }
}
