package com.openminis.app.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinisDocumentsProviderTest {
    @Test
    fun `global document ids stay within exposed scopes`() {
        assertTrue(MinisDocumentsProvider.isSafeDocumentId("memory/SOUL.md"))
        assertTrue(MinisDocumentsProvider.isSafeDocumentId("skills/code/review.md"))
        assertTrue(MinisDocumentsProvider.isSafeDocumentId("shared/notes/today.txt"))
    }

    @Test
    fun `document ids reject traversal hidden and non-global paths`() {
        assertFalse(MinisDocumentsProvider.isSafeDocumentId("../outside"))
        assertFalse(MinisDocumentsProvider.isSafeDocumentId("shared/../outside"))
        assertFalse(MinisDocumentsProvider.isSafeDocumentId("shared/.hidden"))
        assertFalse(MinisDocumentsProvider.isSafeDocumentId("workspace/file.txt"))
        assertFalse(MinisDocumentsProvider.isSafeDocumentId("shared//file.txt"))
    }
}
