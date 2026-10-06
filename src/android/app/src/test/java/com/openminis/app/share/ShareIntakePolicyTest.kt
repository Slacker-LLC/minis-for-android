package com.openminis.app.share

import com.openminis.app.share.ShareIntakePolicy.SingleSendSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntakePolicyTest {
    private fun text(v: String) = PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, v)
    private fun file(v: String) = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, v)

    @Test
    fun aTextFileSharedAsPlainTextIsReadFromItsStream() {
        assertEquals(SingleSendSource.STREAM, ShareIntakePolicy.singleSendSource("text/plain", null, hasStream = true))
        assertEquals(SingleSendSource.STREAM, ShareIntakePolicy.singleSendSource("text/plain", "  ", hasStream = true))
    }

    @Test
    fun inlineTextWinsWhenBothArePresent() {
        assertEquals(SingleSendSource.TEXT, ShareIntakePolicy.singleSendSource("text/plain", "https://x", hasStream = true))
    }

    @Test
    fun otherTypesUseTheStreamAndFallBackToText() {
        assertEquals(SingleSendSource.STREAM, ShareIntakePolicy.singleSendSource("image/png", "caption", hasStream = true))
        assertEquals(SingleSendSource.TEXT, ShareIntakePolicy.singleSendSource("text/html", "<b>x</b>", hasStream = false))
    }

    @Test
    fun anEmptySendCarriesNothing() {
        assertEquals(SingleSendSource.NONE, ShareIntakePolicy.singleSendSource("text/plain", null, hasStream = false))
        assertEquals(SingleSendSource.NONE, ShareIntakePolicy.singleSendSource("image/png", " ", hasStream = false))
    }

    @Test
    fun mergeKeepsOrderAndDropsDuplicates() {
        val merged = ShareIntakePolicy.merge(listOf(text("a"), file("f1")), listOf(file("f1"), text("b")), { 1 })
        assertEquals(listOf(text("a"), file("f1"), text("b")), merged.accepted)
        assertTrue(merged.rejected.isEmpty())
    }

    @Test
    fun theItemsOverTheCountCapAreRejected() {
        val existing = (1..49).map { text("t$it") }
        val merged = ShareIntakePolicy.merge(existing, listOf(text("x50"), text("x51"), text("x52")), { 0 })
        assertEquals(50, merged.accepted.size)
        assertEquals(listOf(text("x51"), text("x52")), merged.rejected)
    }

    @Test
    fun queuedItemsAreNeverRejected() {
        val existing = (1..60).map { text("t$it") }
        val merged = ShareIntakePolicy.merge(existing, listOf(text("new")), { 0 }, maxItems = 50)
        assertEquals(existing, merged.accepted)
        assertEquals(listOf(text("new")), merged.rejected)
    }

    @Test
    fun attachmentBytesAreCappedAcrossHandoffs() {
        val sizes = mapOf("a" to 30L, "b" to 30L, "c" to 30L, "small" to 1L)
        val merged = ShareIntakePolicy.merge(
            existing = listOf(file("a"), file("b")),
            incoming = listOf(file("c"), file("small")),
            sizeOf = { sizes[it.value] ?: 0L },
            maxBytes = 100L,
        )
        assertEquals(listOf(file("a"), file("b"), file("c"), file("small")), merged.accepted)
        val tight = ShareIntakePolicy.merge(
            existing = listOf(file("a"), file("b")),
            incoming = listOf(file("c"), file("small")),
            sizeOf = { sizes[it.value] ?: 0L },
            maxBytes = 70L,
        )
        // c (30) would take 60 to 90 > 70; the 1-byte file after it still fits.
        assertEquals(listOf(file("a"), file("b"), file("small")), tight.accepted)
        assertEquals(listOf(file("c")), tight.rejected)
    }
}
