package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Test

class RootCliInputTextTest {
    @Test
    fun spacesBecomePercentS() {
        assertEquals("a%sb", RootCliOffloadHandler.encodeInputText("a b"))
        assertEquals("hello%s%sworld", RootCliOffloadHandler.encodeInputText("hello  world"))
    }

    @Test
    fun apostrophesAndOtherCharactersSurvive() {
        assertEquals("don't", RootCliOffloadHandler.encodeInputText("don't"))
        assertEquals("\"quoted\"%s&%s中文", RootCliOffloadHandler.encodeInputText("\"quoted\" & 中文"))
    }
}
