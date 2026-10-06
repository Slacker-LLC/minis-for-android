package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSelectionTest {

    @Test
    fun selectAllSelectsTheVisibleRows() {
        assertEquals(setOf("a", "b"), toggledSelection(emptySet(), listOf("a", "b")))
    }

    @Test
    fun theSecondPressDeselectsThem() {
        val first = toggledSelection(emptySet(), listOf("a", "b"))
        assertEquals(emptySet<String>(), toggledSelection(first, listOf("a", "b")))
    }

    @Test
    fun aPartialSelectionIsCompletedNotCleared() {
        assertEquals(setOf("a", "b", "c"), toggledSelection(setOf("a"), listOf("a", "b", "c")))
    }

    @Test
    fun rowsHiddenBySearchAreNeitherAddedNorRemoved() {
        // Three sessions, the search shows only "b".
        assertEquals(setOf("b"), toggledSelection(emptySet(), listOf("b")))
        // "x" was selected before the search narrowed the list: deselecting the visible row keeps it.
        assertEquals(setOf("x"), toggledSelection(setOf("x", "b"), listOf("b")))
    }

    @Test
    fun anEmptyListChangesNothing() {
        assertEquals(setOf("x"), toggledSelection(setOf("x"), emptyList()))
    }
}
