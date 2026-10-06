package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeModalitiesTest {
    private val shownOut = setOf("image", "audio")

    @Test
    fun savingWithoutTouchingAnythingKeepsTextAndImageOutput() {
        assertEquals(
            listOf("text", "image"),
            mergeModalities(listOf("text", "image"), shownOut, enabled = setOf("image")),
        )
    }

    @Test
    fun turningOffTheOnlyShownOutputKeepsText() {
        assertEquals(listOf("text"), mergeModalities(listOf("text", "image"), shownOut, enabled = emptySet()))
    }

    @Test
    fun turningOnAShownOutputAddsItAfterWhatIsKept() {
        assertEquals(listOf("text", "audio"), mergeModalities(listOf("text"), shownOut, enabled = setOf("audio")))
    }

    @Test
    fun modalitiesThePageDoesNotShowSurvive() {
        val shownIn = setOf("image", "pdf", "audio", "video")
        assertEquals(
            listOf("text", "file"),
            mergeModalities(listOf("text", "image", "file"), shownIn, enabled = emptySet()),
        )
        // video as an output is not on this page at all.
        assertEquals(listOf("video", "image"), mergeModalities(listOf("video"), shownOut, enabled = setOf("image")))
    }

    @Test
    fun nothingIsDuplicated() {
        assertEquals(
            listOf("text", "image"),
            mergeModalities(listOf("text", "image", "image"), shownOut, enabled = setOf("image")),
        )
    }
}
