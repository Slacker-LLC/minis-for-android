package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuInputTextTest {
    @Test
    fun spacesBecomePercentS() {
        assertEquals("a%sb", ShizukuOffloadHandler.encodeInputText("a b"))
        assertEquals("hello%s%sworld", ShizukuOffloadHandler.encodeInputText("hello  world"))
    }

    @Test
    fun apostrophesAndOtherCharactersSurvive() {
        assertEquals("don't", ShizukuOffloadHandler.encodeInputText("don't"))
        assertEquals("\"quoted\"%s&%s中文", ShizukuOffloadHandler.encodeInputText("\"quoted\" & 中文"))
    }
}
