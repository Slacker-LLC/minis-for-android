package com.openminis.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The callers of these helpers are handed documents, share targets and archive
 * entries from outside the app, so the only trustworthy size is the number of
 * bytes that actually arrive.
 */
class BoundedStreamsTest {

    /** Reports a small declared length while serving much more data. */
    private class LyingStream(private val payload: ByteArray) : InputStream() {
        override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it == -1) -1 else payload[0].toInt() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (served >= payload.size) return -1
            val count = minOf(length, payload.size - served)
            payload.copyInto(buffer, offset, served, served + count)
            served += count
            return count
        }

        override fun available(): Int = 1

        private var served = 0
    }

    @Test
    fun `copy returns the byte count under the budget`() {
        val payload = ByteArray(5_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()

        val copied = BoundedStreams.copy(ByteArrayInputStream(payload), out, 8_192)

        assertEquals(5_000L, copied)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `copy accepts exactly the budget`() {
        val payload = ByteArray(1_024)
        val out = ByteArrayOutputStream()

        assertEquals(1_024L, BoundedStreams.copy(ByteArrayInputStream(payload), out, 1_024))
    }

    @Test
    fun `copy rejects one byte over the budget`() {
        val failure = runCatching {
            BoundedStreams.copy(ByteArrayInputStream(ByteArray(1_025)), ByteArrayOutputStream(), 1_024)
        }.exceptionOrNull()

        assertTrue("expected IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("1024"))
    }

    @Test
    fun `copy does not trust a declared size`() {
        // The stream claims 1 available byte and serves 4 KiB.
        val failure = runCatching {
            BoundedStreams.copy(LyingStream(ByteArray(4_096)), ByteArrayOutputStream(), 1_024)
        }.exceptionOrNull()

        assertTrue("expected IOException, got $failure", failure is IOException)
    }

    @Test
    fun `copy of an empty stream is allowed`() {
        assertEquals(0L, BoundedStreams.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 0))
    }

    @Test
    fun `copy rejects a negative budget`() {
        val failure = runCatching {
            BoundedStreams.copy(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), -1)
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun `readBytes returns the content under the budget`() {
        val payload = "skill archive".toByteArray()

        val bytes = BoundedStreams.readBytes(ByteArrayInputStream(payload), 1_024)

        assertArrayEquals(payload, bytes)
    }

    @Test
    fun `readBytes rejects an oversized document instead of buffering it`() {
        val failure = runCatching {
            BoundedStreams.readBytes(ByteArrayInputStream(ByteArray(2_048)), 1_024)
        }.exceptionOrNull()

        assertTrue("expected IOException, got $failure", failure is IOException)
    }
}
