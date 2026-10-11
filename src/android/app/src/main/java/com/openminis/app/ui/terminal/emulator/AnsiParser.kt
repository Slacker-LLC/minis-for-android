package com.openminis.app.ui.terminal.emulator

/** Actions emitted by the parser. */
sealed class ParsedAction {
    data class Printable(val codePoint: Int) : ParsedAction()
    data class ControlChar(val byte: Int) : ParsedAction()
    data class CsiDispatch(
        val params: IntArray,
        val intermediate: Char?,
        val finalByte: Char,
        /**
         * `colon[i]` is true when parameter i was written after a `:` rather than a `;`, i.e. it is a sub-parameter
         * of the parameter before it (`4:3` curly underline, `38:2::255:0:0` true colour). Empty when there were none.
         */
        val colon: BooleanArray = NO_COLON,
    ) : ParsedAction()
    /** An ESC sequence; [intermediate] is the byte between ESC and the final one (`(` in `ESC ( 0`, `#` in `ESC # 8`). */
    data class EscDispatch(val char: Char, val intermediate: Char? = null) : ParsedAction()
    data class OscDispatch(val command: Int, val payload: String) : ParsedAction()
}

internal val NO_COLON = BooleanArray(0)

/**
 * VT100/xterm escape sequence parser — state machine port of iOS ANSIParser.swift.
 * Feed raw bytes via [feed]; emits [ParsedAction] values through the callback.
 */
class AnsiParser {
    private enum class State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE,
        CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE,
        OSC_PARAM, OSC_STRING,
        /** DCS / APC / PM / SOS: a string for the terminal to skip, ended by ST. */
        IGNORED_STRING,
    }

    private var state = State.GROUND

    private val csiParams = mutableListOf<Int>()
    private val csiColon = mutableListOf<Boolean>()
    private var nextIsSub = false
    private var currentParam = 0
    private var hasParam = false
    private var csiIntermediate: Char? = null
    private var escIntermediate: Char? = null

    private var oscCommand = 0
    // OSC text is UTF-8: it is collected as bytes and decoded once, or a CJK title would arrive as Latin-1 noise.
    private var oscPayload = java.io.ByteArrayOutputStream()
    private var oscOverflow = false

    private val utf8Buffer = ByteArray(4)
    private var utf8Len = 0
    private var utf8Remaining = 0

    fun feed(bytes: ByteArray, len: Int, action: (ParsedAction) -> Unit) {
        for (i in 0 until len) processByte(bytes[i].toInt() and 0xFF, action)
    }

    fun reset() {
        state = State.GROUND
        csiParams.clear()
        csiColon.clear()
        nextIsSub = false
        currentParam = 0
        hasParam = false
        csiIntermediate = null
        escIntermediate = null
        oscCommand = 0
        oscPayload.reset()
        oscOverflow = false
        utf8Len = 0
        utf8Remaining = 0
    }

    private fun processByte(byte: Int, action: (ParsedAction) -> Unit) {
        // UTF-8 continuation handling (only in ground state)
        if (utf8Remaining > 0) {
            if (byte and 0xC0 == 0x80) {
                utf8Buffer[utf8Len++] = byte.toByte()
                utf8Remaining--
                if (utf8Remaining == 0) {
                    val cp = decodeUtf8(utf8Buffer, utf8Len)
                    utf8Len = 0
                    if (cp >= 0) action(ParsedAction.Printable(cp))
                }
                return
            } else {
                utf8Len = 0
                utf8Remaining = 0
                // fall through to re-process this byte
            }
        }

        when (state) {
            State.GROUND -> processGround(byte, action)
            State.ESCAPE -> processEscape(byte, action)
            State.ESCAPE_INTERMEDIATE -> processEscapeIntermediate(byte, action)
            State.CSI_ENTRY -> processCsiEntry(byte, action)
            State.CSI_PARAM -> processCsiParam(byte, action)
            State.CSI_INTERMEDIATE -> processCsiIntermediate(byte, action)
            State.OSC_PARAM -> processOscParam(byte, action)
            State.OSC_STRING -> processOscString(byte, action)
            State.IGNORED_STRING -> processIgnoredString(byte)
        }
    }

    private fun processGround(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            0x1B -> state = State.ESCAPE
            in 0x00..0x1A, in 0x1C..0x1F -> action(ParsedAction.ControlChar(byte))
            in 0x20..0x7E -> action(ParsedAction.Printable(byte))
            0x7F -> { /* DEL ignored */ }
            in 0xC0..0xDF -> {
                utf8Buffer[0] = byte.toByte(); utf8Len = 1; utf8Remaining = 1
            }
            in 0xE0..0xEF -> {
                utf8Buffer[0] = byte.toByte(); utf8Len = 1; utf8Remaining = 2
            }
            in 0xF0..0xF7 -> {
                utf8Buffer[0] = byte.toByte(); utf8Len = 1; utf8Remaining = 3
            }
            else -> { /* invalid start byte */ }
        }
    }

    private fun processEscape(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            0x5B -> { // [
                state = State.CSI_ENTRY
                csiParams.clear()
                csiColon.clear()
                nextIsSub = false
                currentParam = 0
                hasParam = false
                csiIntermediate = null
            }
            0x5D -> { // ]
                state = State.OSC_PARAM
                oscCommand = 0
                oscPayload.reset()
                oscOverflow = false
            }
            // DCS, SOS, PM, APC: strings the terminal does not act on. Skipped whole, or their text would be printed.
            0x50, 0x58, 0x5E, 0x5F -> state = State.IGNORED_STRING
            in 0x20..0x2F -> {
                escIntermediate = byte.toChar()
                state = State.ESCAPE_INTERMEDIATE
            }
            in 0x30..0x7E -> {
                action(ParsedAction.EscDispatch(byte.toChar()))
                state = State.GROUND
            }
            0x1B -> { /* new ESC, stay in escape */ }
            else -> state = State.GROUND
        }
    }

    private fun processEscapeIntermediate(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            in 0x20..0x2F -> { /* absorb additional intermediates */ }
            in 0x30..0x7E -> {
                action(ParsedAction.EscDispatch(byte.toChar(), escIntermediate))
                escIntermediate = null
                state = State.GROUND
            }
            0x1B -> state = State.ESCAPE
            else -> state = State.GROUND
        }
    }

    /** Adds a finished parameter; [sub] says the NEXT one follows a colon. The one added now follows [nextIsSub]. */
    private fun pushParam(value: Int, sub: Boolean = false) {
        csiParams.add(value)
        csiColon.add(nextIsSub)
        nextIsSub = sub
    }

    private fun processCsiEntry(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            in 0x30..0x39 -> {
                currentParam = byte - 0x30
                hasParam = true
                state = State.CSI_PARAM
            }
            0x3B -> {
                pushParam(0)
                state = State.CSI_PARAM
            }
            0x3A -> {
                pushParam(0, sub = true)
                state = State.CSI_PARAM
            }
            in 0x3C..0x3F -> {
                csiIntermediate = byte.toChar()
                state = State.CSI_PARAM
            }
            in 0x20..0x2F -> {
                csiIntermediate = byte.toChar()
                state = State.CSI_INTERMEDIATE
            }
            in 0x40..0x7E -> {
                action(ParsedAction.CsiDispatch(IntArray(0), null, byte.toChar()))
                state = State.GROUND
            }
            0x1B -> state = State.ESCAPE
            else -> state = State.GROUND
        }
    }

    private fun processCsiParam(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            in 0x30..0x39 -> {
                // A parameter is a count or a coordinate; nothing real needs more than this, and
                // an unbounded run of digits would overflow Int and drive huge loops downstream.
                if (currentParam < MAX_CSI_PARAM) currentParam = currentParam * 10 + (byte - 0x30)
                hasParam = true
            }
            0x3B -> {
                pushParam(if (hasParam) currentParam else 0)
                currentParam = 0
                hasParam = false
            }
            // A colon starts a sub-parameter of the parameter before it (4:3, 38:2::r:g:b).
            0x3A -> {
                pushParam(if (hasParam) currentParam else 0, sub = true)
                currentParam = 0
                hasParam = false
            }
            in 0x3C..0x3F -> {
                if (csiIntermediate == null) csiIntermediate = byte.toChar()
            }
            in 0x20..0x2F -> {
                if (hasParam) {
                    pushParam(currentParam)
                    currentParam = 0
                    hasParam = false
                }
                if (csiIntermediate == null) csiIntermediate = byte.toChar()
                state = State.CSI_INTERMEDIATE
            }
            in 0x40..0x7E -> {
                if (hasParam || nextIsSub) pushParam(currentParam)
                action(ParsedAction.CsiDispatch(
                    csiParams.toIntArray(),
                    csiIntermediate,
                    byte.toChar(),
                    if (csiColon.any { it }) csiColon.toBooleanArray() else NO_COLON,
                ))
                state = State.GROUND
            }
            0x1B -> state = State.ESCAPE
            else -> state = State.GROUND
        }
    }

    private fun processCsiIntermediate(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            in 0x20..0x2F -> { /* more intermediates */ }
            in 0x40..0x7E -> {
                if (hasParam || nextIsSub) pushParam(currentParam)
                action(ParsedAction.CsiDispatch(
                    csiParams.toIntArray(),
                    csiIntermediate,
                    byte.toChar(),
                    if (csiColon.any { it }) csiColon.toBooleanArray() else NO_COLON,
                ))
                state = State.GROUND
            }
            0x1B -> state = State.ESCAPE
            else -> state = State.GROUND
        }
    }

    private fun dispatchOsc(action: (ParsedAction) -> Unit) {
        if (!oscOverflow) action(ParsedAction.OscDispatch(oscCommand, String(oscPayload.toByteArray(), Charsets.UTF_8)))
        oscOverflow = false
    }

    private fun processOscParam(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            in 0x30..0x39 -> {
                oscCommand = oscCommand * 10 + (byte - 0x30)
            }
            0x3B -> state = State.OSC_STRING
            0x07 -> {
                dispatchOsc(action)
                state = State.GROUND
            }
            0x1B -> {
                dispatchOsc(action)
                state = State.ESCAPE
            }
            else -> state = State.GROUND
        }
    }

    /** Skips a DCS / APC / PM / SOS string: it ends at ST (ESC \\) or BEL; a new ESC starts the next sequence. */
    private fun processIgnoredString(byte: Int) {
        when (byte) {
            0x07 -> state = State.GROUND
            0x1B -> state = State.ESCAPE
            else -> Unit
        }
    }

    private fun processOscString(byte: Int, action: (ParsedAction) -> Unit) {
        when (byte) {
            0x07 -> {
                dispatchOsc(action)
                state = State.GROUND
            }
            0x1B -> {
                dispatchOsc(action)
                state = State.ESCAPE
            }
            in 0x20..0x7E, in 0x80..0xFF -> {
                // OSC text is untrusted and unbounded; an oversize string is dropped whole, never truncated.
                if (oscPayload.size() >= MAX_OSC_PAYLOAD) oscOverflow = true else oscPayload.write(byte)
            }
            else -> { /* ignore */ }
        }
    }

    private fun decodeUtf8(buf: ByteArray, len: Int): Int {
        // buf[0..len-1] is a complete UTF-8 sequence
        val b0 = buf[0].toInt() and 0xFF
        val cp = when (len) {
            2 -> ((b0 and 0x1F) shl 6) or (buf[1].toInt() and 0x3F)
            3 -> ((b0 and 0x0F) shl 12) or ((buf[1].toInt() and 0x3F) shl 6) or (buf[2].toInt() and 0x3F)
            4 -> ((b0 and 0x07) shl 18) or ((buf[1].toInt() and 0x3F) shl 12) or ((buf[2].toInt() and 0x3F) shl 6) or (buf[3].toInt() and 0x3F)
            else -> return -1
        }
        // Overlong forms, surrogates and anything past U+10FFFF are not characters; the renderer
        // builds a String from the code point and throws on them. Show U+FFFD instead.
        val minimum = when (len) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
        return if (cp < minimum || cp > 0x10FFFF || cp in 0xD800..0xDFFF) REPLACEMENT else cp
    }

    companion object {
        internal const val MAX_CSI_PARAM = 65_535
        private const val REPLACEMENT = 0xFFFD

        /** Longest OSC string kept; OSC 7501 allows 4096 bytes for the whole sequence. */
        private const val MAX_OSC_PAYLOAD = 4096
    }
}
