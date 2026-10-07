package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A web address typed as plain text is a link, the way it is in any chat app; text that only looks similar is not. */
class BareUrlLinkTest {
    private val colors = MdColors(
        text = Color.Black, codeText = Color.Black, codeBg = Color.White, inlineCodeText = Color.Black,
        inlineCodeBg = Color.White, link = Color.Blue, blockquote = Color.Gray, divider = Color.Gray,
        tableBorder = Color.Gray, tableHeaderBg = Color.White,
    )

    private fun urls(text: String): List<String> =
        parseInline(text, colors).getStringAnnotations("url", 0, text.length + 64).map { it.item }

    @Test
    fun `a bare address becomes a link and keeps the text around it`() {
        val parsed = parseInline("see https://example.com/a?b=1 now", colors)
        assertEquals("see https://example.com/a?b=1 now", parsed.text)
        assertEquals(listOf("https://example.com/a?b=1"), urls("see https://example.com/a?b=1 now"))
    }

    @Test
    fun `sentence punctuation and a closing bracket stay outside the link`() {
        assertEquals(listOf("https://a.b/c"), urls("go to https://a.b/c."))
        assertEquals(listOf("https://a.b/c"), urls("(https://a.b/c)"))
        assertEquals(listOf("https://a.b/c"), urls("链接https://a.b/c，然后"))
        // A balanced bracket belongs to the address.
        assertEquals(listOf("https://a.b/w_(x)"), urls("https://a.b/w_(x)"))
    }

    @Test
    fun `an angle-bracket autolink and a markdown link each give one link`() {
        assertEquals(listOf("https://a.b"), urls("<https://a.b>"))
        assertEquals(listOf("https://a.b"), urls("[docs](https://a.b)"))
        assertEquals("docs", parseInline("[docs](https://a.b)", colors).text)
    }

    @Test
    fun `things that only look like addresses are left alone`() {
        assertEquals(emptyList<String>(), urls("https:// and http://"))
        assertEquals(emptyList<String>(), urls("xhttps://a.b"))
        assertEquals(emptyList<String>(), urls("mailto:x and ftp:/a.b"))
        // An unclosed "<" is not an angle autolink, but the address after it is still an address.
        assertEquals(listOf("https://a.b"), urls("<https://a.b"))
    }

    @Test
    fun `www addresses and e-mail addresses are links too`() {
        assertEquals(listOf("http://www.example.com/x"), urls("see www.example.com/x now"))
        assertEquals(listOf("mailto:a.b@example.com"), urls("write to a.b@example.com."))
    }
}
