package com.openminis.app.mcp.client

import java.io.Reader

/**
 * [T-mcp-stdio-line-bound-android] Reads one line at a time without letting the line
 * decide how much memory this app spends. `BufferedReader.readLine()` buffers a whole
 * line before any length check can run, so the 64 KiB cap the stdio transport used to
 * apply *after* the read was cosmetic: one line from a hostile or broken local server
 * could still be hundreds of megabytes in memory before anyone looked at it.
 *
 * A line past [limit] is an error, not a truncation — a truncated JSON-RPC frame is
 * useless, and pretending otherwise would hand the caller a parse failure that hides
 * why it happened.
 */
internal class MCPBoundedLineReader(
    private val reader: Reader,
    private val limit: Int,
    private val source: String,
) {
    /** Reads one line; null at end of stream; throws when the line exceeds [limit]. */
    fun readLine(): String? {
        val out = StringBuilder(minOf(limit, 256))
        while (true) {
            val read = reader.read()
            if (read < 0) {
                return if (out.isEmpty()) null else out.toString()
            }
            val character = read.toChar()
            if (character == '\n') return out.toString()
            if (character == '\r') continue
            if (out.length >= limit) {
                throw MCPTransportException("oversized line from $source (>$limit chars)")
            }
            out.append(character)
        }
    }

    /**
     * Skips whatever is left of an oversized line, bounded, so a caller that wants to
     * keep the stream usable (the stderr pump) can continue on the next line instead of
     * blocking the server on a full pipe.
     */
    fun skipToEndOfLine(maxChars: Int = 1 shl 20) {
        var skipped = 0
        while (skipped < maxChars) {
            val read = reader.read()
            if (read < 0 || read.toChar() == '\n') return
            skipped++
        }
    }
}
