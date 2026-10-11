package com.openminis.app.ui.terminal.emulator

/**
 * What a reader that cannot see the screen needs besides its text: where the cursor is, which cells are
 * highlighted, what the program has asked of the terminal, and a way to search the history.
 */

/** Opens and closes a highlighted run in [screenTextMarked]; chosen because they do not occur in terminal output. */
const val MARK_OPEN = '⟦'
const val MARK_CLOSE = '⟧'

/** Where the cursor is on the screen, one-based, as a reader would say it. */
fun TerminalEmulator.cursorLine(): String {
    val (col, row) = cursorPos()
    val at = "row ${row + 1}, col ${col + 1}"
    return if (cursorVisible) "cursor $at" else "cursor hidden (at $at)"
}

/** What the program has asked of the terminal, or null when it is just a shell at a prompt. */
fun TerminalEmulator.modeLine(): String? {
    val parts = ArrayList<String>(4)
    if (isAlternateActive) parts.add("full-screen app")
    if (mouseTracking != MouseTracking.OFF) parts.add("mouse reporting")
    if (bracketedPaste) parts.add("bracketed paste")
    if (applicationCursorKeys) parts.add("application cursor keys")
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun isHighlighted(cell: TerminalCell) =
    cell.attributes.has(TextAttributes.INVERSE) || cell.background != TerminalColor.Default

/** Closes a highlight; blanks that only padded its end stay outside it. */
private fun closeMark(sb: StringBuilder) {
    var end = sb.length
    while (end > 0 && sb[end - 1] == ' ') end--
    val padding = sb.length - end
    sb.setLength(end)
    sb.append(MARK_CLOSE)
    repeat(padding) { sb.append(' ') }
}

private fun rowText(row: Array<TerminalCell>, marks: Boolean): String {
    val sb = StringBuilder(row.size + 8)
    var open = false
    for (cell in row) {
        if (cell.isWideTrailer) continue
        val blank = cell.char <= 0 || cell.char == ' '.code
        val lit = marks && isHighlighted(cell)
        if (open && !lit) {
            closeMark(sb)
            open = false
        }
        if (lit && !open && !blank) {
            sb.append(MARK_OPEN)
            open = true
        }
        sb.append(cell.text())
    }
    if (open) closeMark(sb)
    return sb.toString().trimEnd()
}

/**
 * The screen as text like [TerminalEmulator.screenText], with each highlighted run (reverse video or a coloured
 * background: the selected item of a menu, the active tab) wrapped in [MARK_OPEN] / [MARK_CLOSE] when [marks].
 * Without them a reader cannot tell which option a menu has selected.
 */
fun TerminalEmulator.screenTextMarked(scrollbackLines: Int, marks: Boolean): String {
    if (!marks) return screenText(scrollbackLines)
    val buf = activeBuffer
    val lines = ArrayList<String>()
    if (scrollbackLines > 0 && !isAlternateActive) {
        val history = buf.scrollback
        for (i in maxOf(0, history.size - scrollbackLines) until history.size) lines.add(rowText(history.elementAt(i), true))
    }
    for (row in buf.grid) lines.add(rowText(row, true))
    while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n")
}

/** One line of the history or the screen that matched, numbered from the oldest line still kept (one-based). */
class FoundLine(val number: Int, val text: String)

/** Every line of the history and the screen, oldest first; none of the history on the alternate screen. */
private fun TerminalEmulator.allLines(): List<String> {
    val buf = activeBuffer
    val out = ArrayList<String>()
    if (!isAlternateActive) for (row in buf.scrollback) out.add(rowText(row, false))
    for (row in buf.grid) out.add(rowText(row, false))
    return out
}

/** The lines of the whole history and screen that match [pattern], at most [max], newest last. */
fun TerminalEmulator.findLines(pattern: Regex, max: Int): List<FoundLine> {
    val found = ArrayList<FoundLine>()
    allLines().forEachIndexed { index, text -> if (pattern.containsMatchIn(text)) found.add(FoundLine(index + 1, text)) }
    return if (found.size > max) found.subList(found.size - max, found.size) else found
}

/** How many lines [findLines] numbers over: the history kept plus the screen. */
fun TerminalEmulator.totalLines(): Int = allLines().size
