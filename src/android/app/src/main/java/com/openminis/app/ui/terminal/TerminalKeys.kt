package com.openminis.app.ui.terminal

/**
 * What the next typed input becomes when a sticky Ctrl and/or Alt key is armed on the accessory
 * bar. Ctrl turns a single letter (or one of `@ [ \ ] ^ _`) into its control byte; Alt prefixes
 * the input with ESC, which is how terminals send Meta. Input that Ctrl cannot apply to is sent
 * unchanged, so an armed Ctrl never swallows a keystroke.
 *
 * [ctrlUsed] / [altUsed] report which modifiers were actually applied, so the caller can disarm
 * exactly those.
 */
internal class ModifiedInput(val bytes: ByteArray, val ctrlUsed: Boolean, val altUsed: Boolean)

internal fun applyTerminalModifiers(input: ByteArray, ctrl: Boolean, alt: Boolean): ModifiedInput {
    var bytes = input
    var ctrlUsed = false
    if (ctrl && bytes.size == 1) {
        val c = bytes[0].toInt().toChar().uppercaseChar()
        val control = when (c) {
            in 'A'..'Z' -> c - 'A' + 1
            '@' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_' -> 31
            else -> -1
        }
        if (control >= 0) {
            bytes = byteArrayOf(control.toByte())
            ctrlUsed = true
        }
    }
    var altUsed = false
    if (alt && bytes.isNotEmpty()) {
        bytes = byteArrayOf(0x1B) + bytes
        altUsed = true
    }
    return ModifiedInput(bytes, ctrlUsed, altUsed)
}
