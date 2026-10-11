package com.openminis.app.ui.terminal.emulator

/**
 * Web links in selected terminal text. Only http and https are ever offered to open: a terminal prints
 * whatever a program sends, so other schemes (`intent:`, `file:`, `tel:`) must not become one tap away.
 */
object TerminalLinks {
    private val link = Regex("""^https?://[^\s<>"']+$""", RegexOption.IGNORE_CASE)

    /** [text] trimmed and stripped of trailing sentence punctuation, when it is exactly one web link; else null. */
    fun webLink(text: String): String? {
        val trimmed = text.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        return if (trimmed.length <= MAX_LENGTH && link.matches(trimmed)) trimmed else null
    }

    private const val MAX_LENGTH = 2048
}
