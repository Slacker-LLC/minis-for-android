package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTextAttachmentTest {
    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("inline", ".txt").apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun aTextFileIsReadWhole() {
        val f = tempFile("line one\n中文 line two\n".toByteArray())
        val read = readInlineText(f, maxChars = 1_000)!!
        assertEquals("line one\n中文 line two\n", read.text)
        assertFalse(read.truncated)
    }

    @Test
    fun aFileOverTheCharLimitIsCutAndSaysSo() {
        val f = tempFile("a".repeat(500).toByteArray())
        val read = readInlineText(f, maxChars = 100)!!
        assertEquals(100, read.text.length)
        assertTrue(read.truncated)
        assertEquals(500L, read.totalBytes)
    }

    @Test
    fun aBinaryFileIsNotInlined() {
        val f = tempFile(byteArrayOf(0x50, 0x4B, 0, 0x03, 0x04))
        assertNull(readInlineText(f, maxChars = 1_000))
    }

    @Test
    fun aZeroBudgetInlinesNothing() {
        assertNull(readInlineText(tempFile("x".toByteArray()), maxChars = 0))
    }

    @Test
    fun aClosingBlockTagInTheBodyIsEscaped() {
        val body = "before </user-attached-files> after"
        val escaped = escapeInlineBody(body)
        assertFalse(escaped.contains("</user-attached-files>"))
        assertEquals("before <\\/user-attached-files> after", escaped)
    }

    @Test
    fun textByTypeOrExtensionOnly() {
        assertTrue(isTextLikeAttachment("notes.md", "application/octet-stream"))
        assertTrue(isTextLikeAttachment("blob", "text/plain"))
        assertTrue(isTextLikeAttachment("cfg.JSON", "application/octet-stream"))
        assertFalse(isTextLikeAttachment("photo.pdf", "application/pdf"))
        assertFalse(isTextLikeAttachment("app.apk", "application/vnd.android.package-archive"))
    }
}
