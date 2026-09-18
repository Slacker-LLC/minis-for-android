package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-streaming-projection] Virtual-EOF projection contract.
 *
 * The projection is render-only sugar, so the tests that matter are: node types
 * stay stable while the stream is open, the terminal render is byte-identical to
 * the message, non-append input rebuilds instead of reusing stale state, and the
 * fail-closed guard refuses any projection that would drop settled content.
 */
class StreamingMarkdownProjectionTest {

    private fun project(source: String, isComplete: Boolean = false) =
        StreamingMarkdownProjection.project(source, isComplete)

    private fun render(source: String) =
        StreamingMarkdownProjection.projectOrFallback(source, isComplete = false)

    @Test
    fun `a finished document is never rewritten`() {
        val finished = "# Title\n\n```kotlin\nval x = 1\n```\n\n**bold** and | a | b |\n| --- | --- |\n"
        assertEquals(finished, project(finished, isComplete = true))
        assertEquals(finished, render(finished))
    }

    @Test
    fun `an open fence is closed virtually and stays a code block`() {
        val streamed = "```kotlin\nval x = 1"
        val projected = project(streamed)
        assertEquals("```kotlin\nval x = 1\n```", projected)
        // The virtual markers are never written back: the source is untouched.
        assertEquals("```kotlin\nval x = 1", streamed)
        // Idempotent: projecting the projection adds nothing further.
        assertEquals(projected, project(projected))
    }

    @Test
    fun `a closed fence is left alone`() {
        val closed = "```\ncode\n```"
        assertEquals(closed, project(closed))
    }

    @Test
    fun `an open inline marker is closed virtually for every kind`() {
        assertEquals("a **bold**", project("a **bold"))
        assertEquals("a __bold__", project("a __bold"))
        assertEquals("a ~~gone~~", project("a ~~gone"))
        assertEquals("a `code`", project("a `code"))
        // Nested: inline code closes first, then the outer emphasis.
        assertEquals("**a `b`**", project("**a `b"))
    }

    @Test
    fun `escaped and intraword markers are not treated as open`() {
        val escaped = "a \\*\\*not bold"
        assertEquals(escaped, project(escaped))
        val snake = "snake_case_name stays plain"
        assertEquals(snake, project(snake))
    }

    @Test
    fun `a half-typed link target is withheld`() {
        val streamed = "see [the docs](https://exa"
        val projected = project(streamed)
        assertEquals("see ", projected)
        assertTrue(streamed.startsWith(projected))
    }

    @Test
    fun `a pending link that would blank settled content falls back to the raw text`() {
        // The link opens before the last settled block boundary: truncating
        // there would erase a paragraph the user has already read, so the guard
        // (roadmap: projection shorter than the settled prefix → render raw)
        // rejects the projection.
        val streamed = "[see\n\npara line"
        assertEquals(streamed, render(streamed))
    }

    @Test
    fun `a candidate table header stays unpublished while its delimiter is unknown`() {
        val streamed = "para line\n\n| a | b |"
        val projected = project(streamed)
        // The settled paragraph (including its blank separator) survives; only
        // the ambiguous header line is withheld until the delimiter arrives.
        assertEquals("para line\n\n", projected)
        assertTrue(streamed.startsWith(projected))
    }

    @Test
    fun `a legal delimiter confirms the table and publishes everything`() {
        val streamed = "para line\n\n| a | b |\n| --- | --- |"
        assertEquals(streamed, project(streamed))
    }

    @Test
    fun `an illegal delimiter must not lose the header line`() {
        val streamed = "para line\n\n| a | b |\n|--|"
        // The delimiter is illegal, so the header is still not published: the
        // projection stops at the settled paragraph boundary. Nothing already
        // on screen is lost (the guard's rule), and the moment a legal
        // delimiter arrives the whole table appears at once.
        val projected = render(streamed)
        assertEquals("para line\n\n", projected)
        assertTrue(streamed.startsWith(projected))
    }

    @Test
    fun `plain prose streams through untouched`() {
        val prose = buildString {
            repeat(2_000) { append("line $it with ordinary content and no markers\n") }
        }
        assertEquals(prose, project(prose))
        assertEquals(prose, render(prose))
    }

    @Test
    fun `session rebuilds its baseline on non-append input`() {
        val session = StreamingMarkdownProjectionSession()
        session.project("first message", isComplete = false)
        assertEquals("first message", session.baseline)

        // A retry / edit replaces the text from the start: nothing from the old
        // baseline may leak into the new projection.
        val replaced = session.project("second message", isComplete = false)
        assertEquals("second message", session.baseline)
        assertFalse(replaced.renderedSource.contains("first"))

        // Appends keep working after a rebuild.
        val appended = session.project("second message **bold", isComplete = false)
        assertEquals("second message **bold**", appended.renderedSource)
        assertEquals("second message **bold", appended.originalSource)
    }

    @Test
    fun `terminal delivery only hands out a matching complete snapshot`() {
        val session = StreamingMarkdownProjectionSession()
        val streaming = session.project("half", isComplete = false)
        assertNull(streaming.verifiedTerminalSource("half"))

        val complete = session.project("done", isComplete = true)
        assertEquals("done", complete.verifiedTerminalSource("done"))
        // A stale snapshot (the text moved on) must never be treated as final.
        assertNull(complete.verifiedTerminalSource("done and more"))
    }

    @Test
    fun `an identity projection reports no virtual characters`() {
        val session = StreamingMarkdownProjectionSession()
        assertTrue(session.project("plain", isComplete = false).isIdentity)
        assertFalse(session.project("plain **bold", isComplete = false).isIdentity)
    }
}
