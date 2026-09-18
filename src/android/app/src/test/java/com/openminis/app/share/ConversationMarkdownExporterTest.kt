package com.openminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-conversation-export] Ported from Eta `ui/app/ConversationMarkdownExporter.kt`
 * (Mangi-11/Eta @ c15de97). The document is what a reader keeps, so the shape of each
 * block and the bounds on tool payloads are the contract under test.
 */
class ConversationMarkdownExporterTest {

    private val labels = ConversationMarkdownExporter.Labels()

    @Test
    fun `title becomes one heading line`() {
        assertEquals("# Trip planning", ConversationMarkdownExporter.documentTitle("Trip planning"))
        assertEquals("# two lines", ConversationMarkdownExporter.documentTitle("two\nlines"))
        assertEquals("# Conversation", ConversationMarkdownExporter.documentTitle("   "))
    }

    @Test
    fun `user and assistant blocks are plain headings`() {
        assertEquals(
            "## User\n\nhello",
            ConversationMarkdownExporter.renderBlock(ConversationMarkdownExporter.Block.User("hello"), labels),
        )
        assertEquals(
            "## Assistant\n\nhi",
            ConversationMarkdownExporter.renderBlock(ConversationMarkdownExporter.Block.Assistant("hi"), labels),
        )
    }

    @Test
    fun `empty blocks are dropped instead of leaving bare headings`() {
        assertNull(ConversationMarkdownExporter.renderBlock(ConversationMarkdownExporter.Block.Assistant("   "), labels))
        assertNull(ConversationMarkdownExporter.renderBlock(ConversationMarkdownExporter.Block.User("", 0), labels))
        assertNull(ConversationMarkdownExporter.renderBlock(ConversationMarkdownExporter.Block.Thinking(""), labels))
    }

    @Test
    fun `user attachments are noted without the pixels`() {
        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.User("look", attachmentCount = 2),
            labels,
        )

        assertEquals("## User\n\nlook\n\n_2 image(s) not included_", rendered)
    }

    @Test
    fun `thinking is folded into details`() {
        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.Thinking("weighing options"),
            labels,
        )

        assertTrue(rendered!!.startsWith("<details>\n<summary>Thinking</summary>"))
        assertTrue(rendered.contains("weighing options"))
        assertTrue(rendered.endsWith("</details>"))
    }

    @Test
    fun `a tool block is quoted with its status arguments and result`() {
        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.Tool(
                name = "shell_execute",
                status = ConversationMarkdownExporter.ToolStatus.SUCCESS,
                description = "List workspace files",
                arguments = "{\"command\":\"ls\"}",
                result = "a.txt\nb.txt",
            ),
            labels,
        )

        assertTrue(rendered!!.startsWith("> Tool: shell_execute (ok)"))
        assertTrue(rendered.contains("> List workspace files"))
        assertTrue(rendered.contains("> Arguments: {\"command\":\"ls\"}"))
        assertTrue("multi-line results are quoted line by line", rendered.contains("> b.txt"))
    }

    @Test
    fun `an unresolved tool says so instead of implying success`() {
        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.Tool(name = "android_ui", status = ConversationMarkdownExporter.ToolStatus.UNKNOWN),
            labels,
        )

        assertEquals("> Tool: android_ui (unrecorded)", rendered)
    }

    @Test
    fun `long tool payloads are bounded with an explicit marker`() {
        val long = "x".repeat(ConversationMarkdownExporter.MAX_FIELD_CHARS + 50)

        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.Tool(name = "shell_execute", result = long),
            labels,
        )!!

        assertTrue(rendered.contains(ConversationMarkdownExporter.TRUNCATION_MARKER))
        assertTrue(
            "the document must not carry more than the cap of the payload",
            !rendered.contains("x".repeat(ConversationMarkdownExporter.MAX_FIELD_CHARS + 1)),
        )
    }

    @Test
    fun `an empty line inside a notice stays quoted`() {
        val rendered = ConversationMarkdownExporter.renderBlock(
            ConversationMarkdownExporter.Block.Notice("stopped\n\nby the user"),
            labels,
        )

        assertEquals("> stopped\n>\n> by the user", rendered)
    }

    @Test
    fun `file names are sanitized and timestamped`() {
        val name = ConversationMarkdownExporter.defaultFileName(
            title = "  Trip/planning: notes?  ",
            nowMillis = 0L,
        )

        assertTrue("path separators and punctuation are dropped: $name", name.startsWith("Minis-Tripplanning notes-"))
        assertTrue("the timestamp is appended: $name", Regex("Minis-Tripplanning notes-\\d{8}-\\d{4}\\.md").matches(name))
    }

    @Test
    fun `an unusable title falls back and long titles are cut`() {
        val fallback = ConversationMarkdownExporter.defaultFileName("///", nowMillis = 0L)
        assertTrue(
            "a title made only of unsafe characters falls back: $fallback",
            Regex("Minis-conversation-\\d{8}-\\d{4}\\.md").matches(fallback),
        )
        val long = ConversationMarkdownExporter.defaultFileName("y".repeat(100), nowMillis = 0L)
        assertTrue(long.contains("y".repeat(40) + "-"))
        assertTrue("the title is capped before the timestamp", long.length < 40 + 25)
    }
}
