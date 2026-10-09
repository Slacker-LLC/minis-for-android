package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GiveUpNoteTest {
    @Test
    fun theNoteSitsBetweenTheReasonAndTheProvidersWords() {
        assertEquals(
            "Server problem (5xx).\nRetried 3 times.\nDetail: no response",
            withGiveUpNote("Server problem (5xx).\nDetail: no response", "Retried 3 times."),
        )
    }

    @Test
    fun aReasonAloneGetsTheNoteAfterIt() {
        assertEquals("Server problem.\nRetried 3 times.", withGiveUpNote("Server problem.", "Retried 3 times."))
    }

    @Test
    fun noNoteLeavesTheTextAlone() {
        assertEquals("Server problem.\nDetail", withGiveUpNote("Server problem.\nDetail", null))
        assertEquals("Server problem.", withGiveUpNote("Server problem.", "  "))
    }
}
