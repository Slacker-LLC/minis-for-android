package com.openminis.app.ui.terminal.emulator

/** Which mouse events a program asked for (DECSET 1000 / 1002 / 1003). */
enum class MouseTracking {
    /** Mouse not reported: touch scrolls the scrollback or, on the alternate screen, sends arrow keys. */
    OFF,

    /** 1000: button press and release. */
    BUTTON,

    /** 1002: also motion while a button is held. */
    DRAG,

    /** 1003: all motion. A touch screen has no hover, so it behaves like [DRAG]. */
    ANY,
}

/**
 * Mouse reports for the program running in the terminal, plus the plain-key fallbacks for a touch
 * swipe and the paste payload. Everything here is pure so the byte sequences are unit-tested.
 *
 * Coordinates are zero-based cells here and one-based on the wire.
 */
object TerminalMouse {
    const val LEFT = 0
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65
    private const val MOTION = 32

    /** SGR (1006) is unambiguous and has no coordinate limit; the legacy form stops at 223. */
    private const val LEGACY_MAX = 223

    fun press(col: Int, row: Int, sgr: Boolean): ByteArray? = encode(LEFT, col, row, release = false, sgr = sgr)

    fun release(col: Int, row: Int, sgr: Boolean): ByteArray? = encode(LEFT, col, row, release = true, sgr = sgr)

    /** Motion with the left button held. */
    fun drag(col: Int, row: Int, sgr: Boolean): ByteArray? =
        encode(LEFT + MOTION, col, row, release = false, sgr = sgr)

    /** One wheel notch. [up] means the content moves down (scrolling back), like the wheel rolling away. */
    fun wheel(up: Boolean, col: Int, row: Int, sgr: Boolean): ByteArray? =
        encode(if (up) WHEEL_UP else WHEEL_DOWN, col, row, release = false, sgr = sgr)

    /**
     * Encode one event. In the legacy form a release has no button (it reports button 3) and a coordinate
     * past 223 cannot be written, so such an event is dropped (null) instead of corrupting the stream.
     */
    fun encode(button: Int, col: Int, row: Int, release: Boolean, sgr: Boolean): ByteArray? {
        val x = col.coerceAtLeast(0) + 1
        val y = row.coerceAtLeast(0) + 1
        if (sgr) {
            return "\u001B[<$button;$x;$y${if (release) 'm' else 'M'}".toByteArray(Charsets.US_ASCII)
        }
        if (x > LEGACY_MAX || y > LEGACY_MAX) return null
        val b = if (release) 3 else button
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (32 + b).toByte(), (32 + x).toByte(), (32 + y).toByte())
    }

    /**
     * Arrow keys for a vertical swipe on a full-screen program that did not ask for the mouse (less,
     * man, vim without `mouse=a`). [lines] > 0 scrolls the content down (finger moved up) and sends
     * Down; < 0 sends Up. Application cursor mode (DECCKM) uses `ESC O` instead of `ESC [`.
     */
    fun scrollKeys(lines: Int, applicationCursorKeys: Boolean): ByteArray {
        if (lines == 0) return ByteArray(0)
        val prefix = if (applicationCursorKeys) "\u001BO" else "\u001B["
        val key = prefix + (if (lines > 0) 'B' else 'A')
        return key.repeat(kotlin.math.abs(lines).coerceAtMost(MAX_SCROLL_KEYS)).toByteArray(Charsets.US_ASCII)
    }

    /** Wheel events for a vertical swipe on a program that asked for the mouse. */
    fun wheelEvents(lines: Int, col: Int, row: Int, sgr: Boolean): ByteArray {
        if (lines == 0) return ByteArray(0)
        val one = wheel(up = lines < 0, col = col, row = row, sgr = sgr) ?: return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        repeat(kotlin.math.abs(lines).coerceAtMost(MAX_SCROLL_KEYS)) { out.write(one) }
        return out.toByteArray()
    }

    /**
     * The bytes to send for pasted [text]. Line breaks become CR, as typing Enter would. When the program
     * enabled bracketed paste (DECSET 2004) the text is wrapped in `ESC[200~ … ESC[201~` so a shell takes a
     * multi-line paste as one piece instead of running it line by line; any escape character inside is
     * dropped, so pasted text cannot contain the closing marker and break out of the bracket.
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        if (!bracketed) return normalized.toByteArray(Charsets.UTF_8)
        val safe = normalized.filter { it != '\u001B' }
        return ("\u001B[200~" + safe + "\u001B[201~").toByteArray(Charsets.UTF_8)
    }

    /** A swipe never sends more than this many keys or wheel notches at once. */
    const val MAX_SCROLL_KEYS = 12
}

/** The terminal's text size: pinch to change, remembered between sessions. */
object TerminalFontScale {
    const val DEFAULT_SP = 13f
    const val MIN_SP = 8f
    const val MAX_SP = 32f

    /** New size after a pinch of [factor] (>1 spreads fingers apart); always within [MIN_SP], [MAX_SP]. */
    fun scaled(currentSp: Float, factor: Float): Float {
        if (!factor.isFinite() || factor <= 0f) return currentSp.coerceIn(MIN_SP, MAX_SP)
        return (currentSp * factor).coerceIn(MIN_SP, MAX_SP)
    }
}
