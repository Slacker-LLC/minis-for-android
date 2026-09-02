package com.openminis.app.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** A small, byte-oriented HTTP/1.x reader for the loopback JSON servers. */
internal object BoundedHttp {
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    const val MAX_HEADER_LINE_BYTES = 16 * 1024
    const val MAX_HEADERS = 100

    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw BoundedHttpException(400, "request body is not valid UTF-8")
    }

    fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        body: String,
        contentType: String? = "application/json",
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ")
            append(statusCode)
            append(' ')
            append(statusText(statusCode))
            append("\r\n")
            contentType?.let {
                append("Content-Type: ")
                append(it)
                append("\r\n")
            }
            append("Content-Length: ")
            append(bodyBytes.size)
            append("\r\nConnection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun statusText(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}

internal class BoundedHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal data class BoundedHttpRequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, List<String>>,
    val contentLength: Long,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.lastOrNull()
}

/** Reads an HTTP request head and then exactly the announced number of bytes. */
internal class BoundedHttpRequestReader(
    private val input: InputStream,
    private val maxBodyBytes: Int = BoundedHttp.MAX_BODY_BYTES,
    private val maxHeaderLineBytes: Int = BoundedHttp.MAX_HEADER_LINE_BYTES,
    private val maxHeaders: Int = BoundedHttp.MAX_HEADERS,
) {
    init {
        require(maxBodyBytes > 0)
        require(maxHeaderLineBytes > 0)
        require(maxHeaders > 0)
    }

    fun readHead(): BoundedHttpRequestHead? {
        val requestLine = readLine() ?: return null
        val parts = requestLine.trim().split(Regex("\\s+"))
        if (parts.size != 3 || parts.any { it.isEmpty() }) {
            throw BoundedHttpException(400, "malformed HTTP request line")
        }

        val headerValues = linkedMapOf<String, MutableList<String>>()
        var count = 0
        while (true) {
            val line = readLine() ?: throw BoundedHttpException(400, "incomplete HTTP headers")
            if (line.isEmpty()) break
            count++
            if (count > maxHeaders) {
                throw BoundedHttpException(400, "too many headers")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                throw BoundedHttpException(400, "malformed HTTP header")
            }
            val rawName = line.substring(0, colon)
            val name = rawName.trim()
            if (name.isEmpty() || name != rawName || name.any { it.code <= 32 || it.code >= 127 }) {
                throw BoundedHttpException(400, "malformed HTTP header name")
            }
            val key = name.lowercase(Locale.ROOT)
            headerValues.getOrPut(key) { mutableListOf() }
                .add(line.substring(colon + 1).trim())
        }

        val headers = headerValues.mapValues { it.value.toList() }
        val contentLengths = headers["content-length"].orEmpty().map { parseContentLength(it) }
        if (contentLengths.distinct().size > 1) {
            throw BoundedHttpException(400, "conflicting Content-Length headers")
        }
        if (headers["transfer-encoding"].orEmpty().any { it.isNotBlank() }) {
            throw BoundedHttpException(400, "Transfer-Encoding is not supported")
        }

        return BoundedHttpRequestHead(
            method = parts[0],
            target = parts[1],
            version = parts[2],
            headers = headers,
            contentLength = contentLengths.firstOrNull() ?: 0L,
        )
    }

    fun readBody(contentLength: Long): ByteArray {
        if (contentLength < 0) {
            throw BoundedHttpException(400, "invalid Content-Length")
        }
        if (contentLength > maxBodyBytes) {
            throw BoundedHttpException(413, "request body too large")
        }
        val body = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            when {
                count > 0 -> offset += count
                count < 0 -> throw BoundedHttpException(400, "incomplete request body")
                else -> {
                    val next = input.read()
                    if (next < 0) throw BoundedHttpException(400, "incomplete request body")
                    body[offset++] = next.toByte()
                }
            }
        }
        return body
    }

    private fun parseContentLength(value: String): Long {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) {
            throw BoundedHttpException(400, "invalid Content-Length")
        }
        return value.toLongOrNull()
            ?: throw BoundedHttpException(400, "invalid Content-Length")
    }

    private fun readLine(): String? {
        val bytes = ByteArrayOutputStream(minOf(maxHeaderLineBytes, 256))
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.size() == 0) return null
                throw BoundedHttpException(400, "incomplete HTTP line")
            }
            if (next == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.lastOrNull()?.toInt() == '\r'.code) raw.size - 1 else raw.size
                val line = String(raw, 0, length, Charsets.ISO_8859_1)
                if (line.any { it == '\r' || it.code < 32 && it != '\t' }) {
                    throw BoundedHttpException(400, "malformed HTTP line")
                }
                return line
            }
            bytes.write(next)
            if (bytes.size() > maxHeaderLineBytes) {
                throw BoundedHttpException(400, "HTTP line too long")
            }
        }
    }
}
