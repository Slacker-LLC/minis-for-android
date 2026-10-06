package com.openminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class CsvParseTest {
    private fun parse(text: String, sep: Char = ',', max: Int = 200) = parseCsv(StringReader(text), sep, max)

    @Test
    fun aQuotedFieldWithALineBreakIsOneRecord() {
        val page = parse("name,note\nalice,\"first line\nsecond line\"\n")
        assertEquals(
            listOf(listOf("name", "note"), listOf("alice", "first line\nsecond line")),
            page.rows,
        )
        assertFalse(page.truncated)
    }

    @Test
    fun separatorsAndDoubledQuotesInsideQuotesAreKept() {
        val page = parse("a,\"x,y\",\"say \"\"hi\"\"\"\n")
        assertEquals(listOf(listOf("a", "x,y", "say \"hi\"")), page.rows)
    }

    @Test
    fun windowsLineEndingsAndAMissingFinalNewlineWork() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parse("a,b\r\nc,d").rows)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parse("a,b\r\nc,d\r\n").rows)
    }

    @Test
    fun aCrLfInsideQuotesBecomesOneNewline() {
        assertEquals(listOf(listOf("x\ny")), parse("\"x\r\ny\"").rows)
    }

    @Test
    fun tabSeparatedWorks() {
        assertEquals(listOf(listOf("a", "b c", "d")), parse("a\tb c\td", sep = '\t').rows)
    }

    @Test
    fun theCapCountsRecordsNotPhysicalLines() {
        val text = "h1,h2\n" + "r1,\"multi\nline\nfield\"\n" + "r2,x\n" + "r3,y\n"
        val page = parse(text, max = 3)
        assertEquals(3, page.rows.size)
        assertEquals("multi\nline\nfield", page.rows[1][1])
        assertEquals("r2", page.rows[2][0])
        assertTrue(page.truncated)
    }

    @Test
    fun exactlyTheCapIsNotTruncated() {
        val page = parse("a\nb\nc\n", max = 3)
        assertEquals(3, page.rows.size)
        assertFalse(page.truncated)
    }

    @Test
    fun emptyInputHasNoRowsAndABlankLineIsAnEmptyRow() {
        assertEquals(emptyList<List<String>>(), parse("").rows)
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), parse("a\n\nb").rows)
    }

    @Test
    fun anUnclosedQuoteTakesTheRestAsOneField() {
        assertEquals(listOf(listOf("a", "b\nc,d")), parse("a,\"b\nc,d").rows)
    }
}
