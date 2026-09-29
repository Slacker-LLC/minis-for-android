package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackOrderTest {
    @Test
    fun `current member in the middle tries following members then wraps`() {
        assertEquals(listOf("c", "a"), fallbackEntryIdsInAttemptOrder(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `current member at the end wraps to the beginning`() {
        assertEquals(listOf("a", "b"), fallbackEntryIdsInAttemptOrder(listOf("a", "b", "c"), "c"))
    }

    @Test
    fun `missing current member preserves existing index-one start`() {
        assertEquals(listOf("b", "c"), fallbackEntryIdsInAttemptOrder(listOf("a", "b", "c"), "missing"))
    }
}
