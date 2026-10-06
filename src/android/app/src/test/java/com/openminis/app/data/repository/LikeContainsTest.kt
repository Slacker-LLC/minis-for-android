package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class LikeContainsTest {

    @Test
    fun plainTextIsWrappedForSubstringMatch() {
        assertEquals("%hello%", likeContains("hello"))
    }

    @Test
    fun sqlWildcardsAndTheEscapeCharacterAreEscaped() {
        assertEquals("%foo\\_bar%", likeContains("foo_bar"))
        assertEquals("%100\\%%", likeContains("100%"))
        assertEquals("%a\\\\b%", likeContains("a\\b"))
    }

    @Test
    fun theEscapeCharacterIsEscapedBeforeTheWildcardsItIntroduces() {
        // "\_" in the input must become "\\\_": a literal backslash, then a literal underscore.
        assertEquals("%\\\\\\_%", likeContains("\\_"))
    }
}
