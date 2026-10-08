package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteForReplyTest {
    @Test
    fun `every line is quoted and blank lines keep the quote in one block`() {
        assertEquals("> one", quoteForReply("one"))
        assertEquals("> a\n>\n> b", quoteForReply("a\n\nb"))
    }

    @Test
    fun `surrounding whitespace is dropped and an existing quote mark is kept as text`() {
        assertEquals("> x\n> > nested", quoteForReply("  x\n> nested \n"))
    }

    @Test
    fun `quotes go in front of the reply as separate blocks`() {
        assertEquals("> a\n\n> b\n\nmy reply", withQuotes(listOf("a", "b"), "  my reply "))
        assertEquals("my reply", withQuotes(emptyList(), "my reply"))
    }

    @Test
    fun `a sent message splits back into its quotes and its reply`() {
        val (quotes, rest) = splitLeadingQuotes(withQuotes(listOf("first\nline two", "second"), "the answer\n\n> not a leading quote"))
        assertEquals(listOf("first\nline two", "second"), quotes)
        assertEquals("the answer\n\n> not a leading quote", rest)
    }

    @Test
    fun `a message without a leading quote is left whole`() {
        assertEquals(emptyList<String>() to "plain text\n> later", splitLeadingQuotes("plain text\n> later"))
        assertEquals(listOf("only") to "", splitLeadingQuotes("> only"))
    }
}
