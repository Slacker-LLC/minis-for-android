package com.openminis.app.ui.terminal.emulator

/** The DEC special graphics set (`ESC ( 0`): line drawing and a few symbols in place of 0x5F-0x7E. */
internal object DecSpecialGraphics {
    private val table = mapOf(
        '_' to 0x00A0, '`' to 0x25C6, 'a' to 0x2592, 'b' to 0x2409, 'c' to 0x240C, 'd' to 0x240D, 'e' to 0x240A,
        'f' to 0x00B0, 'g' to 0x00B1, 'h' to 0x2424, 'i' to 0x240B, 'j' to 0x2518, 'k' to 0x2510, 'l' to 0x250C,
        'm' to 0x2514, 'n' to 0x253C, 'o' to 0x23BA, 'p' to 0x23BB, 'q' to 0x2500, 'r' to 0x23BC, 's' to 0x23BD,
        't' to 0x251C, 'u' to 0x2524, 'v' to 0x2534, 'w' to 0x252C, 'x' to 0x2502, 'y' to 0x2264, 'z' to 0x2265,
        '{' to 0x03C0, '|' to 0x2260, '}' to 0x00A3, '~' to 0x00B7,
    )

    fun map(codePoint: Int): Int = if (codePoint in 0x5F..0x7E) table[codePoint.toChar()] ?: codePoint else codePoint
}
