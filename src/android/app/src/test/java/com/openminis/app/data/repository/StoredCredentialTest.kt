package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredCredentialTest {
    @Test
    fun surroundingSpaceQuotesAndBearerAreRemoved() {
        assertEquals("sk-abc", normalizeStoredCredential("  \"sk-abc\"\n"))
        assertEquals("sk-abc", normalizeStoredCredential("Bearer sk-abc"))
    }

    @Test
    fun aBreakInsideAPastedKeyIsRemovedToo() {
        // trim() alone left this in the Authorization header, and OkHttp refused it ("Unexpected char 0x0a").
        assertEquals("sk-abcdef", normalizeStoredCredential("sk-abc\ndef"))
        assertEquals("sk-abcdef", normalizeStoredCredential("sk-abc\r\n def\t"))
    }
}
