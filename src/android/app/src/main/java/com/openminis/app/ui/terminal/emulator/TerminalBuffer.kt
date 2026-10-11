package com.openminis.app.ui.terminal.emulator

/**
 * Terminal screen buffer: rows × cols character grid + scrollback history + cursor state + scroll region.
 *
 * Each row knows whether it was soft-wrapped into the next one (the text ran past the right edge), which lets a copy
 * rejoin a long line and lets a resize reflow the text: a change of width re-breaks the lines, a change of height keeps
 * the cursor line in view by moving rows into and out of the history, as terminals such as Termux do.
 */
class TerminalBuffer(
    var cols: Int,
    var rows: Int,
    val maxScrollback: Int = 2000,
) {
    var grid: Array<Array<TerminalCell>> = makeGrid(cols, rows)
        private set

    /** `gridWrapped[r]`: row r continues on row r + 1 (a soft wrap, not a line break). */
    var gridWrapped: BooleanArray = BooleanArray(rows)
        private set
    val scrollback: ArrayDeque<Array<TerminalCell>> = ArrayDeque()

    /** Parallel to [scrollback]: whether that history row continues on the next one. */
    val scrollbackWrapped: ArrayDeque<Boolean> = ArrayDeque()
    var scrollbackEnabled: Boolean = true

    var cursorCol: Int = 0
    var cursorRow: Int = 0

    private var savedCursorCol: Int = 0
    private var savedCursorRow: Int = 0
    private var savedCursorStyle: CursorStyle = CursorStyle()

    var wrapPending: Boolean = false

    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    private fun makeGrid(c: Int, r: Int): Array<Array<TerminalCell>> =
        Array(r) { Array(c) { TerminalCell.BLANK } }

    private fun blankLine(): Array<TerminalCell> = Array(cols) { TerminalCell.BLANK }

    // ── Cursor movement ────────────────────────────────────────────────────
    fun moveCursorTo(col: Int, row: Int) {
        cursorCol = clampCol(col); cursorRow = clampRow(row); wrapPending = false
    }
    fun moveCursorToColumn(col: Int) { cursorCol = clampCol(col); wrapPending = false }
    fun moveCursorUp(n: Int) { cursorRow = maxOf(scrollTop, cursorRow - n); wrapPending = false }
    fun moveCursorDown(n: Int) { cursorRow = minOf(scrollBottom, cursorRow + n); wrapPending = false }
    fun moveCursorForward(n: Int) { cursorCol = minOf(cols - 1, cursorCol + n); wrapPending = false }
    fun moveCursorBackward(n: Int) { cursorCol = maxOf(0, cursorCol - n); wrapPending = false }

    // ── Save / restore ─────────────────────────────────────────────────────
    fun saveCursor(style: CursorStyle) {
        savedCursorCol = cursorCol; savedCursorRow = cursorRow
        savedCursorStyle = style.copy()
    }
    fun restoreCursor(): CursorStyle {
        cursorCol = clampCol(savedCursorCol); cursorRow = clampRow(savedCursorRow); wrapPending = false
        return savedCursorStyle.copy()
    }

    // ── Writing ────────────────────────────────────────────────────────────
    fun writeChar(codePoint: Int, style: CursorStyle, autoWrap: Boolean) {
        val width = characterWidth(codePoint)
        if (width == 0) {
            attachToPrevious(codePoint)
            return
        }
        if (wrapPending) {
            if (autoWrap) wrapToNextLine()
            wrapPending = false
        }
        if (width == 2 && cursorCol >= cols - 1) {
            if (autoWrap) {
                // The wide character does not fit in the last column: leave it empty and continue on the next row.
                if (cursorRow in 0 until rows && cursorCol in 0 until cols) grid[cursorRow][cursorCol] = TerminalCell.WRAP_PAD
                wrapToNextLine()
                wrapPending = false
            } else cursorCol = cols - 2
        }
        if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
            grid[cursorRow][cursorCol] = style.makeCell(codePoint, width)
            if (width == 2 && cursorCol + 1 < cols) {
                grid[cursorRow][cursorCol + 1] = TerminalCell(
                    char = ' '.code,
                    foreground = style.foreground,
                    background = style.background,
                    attributes = style.attributes,
                    width = 1,
                    isWideTrailer = true,
                )
            }
        }
        cursorCol += width
        if (cursorCol >= cols) { cursorCol = cols - 1; wrapPending = true }
    }

    /** The text ran off the right edge: the row continues on the next one. */
    private fun wrapToNextLine() {
        if (cursorRow in 0 until rows) gridWrapped[cursorRow] = true
        carriageReturn()
        lineFeed(continuesLine = true)
    }

    /** A combining mark, joiner or variation selector goes on the character before it, not into a cell of its own. */
    private fun attachToPrevious(codePoint: Int) {
        if (cursorRow !in 0 until rows) return
        var col = if (wrapPending) cursorCol else cursorCol - 1
        if (col < 0 || col >= cols) return
        if (col > 0 && grid[cursorRow][col].isWideTrailer) col--
        val cell = grid[cursorRow][col]
        val extra = cell.extra.orEmpty()
        if (extra.length >= MAX_EXTRA) return
        grid[cursorRow][col] = cell.copy(extra = extra + String(Character.toChars(codePoint)))
    }

    // ── Line ops ───────────────────────────────────────────────────────────
    fun carriageReturn() { cursorCol = 0; wrapPending = false }

    /** Moves down a row. A line feed the program wrote ends the line; [continuesLine] is the soft wrap's own. */
    fun lineFeed(continuesLine: Boolean = false) {
        if (!continuesLine && cursorRow in 0 until rows) gridWrapped[cursorRow] = false
        if (cursorRow == scrollBottom) scrollUp(1)
        else if (cursorRow < rows - 1) cursorRow++
        wrapPending = false
    }

    fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1)
        else if (cursorRow > 0) cursorRow--
        wrapPending = false
    }

    // ── Scroll ─────────────────────────────────────────────────────────────
    fun scrollUp(n: Int) {
        // Past the region's height every extra step only moves blank lines: bound the work by it.
        repeat(n.coerceIn(0, scrollBottom - scrollTop + 1)) {
            if (scrollbackEnabled) {
                scrollback.addLast(grid[scrollTop])
                scrollbackWrapped.addLast(gridWrapped[scrollTop])
                while (scrollback.size > maxScrollback) {
                    scrollback.removeFirst()
                    scrollbackWrapped.removeFirst()
                }
            }
            // Shift [scrollTop+1..scrollBottom] → [scrollTop..scrollBottom-1]
            for (r in scrollTop until scrollBottom) {
                grid[r] = grid[r + 1]
                gridWrapped[r] = gridWrapped[r + 1]
            }
            grid[scrollBottom] = blankLine()
            gridWrapped[scrollBottom] = false
        }
    }

    fun scrollDown(n: Int) {
        repeat(n.coerceIn(0, scrollBottom - scrollTop + 1)) {
            for (r in scrollBottom downTo scrollTop + 1) {
                grid[r] = grid[r - 1]
                gridWrapped[r] = gridWrapped[r - 1]
            }
            grid[scrollTop] = blankLine()
            gridWrapped[scrollTop] = false
        }
    }

    // ── Erase ──────────────────────────────────────────────────────────────
    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in (cursorRow + 1) until rows) { grid[r] = blankLine(); gridWrapped[r] = false }
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) { grid[r] = blankLine(); gridWrapped[r] = false }
            }
            2 -> for (r in 0 until rows) { grid[r] = blankLine(); gridWrapped[r] = false }
            3 -> {
                for (r in 0 until rows) { grid[r] = blankLine(); gridWrapped[r] = false }
                scrollback.clear()
                scrollbackWrapped.clear()
            }
        }
    }

    fun eraseInLine(mode: Int) {
        if (cursorRow !in 0 until rows) return
        when (mode) {
            0 -> { for (c in cursorCol until cols) grid[cursorRow][c] = TerminalCell.BLANK; gridWrapped[cursorRow] = false }
            1 -> for (c in 0..minOf(cursorCol, cols - 1)) grid[cursorRow][c] = TerminalCell.BLANK
            2 -> { grid[cursorRow] = blankLine(); gridWrapped[cursorRow] = false }
        }
    }

    fun eraseCharacters(n: Int) {
        if (cursorRow !in 0 until rows) return
        val end = minOf(cursorCol + n, cols)
        for (c in cursorCol until end) grid[cursorRow][c] = TerminalCell.BLANK
    }

    // ── Insert / delete lines & chars ──────────────────────────────────────
    fun insertLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val count = minOf(n, scrollBottom - cursorRow + 1)
        repeat(count) {
            for (r in scrollBottom downTo cursorRow + 1) { grid[r] = grid[r - 1]; gridWrapped[r] = gridWrapped[r - 1] }
            grid[cursorRow] = blankLine()
            gridWrapped[cursorRow] = false
        }
    }

    fun deleteLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val count = minOf(n, scrollBottom - cursorRow + 1)
        repeat(count) {
            for (r in cursorRow until scrollBottom) { grid[r] = grid[r + 1]; gridWrapped[r] = gridWrapped[r + 1] }
            grid[scrollBottom] = blankLine()
            gridWrapped[scrollBottom] = false
        }
    }

    fun insertCharacters(n: Int) {
        if (cursorRow !in 0 until rows) return
        val count = minOf(n, cols - cursorCol)
        val row = grid[cursorRow]
        repeat(count) {
            for (c in cols - 1 downTo cursorCol + 1) row[c] = row[c - 1]
            row[cursorCol] = TerminalCell.BLANK
        }
    }

    fun deleteCharacters(n: Int) {
        if (cursorRow !in 0 until rows) return
        val count = minOf(n, cols - cursorCol)
        val row = grid[cursorRow]
        repeat(count) {
            for (c in cursorCol until cols - 1) row[c] = row[c + 1]
            row[cols - 1] = TerminalCell.BLANK
        }
    }

    // ── Scroll region ──────────────────────────────────────────────────────
    fun setScrollRegion(top: Int, bottom: Int) {
        val t = maxOf(0, top - 1)
        val b = minOf(rows - 1, bottom - 1)
        if (t >= b) return
        scrollTop = t; scrollBottom = b
    }

    fun resetScrollRegion() { scrollTop = 0; scrollBottom = rows - 1 }

    // ── Resize ─────────────────────────────────────────────────────────────

    /**
     * Resizes to [newCols] × [newRows]. With history (the primary screen) the text is kept: a new width re-breaks the
     * lines ([reflow]), a new height keeps the cursor line visible by moving rows to and from the history. Without
     * history (the alternate screen) the top-left part is kept and the program is expected to redraw.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        when {
            !scrollbackEnabled -> truncate(newCols, newRows)
            newCols == cols -> resizeHeight(newRows)
            else -> reflow(newCols, newRows)
        }
        scrollTop = 0
        scrollBottom = rows - 1
        savedCursorCol = clampCol(savedCursorCol)
        savedCursorRow = clampRow(savedCursorRow)
        wrapPending = false
    }

    private fun truncate(newCols: Int, newRows: Int) {
        val newGrid = Array(newRows) { r ->
            Array(newCols) { c -> if (r < rows && c < cols) grid[r][c] else TerminalCell.BLANK }
        }
        val newWrapped = BooleanArray(newRows) { it < rows && gridWrapped[it] && newCols == cols }
        grid = newGrid
        gridWrapped = newWrapped
        fitTabStops(newCols)
        cols = newCols; rows = newRows
        cursorCol = minOf(cursorCol, newCols - 1)
        cursorRow = minOf(cursorRow, newRows - 1)
    }

    private fun isBlankCell(c: TerminalCell) =
        (c.char == ' '.code || c.char == 0) && c.extra == null && c.background == TerminalColor.Default &&
            !c.attributes.has(TextAttributes.INVERSE)

    private fun isBlankRow(row: Array<TerminalCell>) = row.all(::isBlankCell)

    /** Only the height changed: blank rows below the cursor go first, then the top rows move into the history. */
    private fun resizeHeight(newRows: Int) {
        if (newRows < rows) {
            var excess = rows - newRows
            var bottom = rows - 1
            var blanksBelow = 0
            while (excess > 0 && bottom > cursorRow && isBlankRow(grid[bottom]) && !gridWrapped[bottom - 1]) {
                bottom--; excess--; blanksBelow++
            }
            for (r in 0 until excess) {
                scrollback.addLast(grid[r])
                scrollbackWrapped.addLast(gridWrapped[r])
            }
            while (scrollback.size > maxScrollback) { scrollback.removeFirst(); scrollbackWrapped.removeFirst() }
            val kept = rows - blanksBelow - excess
            val newGrid = Array(newRows) { r -> if (r < kept) grid[excess + r] else blankLine() }
            val newWrapped = BooleanArray(newRows) { r -> r < kept && gridWrapped[excess + r] }
            grid = newGrid
            gridWrapped = newWrapped
            cursorRow = maxOf(0, cursorRow - excess)
        } else {
            val pull = minOf(newRows - rows, scrollback.size)
            val pulled = ArrayList<Array<TerminalCell>>(pull)
            val pulledWrapped = ArrayList<Boolean>(pull)
            repeat(pull) {
                pulled.add(0, scrollback.removeLast())
                pulledWrapped.add(0, scrollbackWrapped.removeLast())
            }
            val newGrid = Array(newRows) { r ->
                when {
                    r < pull -> pulled[r]
                    r - pull < rows -> grid[r - pull]
                    else -> blankLine()
                }
            }
            val newWrapped = BooleanArray(newRows) { r ->
                when {
                    r < pull -> pulledWrapped[r]
                    r - pull < rows -> gridWrapped[r - pull]
                    else -> false
                }
            }
            grid = newGrid
            gridWrapped = newWrapped
            cursorRow += pull
        }
        rows = newRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
    }

    /** The width changed: join the soft-wrapped rows into lines, break them again at the new width, keep the cursor line in view. */
    private fun reflow(newCols: Int, newRows: Int) {
        val srcRows = ArrayList<Array<TerminalCell>>(scrollback.size + rows)
        val srcWrap = ArrayList<Boolean>(scrollback.size + rows)
        scrollback.forEachIndexed { i, row -> srcRows.add(row); srcWrap.add(scrollbackWrapped[i]) }
        for (r in 0 until rows) { srcRows.add(grid[r]); srcWrap.add(gridWrapped[r]) }
        val cursorSrcRow = scrollback.size + cursorRow

        // Blank rows below the cursor are not content.
        var last = srcRows.size - 1
        while (last > cursorSrcRow && !srcWrap[last] && isBlankRow(srcRows[last]) && !(last > 0 && srcWrap[last - 1])) last--

        val lines = ArrayList<List<TerminalCell>>()
        var current = ArrayList<TerminalCell>()
        var cursorLine = 0
        var cursorOffset = 0
        for (i in 0..last) {
            val row = srcRows[i]
            val wrapped = srcWrap[i]
            var used = row.size
            if (wrapped) {
                // The empty cell a wide character left at the end of a wrapped row is not text.
                if (used > 0 && row[used - 1] === TerminalCell.WRAP_PAD) used--
            } else {
                while (used > 0 && isBlankCell(row[used - 1])) used--
            }
            if (i == cursorSrcRow) {
                cursorLine = lines.size
                cursorOffset = current.size + cursorCol
                used = maxOf(used, minOf(cursorCol, row.size))
            }
            for (c in 0 until used) current.add(if (c < row.size) row[c] else TerminalCell.BLANK)
            if (i == cursorSrcRow) while (current.size < cursorOffset) current.add(TerminalCell.BLANK)
            if (!wrapped) { lines.add(current); current = ArrayList() }
        }
        if (current.isNotEmpty()) lines.add(current)

        val outRows = ArrayList<Array<TerminalCell>>()
        val outWrap = ArrayList<Boolean>()
        var newCursorRow = 0
        var newCursorCol = 0
        for ((li, cells) in lines.withIndex()) {
            val firstRow = outRows.size
            if (cells.isEmpty()) {
                outRows.add(Array(newCols) { TerminalCell.BLANK }); outWrap.add(false)
                if (li == cursorLine) { newCursorRow = firstRow; newCursorCol = 0 }
                continue
            }
            var start = 0
            while (start < cells.size) {
                var end = minOf(start + newCols, cells.size)
                // Never split a wide character from its trailing half.
                if (end < cells.size && cells[end].isWideTrailer && end - 1 > start) end--
                val more = end < cells.size
                val row = Array(newCols) { c -> if (start + c < end) cells[start + c] else TerminalCell.BLANK }
                if (more && end - start < newCols) row[end - start] = TerminalCell.WRAP_PAD
                if (li == cursorLine && (cursorOffset in start until end || (!more && cursorOffset >= start))) {
                    newCursorRow = outRows.size
                    newCursorCol = minOf(cursorOffset - start, newCols - 1)
                }
                outRows.add(row)
                outWrap.add(more)
                start = end
            }
        }
        if (outRows.isEmpty()) { outRows.add(Array(newCols) { TerminalCell.BLANK }); outWrap.add(false) }

        val total = outRows.size
        var windowStart = maxOf(0, total - newRows)
        if (newCursorRow < windowStart) windowStart = newCursorRow
        scrollback.clear()
        scrollbackWrapped.clear()
        val historyFrom = maxOf(0, windowStart - maxScrollback)
        for (i in historyFrom until windowStart) { scrollback.addLast(outRows[i]); scrollbackWrapped.addLast(outWrap[i]) }
        grid = Array(newRows) { r ->
            val i = windowStart + r
            if (i < total) outRows[i] else Array(newCols) { TerminalCell.BLANK }
        }
        gridWrapped = BooleanArray(newRows) { r ->
            val i = windowStart + r
            // The last visible row cannot continue into a row that is not there.
            i < total && outWrap[i] && i + 1 < total && r + 1 < newRows
        }
        fitTabStops(newCols)
        cols = newCols
        rows = newRows
        cursorRow = (newCursorRow - windowStart).coerceIn(0, newRows - 1)
        cursorCol = newCursorCol.coerceIn(0, newCols - 1)
    }

    // ── Tabs ───────────────────────────────────────────────────────────────
    // A stop every 8 columns until a program sets its own (HTS) or clears them (TBC).
    private var tabStops: BooleanArray = BooleanArray(cols) { it % 8 == 0 }

    private fun fitTabStops(newCols: Int) {
        val old = tabStops
        tabStops = BooleanArray(newCols) { if (it < old.size) old[it] else it % 8 == 0 }
    }

    fun tabForward(count: Int = 1) {
        repeat(count.coerceAtLeast(1)) {
            var next = cursorCol + 1
            while (next < cols - 1 && !tabStops[next]) next++
            cursorCol = minOf(next, cols - 1)
        }
        wrapPending = false
    }

    /** CBT: back to the previous tab stop (or the left edge). */
    fun tabBackward(count: Int = 1) {
        repeat(count.coerceAtLeast(1)) {
            var prev = cursorCol - 1
            while (prev > 0 && !tabStops[prev]) prev--
            cursorCol = maxOf(prev, 0)
        }
        wrapPending = false
    }

    fun setTabStop() { if (cursorCol in tabStops.indices) tabStops[cursorCol] = true }
    fun clearTabStop() { if (cursorCol in tabStops.indices) tabStops[cursorCol] = false }
    fun clearAllTabStops() { tabStops.fill(false) }
    fun resetTabStops() { tabStops = BooleanArray(cols) { it % 8 == 0 } }

    private fun clampCol(c: Int) = maxOf(0, minOf(c, cols - 1))
    private fun clampRow(r: Int) = maxOf(0, minOf(r, rows - 1))

    companion object {
        /** Combining marks and joiners one cell can carry; more than this is not text. */
        private const val MAX_EXTRA = 8

        /** The display width of a code point: 0 (combining), 1 or 2 (East Asian wide, emoji). See [WcWidth]. */
        fun characterWidth(cp: Int): Int = WcWidth.width(cp)
    }
}
