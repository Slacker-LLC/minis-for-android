package com.openminis.app.ui.terminal

import android.view.KeyEvent

/**
 * What a hardware (or Bluetooth) key press sends to the program, in xterm's encoding.
 *
 * Modified cursor, editing and function keys carry the xterm modifier parameter
 * (`1 + shift + 2·alt + 4·ctrl`): Ctrl+Left is `ESC [ 1 ; 5 D`, which is what editors and shells bind
 * word movement to. Unmodified cursor keys follow DECCKM ([applicationCursor]). Shift+Tab is the back-tab
 * `ESC [ Z`. Alt prefixes the key with ESC, which is how terminals send Meta.
 *
 * Returns null for a key that has no terminal meaning, so the caller can let the system handle it.
 * [unicode] is the character the key produces without Ctrl/Alt applied (0 for none).
 */
internal object TerminalKeyEncoder {
    private const val ESC = "\u001B"

    fun encode(keyCode: Int, unicode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, applicationCursor: Boolean): ByteArray? {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val modified = mod > 1

        cursorKey(keyCode)?.let { final ->
            return when {
                modified -> "$ESC[1;$mod$final".toByteArray()
                applicationCursor -> "${ESC}O$final".toByteArray()
                else -> "$ESC[$final".toByteArray()
            }
        }
        tildeKey(keyCode)?.let { code -> return (if (modified) "$ESC[$code;$mod~" else "$ESC[$code~").toByteArray() }
        functionKey(keyCode)?.let { (n, ss3) ->
            return when {
                ss3 != null -> (if (modified) "$ESC[1;$mod$ss3" else "${ESC}O$ss3").toByteArray()
                else -> (if (modified) "$ESC[$n;$mod~" else "$ESC[$n~").toByteArray()
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> withMeta(alt, byteArrayOf(0x0D))
            KeyEvent.KEYCODE_DEL -> withMeta(alt, byteArrayOf(if (ctrl) 0x08 else 0x7F))
            KeyEvent.KEYCODE_TAB -> if (shift) "$ESC[Z".toByteArray() else withMeta(alt, byteArrayOf(0x09))
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            else -> character(unicode, ctrl, alt)
        }
    }

    private fun cursorKey(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 'A'
        KeyEvent.KEYCODE_DPAD_DOWN -> 'B'
        KeyEvent.KEYCODE_DPAD_RIGHT -> 'C'
        KeyEvent.KEYCODE_DPAD_LEFT -> 'D'
        KeyEvent.KEYCODE_MOVE_END -> 'F'
        KeyEvent.KEYCODE_MOVE_HOME -> 'H'
        else -> null
    }

    private fun tildeKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_INSERT -> 2
        KeyEvent.KEYCODE_FORWARD_DEL -> 3
        KeyEvent.KEYCODE_PAGE_UP -> 5
        KeyEvent.KEYCODE_PAGE_DOWN -> 6
        else -> null
    }

    /** F1–F4 are SS3 P–S; F5–F12 are `ESC [ n ~` with xterm's gaps (16 and 22 are unused). */
    private fun functionKey(keyCode: Int): Pair<Int, Char?>? = when (keyCode) {
        KeyEvent.KEYCODE_F1 -> 11 to 'P'
        KeyEvent.KEYCODE_F2 -> 12 to 'Q'
        KeyEvent.KEYCODE_F3 -> 13 to 'R'
        KeyEvent.KEYCODE_F4 -> 14 to 'S'
        KeyEvent.KEYCODE_F5 -> 15 to null
        KeyEvent.KEYCODE_F6 -> 17 to null
        KeyEvent.KEYCODE_F7 -> 18 to null
        KeyEvent.KEYCODE_F8 -> 19 to null
        KeyEvent.KEYCODE_F9 -> 20 to null
        KeyEvent.KEYCODE_F10 -> 21 to null
        KeyEvent.KEYCODE_F11 -> 23 to null
        KeyEvent.KEYCODE_F12 -> 24 to null
        else -> null
    }

    private fun character(unicode: Int, ctrl: Boolean, alt: Boolean): ByteArray? {
        if (unicode == 0) return null
        val ch = unicode.toChar()
        if (ctrl) {
            val control = when (ch.uppercaseChar()) {
                in 'A'..'Z' -> ch.uppercaseChar() - 'A' + 1
                ' ', '@' -> 0
                '[' -> 27
                '\\' -> 28
                ']' -> 29
                '^' -> 30
                '_' -> 31
                else -> -1
            }
            if (control >= 0) return withMeta(alt, byteArrayOf(control.toByte()))
        }
        return withMeta(alt, String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8))
    }

    private fun withMeta(alt: Boolean, bytes: ByteArray): ByteArray = if (alt) byteArrayOf(0x1B) + bytes else bytes
}
