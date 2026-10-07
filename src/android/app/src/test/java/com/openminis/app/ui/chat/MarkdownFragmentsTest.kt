package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long reply is drawn as fragments; they are cut where the parser says a top-level block starts. */
class MarkdownFragmentsTest {
    @Test
    fun `paragraphs, headings and fences are separate fragments`() {
        val f = splitMarkdownIntoBlockTexts("# T\n\npara one\n\npara two\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\ntail")
        assertEquals(listOf("# T", "para one", "para two", "```kotlin\nval a = 1\n\nval b = 2\n```", "tail"), f)
    }

    @Test
    fun `a list with blank lines between items, and an item with several paragraphs, stays one fragment`() {
        val list = "1. first\n\n   more of first\n\n2. second\n\n   ```\n   code\n\n   more code\n   ```\n3. third"
        val f = splitMarkdownIntoBlockTexts("intro\n\n$list\n\nafter")
        assertEquals(listOf("intro", list, "after"), f)
    }

    @Test
    fun `tilde fences and longer fences keep their blank lines`() {
        val tilde = "~~~\na\n\nb\n~~~"
        val long = "````md\n```\ninner\n\nstill inner\n```\n````"
        assertEquals(listOf(tilde, long), splitMarkdownIntoBlockTexts("$tilde\n\n$long"))
    }

    @Test
    fun `a table and the paragraph above it are two fragments`() {
        val f = splitMarkdownIntoBlockTexts("intro line\n| a | b |\n|---|---|\n| 1 | 2 |\n\nafter")
        assertEquals(listOf("intro line", "| a | b |\n|---|---|\n| 1 | 2 |", "after"), f)
    }

    @Test
    fun `the cut points do not move while a reply grows`() {
        val full = "# T\n\npara one\n\n- a\n- b\n\nlast para"
        val early = splitMarkdownIntoBlockTexts(full.substringBefore("last para"))
        val late = splitMarkdownIntoBlockTexts(full)
        assertEquals(early, late.take(early.size))
    }

    @Test
    fun `reference definitions travel with the fragments that may use them`() {
        val f = splitMarkdownIntoBlockTexts("See [docs][d].\n\n```\n[x]\n```\n\n[d]: https://example.com \"Docs\"\n")
        assertTrue(f[0], f[0].contains("[d]: <https://example.com> \"Docs\"", ignoreCase = true))
        assertTrue("never into a code fence", f.single { it.startsWith("```") }.let { !it.contains("[d]:") })
        val ann = parseInlineForTest(runBlockingBlocks(f[0]))
        assertEquals(listOf("https://example.com"), ann)
    }

    @Test
    fun `a message with footnote definitions is one fragment, so its footnote list can end it`() {
        val md = "Claim.[^1]\n\nMore text.\n\n[^1]: Source."
        assertEquals(listOf(md), splitMarkdownIntoBlockTexts(md))
    }

    private fun runBlockingBlocks(md: String) = kotlinx.coroutines.runBlocking { parseMarkdownBlocks(md) }

    private fun parseInlineForTest(blocks: List<MdBlock>): List<String> {
        val colors = MdColors(
            androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.White,
            androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Blue,
            androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Gray,
            androidx.compose.ui.graphics.Color.White,
        )
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first()
        val a = parseInline(p.raw, colors)
        return a.getStringAnnotations("url", 0, a.length).map { it.item }
    }
}
