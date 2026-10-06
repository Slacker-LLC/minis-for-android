package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetAroundTest {

    @Test
    fun findsAMatchIgnoringCase() {
        assertEquals("Hello World", snippetAround("Hello World", "world"))
    }

    @Test
    fun noHitIsNull() {
        assertNull(snippetAround("Hello", "xyz"))
        assertNull(snippetAround("Hello", " "))
    }

    @Test
    fun aCharacterThatLowercasesToTwoCharsDoesNotShiftTheWindow() {
        // "İ".lowercase() is two chars; indexes taken from the lowercased copy
        // used to run past the end of the original.
        assertEquals("İA", snippetAround("İA", "A"))
    }

    @Test
    fun aLongRunOfThoseCharactersStillYieldsTheHit() {
        val text = "İ".repeat(200) + "needle" + "tail"
        val snippet = snippetAround(text, "needle")!!
        assertEquals(true, snippet.contains("needle"))
        assertEquals("…", snippet.take(1))
    }

    @Test
    fun truncationIsMarkedOnBothSides() {
        val text = "a".repeat(100) + "hit" + "b".repeat(100)
        val snippet = snippetAround(text, "hit", radius = 10)!!
        assertEquals("…aaaaaaaaaahitbbbbbbbbbb…", snippet)
    }
}
