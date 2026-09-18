package com.openminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from Eta `agent/model/ResponsesCitationFormatter.kt` (Mangi-11/Eta @
 * c15de97) plus the stream-side rules this app needs (see the class KDoc).
 */
class ResponsesCitationFormatterTest {

    @Test
    fun `read accepts the flat annotation shape`() {
        val citation = ResponsesCitationFormatter.read(
            JSONObject()
                .put("type", "url_citation")
                .put("url", "https://example.com/a")
                .put("title", "Example")
                .put("start_index", 3)
                .put("end_index", 11),
        )

        assertEquals(3, citation?.start)
        assertEquals(11, citation?.end)
        assertEquals("https://example.com/a", citation?.url)
        assertEquals("Example", citation?.title)
    }

    @Test
    fun `read accepts the nested annotation shape`() {
        val citation = ResponsesCitationFormatter.read(
            JSONObject().put(
                "url_citation",
                JSONObject()
                    .put("url", "http://example.com/b")
                    .put("title", "Nested")
                    .put("start_index", 0)
                    .put("end_index", 4),
            ),
        )

        assertEquals("http://example.com/b", citation?.url)
        assertEquals(4, citation?.end)
    }

    @Test
    fun `read rejects annotations without an http url`() {
        assertNull(ResponsesCitationFormatter.read(JSONObject().put("url", "ftp://example.com/a")))
        assertNull(ResponsesCitationFormatter.read(JSONObject().put("url", "file:///etc/passwd")))
        assertNull(ResponsesCitationFormatter.read(JSONObject().put("title", "no url at all")))
        assertNull(
            ResponsesCitationFormatter.read(
                JSONObject().put("url_citation", JSONObject().put("url", "javascript:alert(1)")),
            ),
        )
    }

    @Test
    fun `read leaves missing offsets null`() {
        val citation = ResponsesCitationFormatter.read(
            JSONObject().put("url", "https://example.com/c"),
        )

        assertNull(citation?.start)
        assertNull(citation?.end)
    }

    @Test
    fun `inline marker escapes an angle bracket in the url`() {
        assertEquals(
            " [[2]](<https://example.com/a%3Eb>)",
            ResponsesCitationFormatter.inlineMarker(2, "https://example.com/a>b"),
        )
    }

    @Test
    fun `citation ending at the stream head is inlined`() {
        val stream = ResponsesCitationStream()

        val marker = stream.accept(citation("https://example.com/a", 0, 5), streamedLength = 5)

        assertEquals(" [[1]](<https://example.com/a>)", marker)
        assertNull(stream.trailingSources())
    }

    @Test
    fun `citation behind the stream head degrades to the source list`() {
        val stream = ResponsesCitationStream()

        assertNull(stream.accept(citation("https://example.com/a", 0, 5), streamedLength = 40))

        val block = stream.trailingSources()
        assertTrue(block!!.startsWith("\n\n来源："))
        assertTrue(block.contains("- [1] [Example](<https://example.com/a>)"))
    }

    @Test
    fun `citation with inverted offsets is not inlined`() {
        val stream = ResponsesCitationStream()

        assertNull(stream.accept(citation("https://example.com/a", 9, 4), streamedLength = 4))
        assertTrue(stream.trailingSources()!!.contains("https://example.com/a"))
    }

    @Test
    fun `citation without offsets is not inlined`() {
        val stream = ResponsesCitationStream()

        assertNull(stream.accept(citation("https://example.com/a", null, null), streamedLength = 4))
        assertTrue(stream.trailingSources()!!.contains("https://example.com/a"))
    }

    @Test
    fun `a repeated url keeps its first number and is listed once`() {
        val stream = ResponsesCitationStream()

        assertNull(stream.accept(citation("https://example.com/a", 0, 3), streamedLength = 12))
        assertNull(stream.accept(citation("https://example.com/a", 4, 7), streamedLength = 12))
        // Both are behind the head, so both are listed; the second `a` never
        // takes a second number.
        assertNull(stream.accept(citation("https://example.com/b", 8, 11), streamedLength = 12))

        val block = stream.trailingSources()!!
        assertEquals(1, Regex("https://example\\.com/a").findAll(block).count())
        assertTrue(block.contains("- [1] [Example](<https://example.com/a>)"))
        assertTrue(block.contains("- [2] [Example](<https://example.com/b>)"))
    }

    @Test
    fun `trailing sources are emitted once`() {
        val stream = ResponsesCitationStream()
        stream.accept(citation("https://example.com/a", 0, 3), streamedLength = 12)

        assertTrue(stream.trailingSources()!!.isNotEmpty())
        assertNull(stream.trailingSources())
    }

    @Test
    fun `non http citations are ignored entirely`() {
        val stream = ResponsesCitationStream()

        assertNull(stream.accept(citation("ftp://example.com/a", 0, 3), streamedLength = 3))
        assertNull(stream.trailingSources())
    }

    @Test
    fun `source list falls back to a numbered label when the title is blank`() {
        val block = ResponsesCitationFormatter.sourcesBlock(
            listOf(ResponsesCitationFormatter.Citation(null, null, "https://example.com/a", "")),
            mapOf("https://example.com/a" to 3),
        )

        assertTrue(block.contains("- [3] [来源 1](<https://example.com/a>)"))
    }

    private fun citation(url: String, start: Int?, end: Int?) =
        ResponsesCitationFormatter.Citation(start = start, end = end, url = url, title = "Example")
}
