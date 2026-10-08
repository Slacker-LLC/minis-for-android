package com.openminis.app.ui.chat

import java.io.File

/**
 * Text attachments reach the model as text. Each file's head goes into the `<user-attached-files>` block, under
 * these limits; anything past them is named in a note so the model knows to read the rest from the file.
 */
internal const val INLINE_TEXT_PER_FILE_CHARS = 60_000
internal const val INLINE_TEXT_TOTAL_CHARS = 120_000

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf",
    "properties", "env", "log", "html", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "sh",
    "go", "rs", "c", "h", "cpp", "hpp", "sql", "gradle", "srt", "vtt",
)

private val TEXT_MIME_TYPES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-sh", "application/x-yaml",
)

/** Whether a file is text by its MIME type or extension. The body is still checked for NUL bytes when read. */
internal fun isTextLikeAttachment(fileName: String, mimeType: String): Boolean =
    mimeType.startsWith("text/") ||
        mimeType in TEXT_MIME_TYPES ||
        fileName.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

/** The head of a file that is going into the request: the decoded text, the file's size, and whether it was cut. */
internal data class InlineText(val text: String, val totalBytes: Long, val truncated: Boolean)

/**
 * The first [maxChars] characters of [file], decoded as UTF-8. Null when the file cannot be read or is not text
 * (a NUL byte in the head). Reads at most `maxChars * 4` bytes, so a large file is never loaded whole.
 */
internal fun readInlineText(file: File, maxChars: Int): InlineText? {
    if (maxChars <= 0) return null
    val size = file.length()
    val head = try {
        file.inputStream().use { input -> readHead(input, minOf(size, maxChars.toLong() * 4).toInt()) }
    } catch (_: Exception) {
        return null
    }
    if (head.any { it == 0.toByte() }) return null
    val decoded = String(head, Charsets.UTF_8)
    val cut = decoded.length > maxChars
    val text = if (cut) decoded.substring(0, maxChars) else decoded
    return InlineText(text = text, totalBytes = size, truncated = cut || size > head.size)
}

/** The first [limit] bytes of [input], fewer if it ends first. Loops because `readNBytes` needs API 33. */
private fun readHead(input: java.io.InputStream, limit: Int): ByteArray {
    val buf = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val n = input.read(buf, filled, limit - filled)
        if (n < 0) break
        filled += n
    }
    return buf.copyOf(filled)
}

/**
 * Keeps the body from closing the `<user-attached-files>` block early. Display stripping and the other readers of
 * the block all cut at the first closing tag, so a file that contains one must not end the block.
 */
internal fun escapeInlineBody(text: String): String =
    text.replace("</user-attached-files", "<\\/user-attached-files")
