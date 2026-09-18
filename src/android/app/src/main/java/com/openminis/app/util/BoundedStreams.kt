package com.openminis.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming reads that never trust a caller-declared size.
 *
 * A picked document, share target or archive entry can announce any length it
 * likes, so a copy that trusts `InputStream.available()`, a `Content-Length` or
 * a ZIP entry header will still buffer or write without limit. Every helper
 * here counts what actually arrives and fails closed past the budget instead.
 */
internal object BoundedStreams {

    const val CHUNK_BYTES = 8 * 1024

    /** Copies at most [maxBytes] and throws once the source has more to give.
     *  Returns the number of bytes copied. */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        val buffer = ByteArray(CHUNK_BYTES)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            if (copied > maxBytes) {
                throw IOException("stream exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return copied
    }

    /** Reads at most [maxBytes] into memory and throws once the source has more
     *  to give, so an oversized document never reaches the heap whole. */
    fun readBytes(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        val out = ByteArrayOutputStream()
        copy(input, out, maxBytes)
        return out.toByteArray()
    }
}
