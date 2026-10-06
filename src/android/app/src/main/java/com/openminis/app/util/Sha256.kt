package com.openminis.app.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 digests as lowercase hex, the form used for cache keys, revisions and backup blob names. */
internal object Sha256 {

    private const val HEX = "0123456789abcdef"
    private const val CHUNK_BYTES = 64 * 1024

    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Digest of [text]'s UTF-8 bytes. */
    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    /** Streams [input] to its end without buffering it whole; the caller closes it. */
    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return toHex(digest.digest())
    }

    fun hex(file: File): String = file.inputStream().use { hex(it) }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }
}
