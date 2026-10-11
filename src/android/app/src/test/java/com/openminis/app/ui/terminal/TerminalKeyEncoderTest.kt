package com.openminis.app.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalKeyEncoderTest {
    private fun enc(
        key: Int,
        unicode: Int = 0,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        appCursor: Boolean = false,
    ) = TerminalKeyEncoder.encode(key, unicode, ctrl, alt, shift, appCursor)

    private fun s(text: String) = text.toByteArray()

    @Test
    fun plainCursorKeysFollowDecckm() {
        assertArrayEquals(s("\u001B[A"), enc(KeyEvent.KEYCODE_DPAD_UP))
        assertArrayEquals(s("\u001BOA"), enc(KeyEvent.KEYCODE_DPAD_UP, appCursor = true))
        assertArrayEquals(s("\u001B[H"), enc(KeyEvent.KEYCODE_MOVE_HOME))
        assertArrayEquals(s("\u001BOF"), enc(KeyEvent.KEYCODE_MOVE_END, appCursor = true))
    }

    @Test
    fun modifiedCursorKeysCarryTheXtermModifierEvenInApplicationMode() {
        assertArrayEquals(s("\u001B[1;5D"), enc(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertArrayEquals(s("\u001B[1;2C"), enc(KeyEvent.KEYCODE_DPAD_RIGHT, shift = true, appCursor = true))
        assertArrayEquals(s("\u001B[1;3A"), enc(KeyEvent.KEYCODE_DPAD_UP, alt = true))
        assertArrayEquals(s("\u001B[1;8B"), enc(KeyEvent.KEYCODE_DPAD_DOWN, ctrl = true, alt = true, shift = true))
    }

    @Test
    fun editingKeys() {
        assertArrayEquals(s("\u001B[3~"), enc(KeyEvent.KEYCODE_FORWARD_DEL))
        assertArrayEquals(s("\u001B[2~"), enc(KeyEvent.KEYCODE_INSERT))
        assertArrayEquals(s("\u001B[5~"), enc(KeyEvent.KEYCODE_PAGE_UP))
        assertArrayEquals(s("\u001B[6;5~"), enc(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true))
        assertArrayEquals(s("\u001B[3;2~"), enc(KeyEvent.KEYCODE_FORWARD_DEL, shift = true))
    }

    @Test
    fun functionKeys() {
        assertArrayEquals(s("\u001BOP"), enc(KeyEvent.KEYCODE_F1))
        assertArrayEquals(s("\u001BOS"), enc(KeyEvent.KEYCODE_F4))
        assertArrayEquals(s("\u001B[15~"), enc(KeyEvent.KEYCODE_F5))
        assertArrayEquals(s("\u001B[17~"), enc(KeyEvent.KEYCODE_F6))
        assertArrayEquals(s("\u001B[24~"), enc(KeyEvent.KEYCODE_F12))
        assertArrayEquals(s("\u001B[1;5P"), enc(KeyEvent.KEYCODE_F1, ctrl = true))
        assertArrayEquals(s("\u001B[15;2~"), enc(KeyEvent.KEYCODE_F5, shift = true))
    }

    @Test
    fun tabEnterBackspaceEscape() {
        assertArrayEquals(byteArrayOf(0x09), enc(KeyEvent.KEYCODE_TAB))
        assertArrayEquals(s("\u001B[Z"), enc(KeyEvent.KEYCODE_TAB, shift = true))
        assertArrayEquals(byteArrayOf(0x0D), enc(KeyEvent.KEYCODE_ENTER))
        assertArrayEquals(byteArrayOf(0x1B, 0x0D), enc(KeyEvent.KEYCODE_ENTER, alt = true))
        assertArrayEquals(byteArrayOf(0x7F), enc(KeyEvent.KEYCODE_DEL))
        assertArrayEquals(byteArrayOf(0x08), enc(KeyEvent.KEYCODE_DEL, ctrl = true))
        assertArrayEquals(byteArrayOf(0x1B, 0x7F), enc(KeyEvent.KEYCODE_DEL, alt = true))
        assertArrayEquals(byteArrayOf(0x1B), enc(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun controlCharacters() {
        assertArrayEquals(byteArrayOf(3), enc(KeyEvent.KEYCODE_C, 'c'.code, ctrl = true))
        assertArrayEquals(byteArrayOf(1), enc(KeyEvent.KEYCODE_A, 'A'.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0), enc(KeyEvent.KEYCODE_SPACE, ' '.code, ctrl = true))
        assertArrayEquals(byteArrayOf(27), enc(KeyEvent.KEYCODE_LEFT_BRACKET, '['.code, ctrl = true))
        assertArrayEquals(byteArrayOf(28), enc(KeyEvent.KEYCODE_BACKSLASH, '\\'.code, ctrl = true))
        assertArrayEquals(byteArrayOf(0x1B, 3), enc(KeyEvent.KEYCODE_C, 'c'.code, ctrl = true, alt = true))
    }

    @Test
    fun altPrefixesEscapeAndPlainTextPassesThroughAsUtf8() {
        assertArrayEquals(byteArrayOf(0x1B, 'f'.code.toByte()), enc(KeyEvent.KEYCODE_F, 'f'.code, alt = true))
        assertArrayEquals(s("a"), enc(KeyEvent.KEYCODE_A, 'a'.code))
        assertArrayEquals(s("é"), enc(0, 'é'.code))
        // Outside the BMP: one code point, not two surrogate halves.
        assertArrayEquals(s("😀"), enc(0, 0x1F600))
    }

    @Test
    fun ctrlWithACharacterThatHasNoControlCodeSendsTheCharacter() {
        assertArrayEquals(s("5"), enc(KeyEvent.KEYCODE_5, '5'.code, ctrl = true))
    }

    @Test
    fun aKeyWithNoTerminalMeaningIsLeftToTheSystem() {
        assertNull(enc(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(enc(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertNull(enc(KeyEvent.KEYCODE_CAPS_LOCK))
    }
}
