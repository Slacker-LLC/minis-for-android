package com.openminis.app.data

import com.openminis.app.data.repository.reorderSlotEntries
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the tolerant permutation rule used when reordering one model slot. */
class ModelSlotReorderTest {
    @Test
    fun `a full permutation is applied verbatim`() {
        assertEquals(
            listOf("coding", "daily", "translate"),
            reorderSlotEntries(
                current = listOf("daily", "coding", "translate"),
                newOrder = listOf("coding", "daily", "translate"),
            ),
        )
    }

    @Test
    fun `unknown ids are dropped rather than inserted`() {
        assertEquals(
            listOf("b", "a"),
            reorderSlotEntries(current = listOf("a", "b"), newOrder = listOf("b", "ghost", "a")),
        )
    }

    @Test
    fun `an entry added concurrently is appended rather than lost`() {
        assertEquals(
            listOf("b", "a", "new"),
            reorderSlotEntries(current = listOf("a", "b", "new"), newOrder = listOf("b", "a")),
        )
    }

    @Test
    fun `duplicate ids in the incoming order are collapsed`() {
        assertEquals(
            listOf("b", "a", "c"),
            reorderSlotEntries(current = listOf("a", "b", "c"), newOrder = listOf("b", "b", "a")),
        )
    }

    @Test
    fun `no slot entry is ever lost or duplicated`() {
        val current = listOf("a", "b", "c", "d")
        val result = reorderSlotEntries(current, newOrder = listOf("d", "ghost", "b"))
        assertEquals(current.sorted(), result.sorted())
        assertEquals(current.size, result.size)
    }
}
