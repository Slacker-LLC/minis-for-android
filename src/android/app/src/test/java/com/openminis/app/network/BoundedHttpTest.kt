package com.openminis.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedHttpTest {

    @Test
    fun `content length counts UTF-8 bytes and body decodes strictly`() {
        val body = "{\"message\":\"你好\"}".toByteArray(Charsets.UTF_8)
        val request = "POST / HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII) + body
        val reader = BoundedHttpRequestReader(ByteArrayInputStream(request))

        val head = reader.readHead()!!

        assertEquals(body.size.toLong(), head.contentLength)
        assertEquals("你好", org.json.JSONObject(BoundedHttp.decodeUtf8(reader.readBody(head.contentLength)))
            .getString("message"))
    }

    @Test
    fun `incomplete body is rejected instead of being dispatched`() {
        val body = "{\"jsonrpc\":\"2.0\"}".toByteArray(Charsets.UTF_8)
        val request = "POST / HTTP/1.1\r\nContent-Length: ${body.size + 1}\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII) + body
        val reader = BoundedHttpRequestReader(ByteArrayInputStream(request))
        val head = reader.readHead()!!

        val error = assertThrows(BoundedHttpException::class.java) {
            reader.readBody(head.contentLength)
        }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("incomplete request body"))
    }

    @Test
    fun `invalid UTF-8 body is rejected`() {
        val error = assertThrows(BoundedHttpException::class.java) {
            BoundedHttp.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("valid UTF-8"))
    }

    @Test
    fun `conflicting content lengths are rejected`() {
        val request = "POST / HTTP/1.1\r\n" +
            "Content-Length: 1\r\nContent-Length: 2\r\n\r\n"
        val reader = BoundedHttpRequestReader(
            ByteArrayInputStream(request.toByteArray(StandardCharsets.US_ASCII)),
        )

        val error = assertThrows(BoundedHttpException::class.java) { reader.readHead() }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("conflicting Content-Length"))
    }

    @Test
    fun `transfer encoding is rejected`() {
        val request = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
        val reader = BoundedHttpRequestReader(
            ByteArrayInputStream(request.toByteArray(StandardCharsets.US_ASCII)),
        )

        val error = assertThrows(BoundedHttpException::class.java) { reader.readHead() }

        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("Transfer-Encoding"))
    }

    @Test
    fun `response content length is the UTF-8 byte length`() {
        val output = ByteArrayOutputStream()

        BoundedHttp.writeResponse(output, 200, "你好")

        val wire = output.toByteArray()
        val separator = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val separatorAt = wire.indexOfSubsequence(separator)
        val headerText = String(wire, 0, separatorAt, StandardCharsets.US_ASCII)
        val body = wire.copyOfRange(separatorAt + separator.size, wire.size)

        assertTrue(headerText.contains("Content-Length: ${body.size}"))
        assertEquals("你好", String(body, Charsets.UTF_8))
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        throw AssertionError("subsequence not found")
    }
}
