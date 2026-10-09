package com.openminis.app.runtime.terminal

/**
 * Key names an agent can send to a real terminal ("Enter", "Escape", "Ctrl-C", "Up", "Alt-b", "F5") and the bytes
 * they stand for. Modifiers may be written `Ctrl-`, `C-`, `^`, `Alt-`, `M-`, `Meta-`, `Shift-`; names are
 * case-insensitive. Arrow keys follow the application-cursor mode the program asked for, the way a keyboard
 * would.
 */
object AgentKeyEncoder {
    class Result(val bytes: ByteArray, val unknown: List<String>)

    /** Encodes whitespace-separated key specs. Unknown specs are reported and sent as nothing. */
    fun encodeAll(specs: String, applicationCursorKeys: Boolean): Result {
        val out = java.io.ByteArrayOutputStream()
        val unknown = ArrayList<String>()
        for (spec in specs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val bytes = encode(spec, applicationCursorKeys)
            if (bytes == null) unknown.add(spec) else out.write(bytes)
        }
        return Result(out.toByteArray(), unknown)
    }

    fun encode(spec: String, applicationCursorKeys: Boolean): ByteArray? {
        var rest = spec
        var ctrl = false
        var alt = false
        var shift = false
        while (true) {
            val lower = rest.lowercase()
            when {
                lower.startsWith("ctrl-") || lower.startsWith("ctrl+") -> { ctrl = true; rest = rest.substring(5) }
                lower.startsWith("c-") -> { ctrl = true; rest = rest.substring(2) }
                rest.startsWith("^") && rest.length > 1 -> { ctrl = true; rest = rest.substring(1) }
                lower.startsWith("alt-") || lower.startsWith("alt+") -> { alt = true; rest = rest.substring(4) }
                lower.startsWith("meta-") || lower.startsWith("meta+") -> { alt = true; rest = rest.substring(5) }
                lower.startsWith("m-") -> { alt = true; rest = rest.substring(2) }
                lower.startsWith("shift-") || lower.startsWith("shift+") -> { shift = true; rest = rest.substring(6) }
                lower.startsWith("s-") && rest.length > 2 -> { shift = true; rest = rest.substring(2) }
                else -> break
            }
        }
        if (rest.isEmpty()) return null
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val named = named(rest.lowercase(), applicationCursorKeys, modifier)
        if (named != null) return if (alt && !named.modifiedByParameter(modifier)) byteArrayOf(0x1B) + named else named
        // A single character, optionally with Ctrl and/or Alt.
        val codePoints = rest.codePoints().toArray()
        if (codePoints.size != 1) return null
        var bytes = String(codePoints, 0, 1).toByteArray(Charsets.UTF_8)
        if (ctrl) {
            val c = rest[0].uppercaseChar()
            val control = when (c) {
                in 'A'..'Z' -> c - 'A' + 1
                '@', ' ' -> 0
                '[' -> 27
                '\\' -> 28
                ']' -> 29
                '^' -> 30
                '_' -> 31
                '?' -> 127
                else -> return null
            }
            bytes = byteArrayOf(control.toByte())
        } else if (shift && rest[0].isLetter()) {
            bytes = rest.uppercase().toByteArray(Charsets.UTF_8)
        }
        return if (alt) byteArrayOf(0x1B) + bytes else bytes
    }

    // Keys that carry the modifier inside their own sequence (CSI 1;<m> A) must not also get an ESC prefix.
    private fun ByteArray.modifiedByParameter(modifier: Int): Boolean =
        modifier > 1 && size > 2 && this[0] == 0x1B.toByte() && this[1] == '['.code.toByte() && any { it == ';'.code.toByte() }

    private fun csi(final: Char, modifier: Int): ByteArray =
        (if (modifier > 1) "\u001B[1;$modifier$final" else "\u001B[$final").toByteArray()

    private fun tilde(code: Int, modifier: Int): ByteArray =
        (if (modifier > 1) "\u001B[$code;$modifier~" else "\u001B[$code~").toByteArray()

    private fun named(name: String, appCursor: Boolean, modifier: Int): ByteArray? = when (name) {
        "enter", "return", "cr" -> byteArrayOf('\r'.code.toByte())
        "tab" -> if (modifier == 2) "\u001B[Z".toByteArray() else byteArrayOf('\t'.code.toByte())
        "btab", "backtab" -> "\u001B[Z".toByteArray()
        "escape", "esc" -> byteArrayOf(0x1B)
        "backspace", "bs" -> byteArrayOf(0x7F)
        "space", "spc" -> byteArrayOf(' '.code.toByte())
        "delete", "del" -> tilde(3, modifier)
        "insert", "ins" -> tilde(2, modifier)
        "pageup", "pgup", "page_up" -> tilde(5, modifier)
        "pagedown", "pgdn", "page_down" -> tilde(6, modifier)
        "home" -> csi('H', modifier)
        "end" -> csi('F', modifier)
        "up" -> arrow('A', appCursor, modifier)
        "down" -> arrow('B', appCursor, modifier)
        "right" -> arrow('C', appCursor, modifier)
        "left" -> arrow('D', appCursor, modifier)
        "f1" -> ss3('P', modifier)
        "f2" -> ss3('Q', modifier)
        "f3" -> ss3('R', modifier)
        "f4" -> ss3('S', modifier)
        "f5" -> tilde(15, modifier)
        "f6" -> tilde(17, modifier)
        "f7" -> tilde(18, modifier)
        "f8" -> tilde(19, modifier)
        "f9" -> tilde(20, modifier)
        "f10" -> tilde(21, modifier)
        "f11" -> tilde(23, modifier)
        "f12" -> tilde(24, modifier)
        else -> null
    }

    private fun arrow(final: Char, appCursor: Boolean, modifier: Int): ByteArray =
        if (modifier == 1 && appCursor) "\u001BO$final".toByteArray() else csi(final, modifier)

    private fun ss3(final: Char, modifier: Int): ByteArray =
        if (modifier > 1) "\u001B[1;$modifier$final".toByteArray() else "\u001BO$final".toByteArray()

    /**
     * Literal text typed into a terminal. Newlines are Enter. A program that asked for bracketed paste (an editor, a
     * coding agent, bash's own line editor) gets a text with newlines in the middle as ONE paste, so those newlines are
     * text and not "submit"; newlines at the very end are then Enter, pressed after the paste.
     */
    fun encodeText(text: String, bracketedPaste: Boolean): ByteArray {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val body = normalised.trimEnd('\n')
        val trailing = normalised.length - body.length
        if (bracketedPaste && body.contains('\n')) {
            return ("\u001B[200~$body\u001B[201~" + "\r".repeat(trailing)).toByteArray(Charsets.UTF_8)
        }
        return normalised.replace('\n', '\r').toByteArray(Charsets.UTF_8)
    }
}
