package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionWindowTest {
    @Test fun showsFourDistinctValidCards() {
        val w = suggestionWindow(SuggestionPoolSize, seed = 7, page = 0)
        assertEquals(4, w.size)
        assertEquals(4, w.toSet().size)
        assertTrue(w.all { it in 0 until SuggestionPoolSize })
    }

    @Test fun sameSeedAndPageAlwaysGiveTheSameCards() {
        assertEquals(suggestionWindow(12, 42, 1), suggestionWindow(12, 42, 1))
    }

    @Test fun shuffleShowsNewCardsUntilThePoolIsUsedUp() {
        val seen = mutableSetOf<Int>()
        for (page in 0 until 3) seen += suggestionWindow(12, 5, page)
        assertEquals("three pages of four should cover all twelve", 12, seen.size)
        assertNotEquals(suggestionWindow(12, 5, 0), suggestionWindow(12, 5, 1))
    }

    @Test fun differentChatsStartOnDifferentWindows() {
        val firsts = (1..20).map { suggestionWindow(12, it, 0) }.toSet()
        assertTrue("seeds should not all give the same four", firsts.size > 1)
    }

    @Test fun smallOrEmptyPoolsAreSafe() {
        assertEquals(emptyList<Int>(), suggestionWindow(0, 1, 0))
        assertEquals(3, suggestionWindow(3, 1, 0).size)
        assertEquals(4, suggestionWindow(12, 1, -3).size)
    }
}

class EnabledSuggestionsTest {
    @org.junit.Test fun offCardsAreLeftOut() {
        org.junit.Assert.assertEquals(listOf(0, 2, 3), enabledSuggestions(4, setOf(1)))
    }

    @org.junit.Test fun turningEverythingOffFallsBackToTheWholePool() {
        org.junit.Assert.assertEquals(listOf(0, 1, 2), enabledSuggestions(3, setOf(0, 1, 2)))
    }

    @org.junit.Test fun staleIndicesFromAnOlderPoolAreIgnored() {
        org.junit.Assert.assertEquals(listOf(0, 1, 2), enabledSuggestions(3, setOf(9, 10)))
        org.junit.Assert.assertEquals(listOf(1, 2), enabledSuggestions(3, setOf(0, 99)))
    }
}
