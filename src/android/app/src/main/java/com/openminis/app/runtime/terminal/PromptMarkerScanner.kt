package com.openminis.app.runtime.terminal

/**
 * Finds the guest's prompt marker (OSC 133;A, `ESC ] 133 ; A`) in the output stream, including when it is cut
 * between two reads. Pure apart from the few bytes it remembers, so it is unit-tested.
 */
internal class PromptMarkerScanner {
    private var tail = ByteArray(0)

    fun reset() { tail = ByteArray(0) }

    /** Whether [length] bytes of [chunk], together with what came before, contain the marker. */
    fun feed(chunk: ByteArray, length: Int): Boolean {
        val window = ByteArray(tail.size + length)
        tail.copyInto(window)
        chunk.copyInto(window, tail.size, 0, length)
        val found = indexOf(window) >= 0
        tail = window.copyOfRange(maxOf(0, window.size - (MARKER.size - 1)), window.size)
        return found
    }

    private fun indexOf(window: ByteArray): Int {
        outer@ for (i in 0..window.size - MARKER.size) {
            for (j in MARKER.indices) if (window[i + j] != MARKER[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        val MARKER = byteArrayOf(0x1B, ']'.code.toByte(), '1'.code.toByte(), '3'.code.toByte(), '3'.code.toByte(), ';'.code.toByte(), 'A'.code.toByte())
    }
}
