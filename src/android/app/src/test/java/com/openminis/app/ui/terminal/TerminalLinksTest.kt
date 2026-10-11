package com.openminis.app.ui.terminal

import com.openminis.app.ui.terminal.emulator.TerminalLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {
    @Test
    fun aWebLinkIsRecognisedAndTrimmed() {
        assertEquals("https://example.com/a?b=1#c", TerminalLinks.webLink("  https://example.com/a?b=1#c \n"))
        assertEquals("http://localhost:8080", TerminalLinks.webLink("http://localhost:8080"))
        assertEquals("HTTPS://EXAMPLE.COM", TerminalLinks.webLink("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun trailingSentencePunctuationIsNotPartOfTheLink() {
        assertEquals("https://example.com", TerminalLinks.webLink("https://example.com."))
        assertEquals("https://example.com/x", TerminalLinks.webLink("https://example.com/x),"))
    }

    @Test
    fun otherSchemesAreNeverOffered() {
        assertNull(TerminalLinks.webLink("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(TerminalLinks.webLink("file:///data/data/x"))
        assertNull(TerminalLinks.webLink("tel:10086"))
        assertNull(TerminalLinks.webLink("javascript:alert(1)"))
        assertNull(TerminalLinks.webLink("ftp://example.com"))
    }

    @Test
    fun textThatIsMoreThanOneLinkOrNotALinkIsRejected() {
        assertNull(TerminalLinks.webLink("https://a.com https://b.com"))
        assertNull(TerminalLinks.webLink("see https://example.com"))
        assertNull(TerminalLinks.webLink("https://"))
        assertNull(TerminalLinks.webLink(""))
        assertNull(TerminalLinks.webLink("example.com"))
    }

    @Test
    fun anAbsurdlyLongStringIsRejected() {
        assertNull(TerminalLinks.webLink("https://example.com/" + "a".repeat(3000)))
    }
}
