package com.openminis.app.ui.terminal.emulator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

/**
 * Core terminal emulator — connects AnsiParser → TerminalBuffer, handles every CSI/ESC/OSC
 * sequence. Port of iOS TerminalEmulator.swift.
 *
 * Bump [version] for Compose redraws; each [feed] batches a single bump.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {
    private val _version = mutableStateOf(0L)
    val version: State<Long> = _version

    val primaryBuffer = TerminalBuffer(cols, rows, maxScrollback = 2000)
    val alternateBuffer = TerminalBuffer(cols, rows, maxScrollback = 0).also { it.scrollbackEnabled = false }
    var isAlternateActive: Boolean = false
        private set

    val activeBuffer: TerminalBuffer
        get() = if (isAlternateActive) alternateBuffer else primaryBuffer

    private val parser = AnsiParser()

    var applicationCursorKeys: Boolean = false
        private set
    var autoWrap: Boolean = true
        private set
    var cursorVisible: Boolean = true
        private set
    var bracketedPaste: Boolean = false
        private set

    /** Mouse reporting a program asked for (DECSET 1000/1002/1003); touch is sent to it instead of scrolling. */
    var mouseTracking: MouseTracking = MouseTracking.OFF
        private set

    /** DECSET 1006: report with the SGR encoding (no coordinate limit). */
    var mouseSgr: Boolean = false
        private set
    var applicationKeypad: Boolean = false
        private set
    var originMode: Boolean = false
        private set
    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    private val _title = mutableStateOf("")

    /** The title the program set with OSC 0 / OSC 2 (a shell's `user@host: dir`, an editor's file name); empty until one is set. */
    val title: String get() = _title.value

    private companion object {
        const val MAX_TITLE = 64
        const val MAX_CLIPBOARD_BYTES = 100_000
        const val MAX_LINK_LENGTH = 2048
    }

    private val statusStore = ProgramStatusStore()
    private val _programStatus = mutableStateOf<List<StatusRecord>>(emptyList())

    /** OSC 7501 records; they belong to the terminal, so alternate-screen switches leave them alone. */
    val programStatus: State<List<StatusRecord>> = _programStatus

    /** Process exit: working, blocked and idle records go, done and error stay (OSC 7501 lifetime). */
    fun onProcessExit() {
        // A program that died while holding the mouse must not leave touch swallowed by nobody.
        mouseTracking = MouseTracking.OFF
        publishStatus(statusStore.dropTransient())
    }

    fun effectiveApp(record: StatusRecord): String? = statusStore.effectiveApp(record)

    private fun publishStatus(changed: Boolean) {
        if (changed) _programStatus.value = statusStore.snapshot
    }

    /** Callback for terminal responses (DSR, DA). */
    var onResponse: ((ByteArray) -> Unit)? = null

    private val cursorStyle = CursorStyle()

    var scrollOffset: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, activeBuffer.scrollback.size)
            if (field != clamped) {
                field = clamped
                _version.value = _version.value + 1
            }
        }

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    /** Feed raw bytes from the PTY. */
    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        parser.feed(bytes, len) { handleAction(it) }
        _version.value = _version.value + 1
    }

    /** For each row [visibleLines] returns: whether it soft-wrapped into the row after it. */
    fun visibleWrapped(): List<Boolean> {
        val buf = activeBuffer
        if (scrollOffset == 0) return buf.gridWrapped.toList()
        val scrollbackCount = buf.scrollback.size
        val totalLines = scrollbackCount + buf.grid.size
        val viewStart = maxOf(0, totalLines - rows - scrollOffset)
        return (viewStart until viewStart + rows).map { i ->
            if (i < scrollbackCount) buf.scrollbackWrapped.elementAt(i)
            else buf.gridWrapped.getOrElse(i - scrollbackCount) { false }
        }
    }

    /** Return exactly [rows] lines to display, applying scrollOffset. */
    fun visibleLines(): List<Array<TerminalCell>> {
        val buf = activeBuffer
        if (scrollOffset == 0) return buf.grid.toList()
        val scrollbackCount = buf.scrollback.size
        val totalLines = scrollbackCount + buf.grid.size
        val viewStart = maxOf(0, totalLines - rows - scrollOffset)
        val result = ArrayList<Array<TerminalCell>>(rows)
        for (i in viewStart until viewStart + rows) {
            when {
                i < scrollbackCount -> {
                    val line = buf.scrollback.elementAt(i)
                    val padded = if (line.size < cols) {
                        Array(cols) { idx -> if (idx < line.size) line[idx] else TerminalCell.BLANK }
                    } else if (line.size > cols) {
                        Array(cols) { idx -> line[idx] }
                    } else line
                    result.add(padded)
                }
                else -> {
                    val gridRow = i - scrollbackCount
                    result.add(
                        if (gridRow < buf.grid.size) buf.grid[gridRow]
                        else Array(cols) { TerminalCell.BLANK }
                    )
                }
            }
        }
        return result
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        cols = newCols; rows = newRows
        primaryBuffer.resize(newCols, newRows)
        alternateBuffer.resize(newCols, newRows)
        _version.value = _version.value + 1
    }

    /**
     * The visible screen as plain text for a reader that is not looking at the canvas: one line per row, trailing
     * blanks removed, blank rows at the bottom dropped. [scrollbackLines] more lines from above the screen come first.
     */
    fun screenText(scrollbackLines: Int = 0): String {
        val buf = activeBuffer
        fun rowText(row: Array<TerminalCell>): String {
            val sb = StringBuilder(row.size)
            for (cell in row) {
                if (cell.isWideTrailer) continue
                sb.append(cell.text())
            }
            return sb.toString().trimEnd()
        }
        val lines = ArrayList<String>()
        if (scrollbackLines > 0 && !isAlternateActive) {
            val history = buf.scrollback
            for (i in maxOf(0, history.size - scrollbackLines) until history.size) lines.add(rowText(history.elementAt(i)))
        }
        for (row in buf.grid) lines.add(rowText(row))
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    /** Current cursor position (in active buffer). */
    fun cursorPos(): Pair<Int, Int> = activeBuffer.cursorCol to activeBuffer.cursorRow

    fun currentCursorStyle(): CursorStyle = cursorStyle.copy()

    // ── Text selection (T194 groundwork) ───────────────────────────────────
    // Coordinates are viewport-relative (0..cols-1 / 0..rows-1) — i.e. they
    // index into [visibleLines]. The TerminalView gesture handler converts
    // pixel positions to (col,row) and feeds them straight in. Mirrors the
    // Termux terminal-view selection model, but reads from our own buffer
    // instead of bringing in com.termux.terminal.TerminalBuffer.
    //
    // We deliberately keep this state inside the emulator (rather than the
    // view) so renderer + selection-text-extract see a consistent snapshot
    // and a redraw triggered by [setSelectionRect] flows through the
    // existing `version` State the Compose canvas already observes.

    /** Active selection rect, or null. (col1,row1) is start, (col2,row2) is end. */
    private val _selectionRect = mutableStateOf<IntArray?>(null)
    val selectionRect: State<IntArray?> = _selectionRect

    fun setSelectionRect(col1: Int, row1: Int, col2: Int, row2: Int) {
        _selectionRect.value = intArrayOf(col1, row1, col2, row2)
        _version.value = _version.value + 1
    }

    fun clearSelectionRect() {
        if (_selectionRect.value != null) {
            _selectionRect.value = null
            _version.value = _version.value + 1
        }
    }

    /**
     * Extract text from the viewport for the given selection rect, in the
     * order start → end. Coordinates are clamped to [0,cols) × [0,rows). If
     * start is after end (in reading order), they are swapped automatically.
     * Trailing spaces on each line are trimmed (matches Termux behaviour and
     * what users expect when they grab a URL by long-pressing).
     */
    fun getSelectedText(col1: Int, row1: Int, col2: Int, row2: Int): String {
        val lines = visibleLines()
        if (lines.isEmpty()) return ""
        val wrapped = visibleWrapped()

        // Normalize: ensure (sx,sy) ≤ (ex,ey) in reading order.
        val (sx, sy, ex, ey) = if (row1 < row2 || (row1 == row2 && col1 <= col2)) {
            intArrayOf(col1, row1, col2, row2)
        } else {
            intArrayOf(col2, row2, col1, row1)
        }.let { listOf(it[0], it[1], it[2], it[3]) }

        val maxRow = lines.size - 1
        val ry1 = sy.coerceIn(0, maxRow)
        val ry2 = ey.coerceIn(0, maxRow)

        val sb = StringBuilder()
        for (r in ry1..ry2) {
            val row = lines[r]
            val w = row.size
            val cStart = if (r == ry1) sx.coerceIn(0, w) else 0
            val cEnd = if (r == ry2) (ex + 1).coerceIn(0, w) else w
            val lineSb = StringBuilder()
            for (c in cStart until cEnd) {
                val cell = row[c]
                if (cell.char != 0 && !cell.isWideTrailer) lineSb.append(cell.text())
            }
            // A row that soft-wrapped into the next is one line of text: no trailing blanks cut, no line break.
            val joinsNext = r < ry2 && wrapped.getOrElse(r) { false }
            var end = lineSb.length
            if (!joinsNext) while (end > 0 && lineSb[end - 1] == ' ') end--
            sb.append(lineSb, 0, end)
            if (r < ry2 && !joinsNext) sb.append('\n')
        }
        return sb.toString()
    }

    // ────────────────────────────────────────────────────────────────────────
    private fun handleAction(action: ParsedAction) {
        when (action) {
            is ParsedAction.Printable -> printChar(action.codePoint)
            is ParsedAction.ControlChar -> handleControlChar(action.byte)
            is ParsedAction.CsiDispatch -> handleCsi(action.params, action.intermediate, action.finalByte, action.colon)
            is ParsedAction.EscDispatch -> handleEsc(action.char, action.intermediate)
            is ParsedAction.OscDispatch -> handleOsc(action.command, action.payload)
        }
    }

    /** Called when a program rings the bell (BEL outside a string); the view decides how to signal it. */
    var onBell: (() -> Unit)? = null

    /** Insert mode (IRM, CSI 4 h): a printed character pushes the rest of the line right instead of overwriting. */
    private var insertMode = false

    // Character sets: G0 and G1 are ASCII or DEC special graphics (line drawing, `ESC ( 0`); SO / SI pick which one prints.
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftedToG1 = false
    private var lastPrinted = 0

    private fun printChar(codePoint: Int) {
        val graphics = if (shiftedToG1) g1Graphics else g0Graphics
        val cp = if (graphics) DecSpecialGraphics.map(codePoint) else codePoint
        lastPrinted = cp
        val buf = activeBuffer
        if (insertMode) buf.insertCharacters(TerminalBuffer.characterWidth(cp))
        buf.writeChar(cp, cursorStyle, autoWrap)
    }

    private fun handleControlChar(byte: Int) {
        val buf = activeBuffer
        when (byte) {
            0x07 -> onBell?.invoke()
            0x08 -> buf.moveCursorBackward(1)
            0x09 -> buf.tabForward()
            0x0A, 0x0B, 0x0C -> buf.lineFeed()
            0x0D -> buf.carriageReturn()
            0x0E -> shiftedToG1 = true
            0x0F -> shiftedToG1 = false
        }
    }

    private fun handleCsi(params: IntArray, intermediate: Char?, finalByte: Char, colon: BooleanArray = NO_COLON) {
        val buf = activeBuffer
        if (intermediate == '?') {
            when (finalByte) {
                'h', 'l' -> handleDecPrivateMode(params, set = (finalByte == 'h'))
                // DECXCPR: the cursor position again, in the private form some programs ask for.
                'n' -> when (params.getOrElse(0) { 0 }) {
                    6 -> respond("\u001B[?${buf.cursorRow + 1};${buf.cursorCol + 1}R")
                    15 -> respond("\u001B[?13n")
                }
                // DECRQM: is this DEC private mode set (1), reset (2) or not known (0)? A program that asks
                // before it uses a mode waits for the answer, so a silent terminal stalls its start-up.
                'p' -> for (ps in params) respond("\u001B[?$ps;${decModeReport(ps)}$" + "y")
            }
            return
        }
        if (intermediate == '>') {
            when (finalByte) {
                'c' -> respond("\u001B[>0;0;0c")
                // XTVERSION: who is this terminal. Programs gate features on the name.
                'q' -> respond("\u001BP>|Minis\u001B\\")
            }
            return
        }
        // DECSTR: soft reset (modes and attributes back to defaults, the screen kept).
        if (intermediate == '!' && finalByte == 'p') {
            softReset()
            return
        }

        when (finalByte) {
            'A' -> buf.moveCursorUp(maxOf(1, params.getOrElse(0) { 1 }))
            'B' -> buf.moveCursorDown(maxOf(1, params.getOrElse(0) { 1 }))
            'C' -> buf.moveCursorForward(maxOf(1, params.getOrElse(0) { 1 }))
            'D' -> buf.moveCursorBackward(maxOf(1, params.getOrElse(0) { 1 }))
            'E' -> { buf.moveCursorDown(maxOf(1, params.getOrElse(0) { 1 })); buf.carriageReturn() }
            'F' -> { buf.moveCursorUp(maxOf(1, params.getOrElse(0) { 1 })); buf.carriageReturn() }
            'G' -> buf.moveCursorToColumn(maxOf(1, params.getOrElse(0) { 1 }) - 1)
            'H', 'f' -> {
                val row = maxOf(1, params.getOrElse(0) { 1 })
                val col = maxOf(1, params.getOrElse(1) { 1 })
                if (originMode) buf.moveCursorTo(col - 1, buf.scrollTop + row - 1)
                else buf.moveCursorTo(col - 1, row - 1)
            }
            'd' -> {
                val row = maxOf(1, params.getOrElse(0) { 1 })
                buf.moveCursorTo(buf.cursorCol, row - 1)
            }
            'J' -> buf.eraseInDisplay(params.getOrElse(0) { 0 })
            'K' -> buf.eraseInLine(params.getOrElse(0) { 0 })
            'X' -> buf.eraseCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'L' -> buf.insertLines(maxOf(1, params.getOrElse(0) { 1 }))
            'M' -> buf.deleteLines(maxOf(1, params.getOrElse(0) { 1 }))
            '@' -> buf.insertCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'P' -> buf.deleteCharacters(maxOf(1, params.getOrElse(0) { 1 }))
            'S' -> buf.scrollUp(maxOf(1, params.getOrElse(0) { 1 }))
            'T' -> buf.scrollDown(maxOf(1, params.getOrElse(0) { 1 }))
            'r' -> {
                val top = params.getOrElse(0) { 1 }
                val bottom = params.getOrElse(1) { rows }
                buf.setScrollRegion(top, bottom)
                if (originMode) buf.moveCursorTo(0, buf.scrollTop) else buf.moveCursorTo(0, 0)
            }
            'm' -> if (params.isEmpty()) handleSgr(intArrayOf(0), NO_COLON) else handleSgr(params, colon)
            // CBT: back tab. REP: repeat the last character. HPA / HPR / VPR: absolute and relative cursor moves.
            'Z' -> buf.tabBackward(maxOf(1, params.getOrElse(0) { 1 }))
            'b' -> if (lastPrinted != 0) repeat(params.getOrElse(0) { 1 }.coerceIn(1, 2000)) { printChar(lastPrinted) }
            '`' -> buf.moveCursorToColumn(maxOf(1, params.getOrElse(0) { 1 }) - 1)
            'a' -> buf.moveCursorForward(maxOf(1, params.getOrElse(0) { 1 }))
            'e' -> buf.moveCursorDown(maxOf(1, params.getOrElse(0) { 1 }))
            // TBC: clear the tab stop here (0) or all of them (3).
            'g' -> when (params.getOrElse(0) { 0 }) {
                0 -> buf.clearTabStop()
                3 -> buf.clearAllTabStops()
            }
            // The ANSI (not DEC private) modes: only insert mode matters here.
            'h', 'l' -> for (p in params) if (p == 4) insertMode = (finalByte == 'h')
            'n' -> {
                val code = params.getOrElse(0) { 0 }
                when (code) {
                    6 -> {
                        val r = buf.cursorRow + 1
                        val c = buf.cursorCol + 1
                        onResponse?.invoke("\u001B[$r;${c}R".toByteArray(Charsets.UTF_8))
                    }
                    5 -> onResponse?.invoke("\u001B[0n".toByteArray(Charsets.UTF_8))
                }
            }
            'c' -> if ((params.getOrElse(0) { 0 }) == 0) {
                onResponse?.invoke("\u001B[?62;22c".toByteArray(Charsets.UTF_8))
            }
            // XTWINOPS size reports: text area and screen in characters, text area and one cell in pixels.
            't' -> when (params.getOrElse(0) { 0 }) {
                18 -> respond("\u001B[8;$rows;${cols}t")
                19 -> respond("\u001B[9;$rows;${cols}t")
                14 -> respond("\u001B[4;${rows * cellHeightPx};${cols * cellWidthPx}t")
                16 -> respond("\u001B[6;$cellHeightPx;${cellWidthPx}t")
            }
            's' -> buf.saveCursor(cursorStyle)
            'u' -> cursorStyle.copyFrom(buf.restoreCursor())
            'I' -> buf.tabForward(params.getOrElse(0) { 1 }.coerceIn(1, cols))
            'q' -> if (intermediate == ' ') {
                when (params.getOrElse(0) { 1 }) {
                    0, 1, 2 -> cursorShape = CursorShape.BLOCK
                    3, 4 -> cursorShape = CursorShape.UNDERLINE
                    5, 6 -> cursorShape = CursorShape.BAR
                }
            }
            else -> {}
        }
    }

    private fun rgbReport(c: androidx.compose.ui.graphics.Color): String {
        fun hex(v: Float) = "%04x".format((v * 255f + 0.5f).toInt().coerceIn(0, 255) * 257)
        return "rgb:${hex(c.red)}/${hex(c.green)}/${hex(c.blue)}"
    }

    /**
     * Called with text a program asked to put on the clipboard (OSC 52). The view decides whether to honour it; with no
     * callback the request is dropped.
     */
    var onClipboardSet: ((String) -> Unit)? = null

    private fun setClipboard(payload: String) {
        val semi = payload.indexOf(';')
        if (semi < 0) return
        val data = payload.substring(semi + 1)
        // "?" asks for the clipboard back: a terminal that answered would let any program read what the user copied.
        if (data == "?" || data.isEmpty()) return
        val bytes = try { java.util.Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { return }
        if (bytes.size > MAX_CLIPBOARD_BYTES) return
        val text = String(bytes, Charsets.UTF_8)
        if (text.isNotEmpty()) onClipboardSet?.invoke(text)
    }

    private fun setHyperlink(payload: String) {
        // "params;uri": the params (an id) are not needed. An empty uri ends the link.
        val uri = payload.substringAfter(';', "")
        cursorStyle.link = if (TerminalLinks.webLink(uri) != null && uri.length <= MAX_LINK_LENGTH) uri else null
    }

    private fun respond(text: String) {
        onResponse?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    /** Pixel size of one cell for the size reports; the view sets the real value, a headless terminal keeps this guess. */
    var cellWidthPx: Int = 8
    var cellHeightPx: Int = 16

    private fun decModeReport(ps: Int): Int {
        val set: Boolean? = when (ps) {
            1 -> applicationCursorKeys
            6 -> originMode
            7 -> autoWrap
            25 -> cursorVisible
            47, 1047, 1049 -> isAlternateActive
            1000 -> mouseTracking == MouseTracking.BUTTON
            1002 -> mouseTracking == MouseTracking.DRAG
            1003 -> mouseTracking == MouseTracking.ANY
            1006 -> mouseSgr
            2004 -> bracketedPaste
            else -> null
        }
        return when (set) { true -> 1; false -> 2; null -> 0 }
    }

    private fun handleDecPrivateMode(params: IntArray, set: Boolean) {
        for (p in params) when (p) {
            1 -> applicationCursorKeys = set
            6 -> {
                originMode = set
                if (set) activeBuffer.moveCursorTo(0, activeBuffer.scrollTop)
                else activeBuffer.moveCursorTo(0, 0)
            }
            7 -> autoWrap = set
            12 -> {}
            25 -> cursorVisible = set
            47, 1047 -> switchBuffer(set)
            1048 -> if (set) activeBuffer.saveCursor(cursorStyle)
                    else cursorStyle.copyFrom(activeBuffer.restoreCursor())
            1049 -> if (set) {
                primaryBuffer.saveCursor(cursorStyle)
                switchBuffer(true)
                activeBuffer.eraseInDisplay(2)
            } else {
                switchBuffer(false)
                cursorStyle.copyFrom(primaryBuffer.restoreCursor())
            }
            1000 -> mouseTracking = if (set) MouseTracking.BUTTON else MouseTracking.OFF
            1002 -> mouseTracking = if (set) MouseTracking.DRAG else MouseTracking.OFF
            1003 -> mouseTracking = if (set) MouseTracking.ANY else MouseTracking.OFF
            1006 -> mouseSgr = set
            2004 -> bracketedPaste = set
        }
    }

    private fun handleSgr(params: IntArray, colon: BooleanArray) {
        var i = 0
        while (i < params.size) {
            val p = params[i]
            // Sub-parameters written with colons belong to this parameter (4:3, 38:2::r:g:b).
            var groupEnd = i + 1
            while (groupEnd < params.size && colon.getOrElse(groupEnd) { false }) groupEnd++
            val sub = if (groupEnd > i + 1) params.copyOfRange(i + 1, groupEnd) else null
            if (sub != null) {
                when (p) {
                    // 4:0 no underline; 4:1..5 single, double, curly, dotted, dashed: all drawn as an underline.
                    4 -> cursorStyle.attributes = if (sub[0] == 0) cursorStyle.attributes.without(TextAttributes.UNDERLINE)
                        else cursorStyle.attributes.with(TextAttributes.UNDERLINE)
                    38, 48 -> colonColor(sub)?.let { c -> if (p == 38) cursorStyle.foreground = c else cursorStyle.background = c }
                    // 58 is the underline colour: not drawn, but its arguments must not be read as attributes.
                }
                i = groupEnd
                continue
            }
            when (p) {
                0 -> { cursorStyle.foreground = TerminalColor.Default; cursorStyle.background = TerminalColor.Default; cursorStyle.attributes = TextAttributes() }
                1 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.BOLD)
                2 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.DIM)
                3 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.ITALIC)
                4, 21 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.UNDERLINE)
                5 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.BLINK)
                7 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.INVERSE)
                8 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.HIDDEN)
                9 -> cursorStyle.attributes = cursorStyle.attributes.with(TextAttributes.STRIKETHROUGH)
                22 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.BOLD).without(TextAttributes.DIM)
                23 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.ITALIC)
                24 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.UNDERLINE)
                25 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.BLINK)
                27 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.INVERSE)
                28 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.HIDDEN)
                29 -> cursorStyle.attributes = cursorStyle.attributes.without(TextAttributes.STRIKETHROUGH)
                in 30..37 -> cursorStyle.foreground = TerminalColor.Indexed(p - 30)
                38 -> i = parseSgrColor(params, i, isForeground = true)
                39 -> cursorStyle.foreground = TerminalColor.Default
                in 40..47 -> cursorStyle.background = TerminalColor.Indexed(p - 40)
                48 -> i = parseSgrColor(params, i, isForeground = false)
                49 -> cursorStyle.background = TerminalColor.Default
                // 58 sets the underline colour, 59 resets it: not drawn, but the `58;2;r;g;b` arguments are skipped.
                58 -> i = skipSgrColor(params, i)
                in 90..97 -> cursorStyle.foreground = TerminalColor.Indexed(p - 90 + 8)
                in 100..107 -> cursorStyle.background = TerminalColor.Indexed(p - 100 + 8)
            }
            i++
        }
    }

    /** The colour in `38:5:n`, `38:2:r:g:b` or `38:2::r:g:b` (an empty colour-space id), given what follows the 38. */
    private fun colonColor(sub: IntArray): TerminalColor? = when (sub[0]) {
        5 -> sub.getOrNull(1)?.let { TerminalColor.Indexed(it.coerceIn(0, 255)) }
        2 -> {
            // The colour-space id is optional: r:g:b is 4 values after the 2 only when it is present.
            val rgb = if (sub.size >= 5) sub.copyOfRange(2, 5) else if (sub.size == 4) sub.copyOfRange(1, 4) else null
            rgb?.let { TerminalColor.Rgb(it[0].coerceIn(0, 255), it[1].coerceIn(0, 255), it[2].coerceIn(0, 255)) }
        }
        else -> null
    }

    private fun skipSgrColor(params: IntArray, index: Int): Int {
        if (index + 1 >= params.size) return index
        return when (params[index + 1]) {
            5 -> minOf(index + 2, params.size - 1)
            2 -> minOf(index + 4, params.size - 1)
            else -> index
        }
    }

    private fun parseSgrColor(params: IntArray, index: Int, isForeground: Boolean): Int {
        if (index + 1 >= params.size) return index
        return when (params[index + 1]) {
            5 -> {
                if (index + 2 >= params.size) return index + 1
                val c = TerminalColor.Indexed(params[index + 2].coerceIn(0, 255))
                if (isForeground) cursorStyle.foreground = c else cursorStyle.background = c
                index + 2
            }
            2 -> {
                if (index + 4 >= params.size) return index + 1
                val c = TerminalColor.Rgb(
                    params[index + 2].coerceIn(0, 255),
                    params[index + 3].coerceIn(0, 255),
                    params[index + 4].coerceIn(0, 255),
                )
                if (isForeground) cursorStyle.foreground = c else cursorStyle.background = c
                index + 4
            }
            else -> index
        }
    }

    private fun handleEsc(char: Char, intermediate: Char? = null) {
        when (intermediate) {
            // Character set designation: `0` is DEC special graphics, anything else (B, A, ...) is plain text.
            '(' -> { g0Graphics = (char == '0'); return }
            ')' -> { g1Graphics = (char == '0'); return }
            // DECALN: the alignment test fills the screen with E.
            '#' -> { if (char == '8') fillWithE(); return }
        }
        when (char) {
            'H' -> activeBuffer.setTabStop()
            '7' -> activeBuffer.saveCursor(cursorStyle)
            '8' -> cursorStyle.copyFrom(activeBuffer.restoreCursor())
            'D' -> activeBuffer.lineFeed()
            'M' -> activeBuffer.reverseIndex()
            'E' -> { activeBuffer.carriageReturn(); activeBuffer.lineFeed() }
            'c' -> resetTerminal()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            else -> {}
        }
    }

    private fun handleOsc(command: Int, payload: String) {
        when (command) {
            0, 2 -> _title.value = payload.filter { !it.isISOControl() }.take(MAX_TITLE)
            // Colour queries: full-screen programs ask for the default colours to pick a light or dark theme.
            10, 11, 12 -> if (payload == "?") respond("\u001B]$command;${rgbReport(if (command == 11) TerminalPalette.defaultBackground else TerminalPalette.defaultForeground)}\u001B\\")
            // OSC 8: a hyperlink around the text that follows, until an empty one ends it.
            8 -> setHyperlink(payload)
            // OSC 52: a program (vim, tmux over ssh) puts text on the clipboard. Write only: a query is never answered.
            52 -> setClipboard(payload)
            133 -> if (payload == "A" || payload.startsWith("A;")) publishStatus(statusStore.dropTransient())
            1337 -> handleITermOsc(payload)
            7501 -> handleProgramStatus(payload)
            else -> {}
        }
    }

    private fun handleProgramStatus(body: String) {
        if (ProgramStatusParser.isQuery(body)) {
            // The only bytes this protocol ever writes back: the fixed feature-detection reply.
            onResponse?.invoke("\u001B]7501;?\u001B\\".toByteArray(Charsets.UTF_8))
            return
        }
        val report = ProgramStatusParser.parse(body) ?: return
        publishStatus(statusStore.apply(report))
    }

    private fun handleITermOsc(payload: String) {
        val eq = payload.indexOf('=')
        if (eq <= 0 || payload.substring(0, eq) != "MinisOpenURL") return
        val urlString = payload.substring(eq + 1)
        if (urlString.isEmpty()) return
        com.openminis.app.terminal.MinisOpenUrlBroker.offer(urlString)
    }

    private fun switchBuffer(toAlternate: Boolean) {
        if (toAlternate && !isAlternateActive) {
            isAlternateActive = true
            alternateBuffer.resize(cols, rows)
            alternateBuffer.moveCursorTo(0, 0)
        } else if (!toAlternate && isAlternateActive) {
            isAlternateActive = false
        }
    }

    private fun fillWithE() {
        val buf = activeBuffer
        for (r in 0 until buf.rows) for (c in 0 until buf.cols) buf.grid[r][c] = TerminalCell('E'.code)
        buf.moveCursorTo(0, 0)
    }

    private fun softReset() {
        cursorStyle.link = null
        cursorStyle.foreground = TerminalColor.Default
        cursorStyle.background = TerminalColor.Default
        cursorStyle.attributes = TextAttributes()
        applicationCursorKeys = false
        applicationKeypad = false
        autoWrap = true
        originMode = false
        cursorVisible = true
        insertMode = false
        g0Graphics = false
        g1Graphics = false
        shiftedToG1 = false
        activeBuffer.resetScrollRegion()
        activeBuffer.resetTabStops()
    }

    private fun resetTerminal() {
        cursorStyle.link = null
        insertMode = false
        g0Graphics = false
        g1Graphics = false
        shiftedToG1 = false
        cursorStyle.foreground = TerminalColor.Default
        cursorStyle.background = TerminalColor.Default
        cursorStyle.attributes = TextAttributes()
        applicationCursorKeys = false
        autoWrap = true
        cursorVisible = true
        bracketedPaste = false
        mouseTracking = MouseTracking.OFF
        mouseSgr = false
        applicationKeypad = false
        originMode = false
        cursorShape = CursorShape.BLOCK
        primaryBuffer.resize(cols, rows)
        primaryBuffer.eraseInDisplay(3)
        // T310: eraseInDisplay only blanks the grid; without an explicit
        // cursor-home the cursor stays wherever it was before the clear,
        // so the next PTY byte (shell echo / re-prompt) writes mid-screen
        // instead of at the top-left.
        primaryBuffer.moveCursorTo(0, 0)
        alternateBuffer.resize(cols, rows)
        alternateBuffer.eraseInDisplay(3)
        alternateBuffer.moveCursorTo(0, 0)
        isAlternateActive = false
        scrollOffset = 0
        publishStatus(statusStore.reset())
    }
}
