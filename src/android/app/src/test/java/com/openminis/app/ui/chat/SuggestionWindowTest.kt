package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionPickTest {
    @Test fun showsFourDistinctValidCards() {
        val w = pickSuggestions(SuggestionPoolSize, seed = 7, uses = emptyMap())
        assertEquals(4, w.size)
        assertEquals(4, w.toSet().size)
        assertTrue(w.all { it in 0 until SuggestionPoolSize })
    }

    @Test fun sameSeedAndHistoryGiveTheSameCards() {
        assertEquals(pickSuggestions(12, 42, mapOf(3 to 5)), pickSuggestions(12, 42, mapOf(3 to 5)))
    }

    @Test fun differentChatsStartOnDifferentCards() {
        val sets = (1..20).map { pickSuggestions(12, it, emptyMap()).toSet() }.toSet()
        assertTrue("seeds should not all give the same four", sets.size > 1)
    }

    @Test fun mostUsedCardsAreAlwaysThere() {
        for (seed in 1..30) {
            val w = pickSuggestions(12, seed, mapOf(2 to 9, 7 to 4, 5 to 1))
            assertTrue("seed $seed lost the favourite", 2 in w)
            assertTrue("seed $seed lost the second favourite", 7 in w)
        }
    }

    @Test fun unusedCardsAreNeverRankedAsFavourites() {
        val w = pickSuggestions(12, 3, mapOf(4 to 2))
        assertTrue(4 in w)
        assertEquals(4, w.size)
    }

    @Test fun smallOrEmptyPoolsAreSafe() {
        assertEquals(emptyList<Int>(), pickSuggestions(0, 1, emptyMap()))
        assertEquals(3, pickSuggestions(3, 1, emptyMap()).size)
        assertEquals(4, pickSuggestions(12, 1, mapOf(99 to 5)).size)
    }

    @Test fun usesRoundTripAndIgnoreGarbage() {
        val uses = mapOf(1 to 3, 10 to 1)
        assertEquals(uses, parseUses(formatUses(uses)))
        assertEquals(emptyMap<Int, Int>(), parseUses(null))
        assertEquals(mapOf(2 to 4), parseUses("x,2:4,3:0,5:-1,a:b,7"))
    }
}
