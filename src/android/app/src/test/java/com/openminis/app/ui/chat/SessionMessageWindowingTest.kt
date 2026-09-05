package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageWindowingTest {

    @Test
    fun testEmptySessionNotWindowed() {
        val window = SessionMessageWindowing.calculateInitialWindow(totalCount = 0, windowSize = 100)
        assertFalse(window.isWindowed)
        assertEquals(0, window.offset)
        assertEquals(0, window.limit)
    }

    @Test
    fun testShortSessionNotWindowed() {
        val window = SessionMessageWindowing.calculateInitialWindow(totalCount = 42, windowSize = 100)
        assertFalse(window.isWindowed)
        assertEquals(0, window.offset)
        assertEquals(42, window.limit)
    }

    @Test
    fun testExactWindowSizeNotWindowed() {
        val window = SessionMessageWindowing.calculateInitialWindow(totalCount = 100, windowSize = 100)
        assertFalse(window.isWindowed)
        assertEquals(0, window.offset)
        assertEquals(100, window.limit)
    }

    @Test
    fun testLongSessionWindowedToRecentSlice() {
        val window = SessionMessageWindowing.calculateInitialWindow(totalCount = 250, windowSize = 100)
        assertTrue(window.isWindowed)
        assertEquals(150, window.offset)
        assertEquals(100, window.limit)
    }
}
