package com.openminis.app.ui.terminal.emulator

import androidx.compose.ui.graphics.Color

/** Terminal color representation. */
sealed class TerminalColor {
    object Default : TerminalColor()
    data class Indexed(val index: Int) : TerminalColor()
    data class Rgb(val r: Int, val g: Int, val b: Int) : TerminalColor()
}

/** xterm-256 palette and default fg/bg, matching iOS TerminalPalette. */
object TerminalPalette {
    /**
     * Light theme switch (docs/design/UI-DESIGN-LANGUAGE.md §3). When true the default colours are
     * black on white and ANSI colours 0-15 use darker variants so "white" / "bright yellow" output
     * written for dark terminals stays readable. Colours are resolved at draw time.
     */
    @Volatile
    var light: Boolean = false

    private val palette: Array<Color> = buildPalette()

    // ANSI 0-15 for the light theme; each is >= 4.5:1 on white (TerminalPaletteTest).
    private val lightAnsi: Array<Color> = arrayOf(
        Color(0, 0, 0), Color(178, 24, 24), Color(20, 116, 20), Color(125, 95, 0),
        Color(0, 0, 205), Color(150, 0, 150), Color(0, 110, 120), Color(85, 85, 85),
        Color(90, 90, 90), Color(200, 30, 30), Color(20, 120, 20), Color(130, 100, 0),
        Color(30, 55, 225), Color(170, 0, 170), Color(0, 115, 130), Color(0, 0, 0),
    )

    internal fun ansiForTest(index: Int, light: Boolean): Color =
        if (light) lightAnsi[index] else palette[index]

    private fun indexed(index: Int): Color {
        val i = index.coerceIn(0, 255)
        return if (light && i < 16) lightAnsi[i] else palette[i]
    }

    private fun buildPalette(): Array<Color> {
        val p = Array(256) { Color.Black }
        // 0-7 standard
        p[0] = Color(0, 0, 0)
        p[1] = Color(205, 0, 0)
        p[2] = Color(0, 205, 0)
        p[3] = Color(205, 205, 0)
        p[4] = Color(0, 0, 238)
        p[5] = Color(205, 0, 205)
        p[6] = Color(0, 205, 205)
        p[7] = Color(229, 229, 229)
        // 8-15 bright
        p[8] = Color(127, 127, 127)
        p[9] = Color(255, 0, 0)
        p[10] = Color(0, 255, 0)
        p[11] = Color(255, 255, 0)
        p[12] = Color(92, 92, 255)
        p[13] = Color(255, 0, 255)
        p[14] = Color(0, 255, 255)
        p[15] = Color(255, 255, 255)
        // 16-231: 6×6×6 cube
        var idx = 16
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            val rv = if (r == 0) 0 else 55 + 40 * r
            val gv = if (g == 0) 0 else 55 + 40 * g
            val bv = if (b == 0) 0 else 55 + 40 * b
            p[idx++] = Color(rv, gv, bv)
        }
        // 232-255 grayscale
        for (i in 0 until 24) {
            val v = 8 + 10 * i
            p[idx++] = Color(v, v, v)
        }
        return p
    }

    val defaultForeground: Color
        get() = if (light) Color.Black else Color(204, 204, 204)
    val defaultBackground: Color
        get() = if (light) Color.White else Color.Black

    fun resolve(color: TerminalColor, isForeground: Boolean, bold: Boolean = false): Color = when (color) {
        is TerminalColor.Default -> if (isForeground) defaultForeground else defaultBackground
        is TerminalColor.Indexed -> {
            if (bold && isForeground && color.index < 8) indexed(color.index + 8)
            else indexed(color.index)
        }
        is TerminalColor.Rgb -> Color(color.r, color.g, color.b)
    }
}

/** Text attributes — bitfield. */
@JvmInline
value class TextAttributes(val bits: Int = 0) {
    fun has(flag: Int) = bits and flag != 0
    fun with(flag: Int) = TextAttributes(bits or flag)
    fun without(flag: Int) = TextAttributes(bits and flag.inv())

    companion object {
        const val BOLD = 1 shl 0
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val BLINK = 1 shl 4
        const val INVERSE = 1 shl 5
        const val HIDDEN = 1 shl 6
        const val STRIKETHROUGH = 1 shl 7
    }
}

/** A single character cell in the grid. */
data class TerminalCell(
    val char: Int = ' '.code,              // Unicode code point
    val foreground: TerminalColor = TerminalColor.Default,
    val background: TerminalColor = TerminalColor.Default,
    val attributes: TextAttributes = TextAttributes(),
    val width: Int = 1,
    val isWideTrailer: Boolean = false,
) {
    companion object {
        val BLANK = TerminalCell()
    }
}

/** SGR state applied to new characters. */
data class CursorStyle(
    var foreground: TerminalColor = TerminalColor.Default,
    var background: TerminalColor = TerminalColor.Default,
    var attributes: TextAttributes = TextAttributes(),
) {
    fun makeCell(char: Int, width: Int = 1): TerminalCell =
        TerminalCell(char, foreground, background, attributes, width)

    fun copyFrom(other: CursorStyle) {
        foreground = other.foreground
        background = other.background
        attributes = other.attributes
    }
}

enum class CursorShape { BLOCK, UNDERLINE, BAR }
