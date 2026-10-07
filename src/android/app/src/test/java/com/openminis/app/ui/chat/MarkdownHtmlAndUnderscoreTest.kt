package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What model output actually contains: identifiers with underscores, <br>, entities, a little HTML. */
class MarkdownHtmlAndUnderscoreTest {
    private val colors = MdColors(
        text = Color.Black, codeText = Color.Black, codeBg = Color.White, inlineCodeText = Color.Black,
        inlineCodeBg = Color.White, link = Color.Blue, blockquote = Color.Gray, divider = Color.Gray,
        tableBorder = Color.Gray, tableHeaderBg = Color.White,
    )

    private fun text(s: String) = parseInline(s, colors).text

    @Test
    fun `underscores inside an identifier are kept`() {
        assertEquals("call shell_execute now", text("call shell_execute now"))
        assertEquals("snake_case_name and my_var_2", text("snake_case_name and my_var_2"))
        assertEquals("file_name.py", text("file_name.py"))
    }

    @Test
    fun `underscore emphasis at word edges still works`() {
        val italic = parseInline("an _italic_ word", colors)
        assertEquals("an italic word", italic.text)
        assertTrue(italic.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        val bold = parseInline("a __bold__ word", colors)
        assertEquals("a bold word", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `line breaks and entities are decoded`() {
        assertEquals("one\ntwo", text("one<br>two"))
        assertEquals("one\ntwo\nthree", text("one<br/>two<BR />three"))
        assertEquals("a & b < c > d \"e\"", text("a &amp; b &lt; c &gt; d &quot;e&quot;"))
        assertEquals("A B ©", text("A&nbsp;B &#169;").replace("&#xA9;", "©"))
        assertEquals("é", text("&#xE9;"))
    }

    @Test
    fun `known html tags style their content and unknown or unclosed ones stay literal`() {
        val sub = parseInline("H<sub>2</sub>O", colors)
        assertEquals("H2O", sub.text)
        assertTrue(sub.spanStyles.isNotEmpty())
        val bold = parseInline("<b>strong</b> text", colors)
        assertEquals("strong text", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals("<foo>x</foo>", text("<foo>x</foo>"))
        assertEquals("<b>still streaming", text("<b>still streaming"))
        assertEquals("a < b and c > d", text("a < b and c > d"))
        assertEquals("&unknown; &;", text("&unknown; &;"))
    }

    @Test
    fun `emphasis nests inside emphasis`() {
        val parsed = parseInline("**bold *italic* tail**", colors)
        assertEquals("bold italic tail", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
}
