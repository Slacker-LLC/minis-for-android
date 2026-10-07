package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.chat.md.Align
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a model's reply turns into when the whole document goes through the parser and the chat's adapter. */
class MarkdownBlockAdapterTest {
    private val colors = MdColors(
        text = Color.Black, codeText = Color.Black, codeBg = Color.White, inlineCodeText = Color.Black,
        inlineCodeBg = Color.White, link = Color.Blue, blockquote = Color.Gray, divider = Color.Gray,
        tableBorder = Color.Gray, tableHeaderBg = Color.White,
    )

    private fun blocks(md: String): List<MdBlock> = runBlocking { parseMarkdownBlocks(md) }
    private fun kinds(md: String) = blocks(md).map { it::class.simpleName }
    private fun text(b: MdBlock): String = parseInline((b as? MdBlock.Paragraph)?.raw ?: (b as MdBlock.Heading).text, colors).text

    @Test
    fun `setext headings are headings`() {
        val b = blocks("Title\n=====\n\nSub\n---\n\nbody")
        assertEquals(listOf("Heading", "Heading", "Paragraph"), kinds("Title\n=====\n\nSub\n---\n\nbody"))
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        assertEquals(2, (b[1] as MdBlock.Heading).level)
    }

    @Test
    fun `a number followed by a word is a paragraph, not a list`() {
        assertEquals(listOf("Paragraph"), kinds("2026 年有 3 次发布。"))
        assertEquals(listOf("Paragraph"), kinds("10 apples are red"))
        assertEquals(listOf("OrderedList"), kinds("1) one\n2) two"))
        val list = blocks("3. three\n4. four")[0] as MdBlock.OrderedList
        assertEquals(3, list.startNum)
    }

    @Test
    fun `fences of any length and either character hold code`() {
        val b = blocks("````markdown\n```kotlin\nval a = 1\n```\n````\n\n~~~\nplain\n~~~")
        assertEquals(listOf("CodeBlock", "CodeBlock"), b.map { it::class.simpleName })
        assertEquals("```kotlin\nval a = 1\n```", (b[0] as MdBlock.CodeBlock).code)
        assertEquals("markdown", (b[0] as MdBlock.CodeBlock).language)
        assertEquals("plain", (b[1] as MdBlock.CodeBlock).code)
        // an unclosed fence runs to the end, as while a reply is still streaming
        assertEquals("line1\nline2", (blocks("```\nline1\nline2")[0] as MdBlock.CodeBlock).code)
    }

    @Test
    fun `indented code is code after a blank line but a paragraph continuation inside a paragraph`() {
        assertEquals(listOf("Paragraph", "CodeBlock"), kinds("text\n\n    code here"))
        assertEquals(listOf("Paragraph"), kinds("text\n    still the paragraph"))
    }

    @Test
    fun `tables keep alignment, escaped pipes and pipes inside code`() {
        val t = blocks("| a | b | c |\n|:--|:-:|--:|\n| 1 | x \\| y | `p|q` |\n")[0] as MdBlock.Table
        assertEquals(listOf(Align.LEFT, Align.CENTER, Align.RIGHT), t.aligns)
        assertEquals(listOf("a", "b", "c"), t.headers)
        assertEquals(listOf("1", "x | y", "`p|q`"), t.rows[0])
        // no outer pipes
        val t2 = blocks("a | b\n--|--\n1 | 2")[0] as MdBlock.Table
        assertEquals(listOf("1", "2"), t2.rows[0])
        // a lone "---" under a line is a heading underline, not a table
        assertEquals(listOf("Heading"), kinds("a | b\n---"))
    }

    @Test
    fun `task lists, mixed task lists and nested lists`() {
        val tasks = blocks("- [x] done\n- [ ] todo")[0] as MdBlock.TaskList
        assertEquals(listOf(true, false), tasks.items.map { it.checked })
        val mixed = blocks("- [x] done\n- plain")[0] as MdBlock.UnorderedList
        assertEquals("☑ done", mixed.items[0].text)
        assertEquals("plain", mixed.items[1].text)
        val nested = blocks("- a\n  - b\n    - c\n- d")[0] as MdBlock.UnorderedList
        assertEquals(2, nested.items.size)
        val inner = nested.items[0].children.single() as MdBlock.UnorderedList
        assertEquals("b", inner.items[0].text)
        assertEquals("c", (inner.items[0].children.single() as MdBlock.UnorderedList).items[0].text)
    }

    @Test
    fun `a list item with two paragraphs and a code block keeps them together`() {
        val item = (blocks("1. first\n\n   second paragraph\n\n   ```\n   code\n   ```\n2. next")[0] as MdBlock.OrderedList).items[0]
        assertEquals("first", item.text)
        assertEquals(listOf("Paragraph", "CodeBlock"), item.children.map { it::class.simpleName })
    }

    @Test
    fun `thematic breaks in all their spellings`() {
        assertEquals(listOf("HorizontalRule", "HorizontalRule", "HorizontalRule"), kinds("***\n\n- - -\n\n___"))
    }

    @Test
    fun `block quotes nest and continue lazily`() {
        val q = blocks("> a\nlazy\n> > deep")[0] as MdBlock.BlockQuote
        assertTrue(q.innerBlocks.any { it is MdBlock.BlockQuote })
    }

    @Test
    fun `reference links and footnotes are resolved, footnotes ending the message`() {
        val b = blocks("See [the docs][d] and [other].\n\n[d]: https://example.com/docs \"Docs\"\n[other]: <https://x.y/(z)>\n")
        assertEquals(1, b.size)
        val ann = parseInline((b[0] as MdBlock.Paragraph).raw, colors)
        assertEquals(listOf("https://example.com/docs", "https://x.y/(z)"), ann.getStringAnnotations("url", 0, ann.length).map { it.item })

        val fn = blocks("Claim.[^1] Another.[^note]\n\n[^note]: second source\n[^1]: first source\n")
        assertEquals(listOf("Paragraph", "HorizontalRule", "OrderedList"), fn.map { it::class.simpleName })
        val items = (fn[2] as MdBlock.OrderedList).items.map { it.text }
        assertEquals(listOf("first source", "second source"), items)
        assertTrue((fn[0] as MdBlock.Paragraph).raw.contains("<sup>1</sup>") && (fn[0] as MdBlock.Paragraph).raw.contains("<sup>2</sup>"))
    }

    @Test
    fun `an undefined footnote or reference stays as typed`() {
        assertEquals("a [^x] b [nope] c", text(blocks("a [^x] b [nope] c")[0]))
    }

    @Test
    fun `display math is lifted out whole, and code is left alone`() {
        val b = blocks("before\n\n\$\$\nx_1 * y_2\n\$\$\n\nafter")
        assertEquals(listOf("Paragraph", "MathDisplay", "Paragraph"), b.map { it::class.simpleName })
        assertEquals("x_1 * y_2", (b[1] as MdBlock.MathDisplay).latex)
        val code = blocks("```\n\$\$\nnot math\n\$\$\n```")
        assertEquals(listOf("CodeBlock"), code.map { it::class.simpleName })
        assertEquals(listOf("MathDisplay"), kinds("\\[ a^2 + b^2 \\]"))
    }

    @Test
    fun `html blocks show their content, not their markup`() {
        val b = blocks("<details>\n<summary>More</summary>\n\nHidden **text**\n\n</details>")
        assertTrue(b.any { it is MdBlock.Paragraph && "More" in it.raw })
        assertTrue(b.any { it is MdBlock.Paragraph && "Hidden **text**" in it.raw })
        assertEquals(emptyList<String>(), kinds("<!-- a note for later -->"))
    }

    @Test
    fun `links keep parentheses and titles, emphasis works inside them`() {
        val ann = parseInline("[**wiki** page](https://en.wikipedia.org/wiki/Foo_(bar) \"t\")", colors)
        assertEquals("wiki page", ann.text)
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), ann.getStringAnnotations("url", 0, ann.length).map { it.item })
    }

    @Test
    fun `code spans can use any number of backticks`() {
        assertEquals("a`b", parseInline("``a`b``", colors).text.trim { it == ' ' })
        assertEquals("x", parseInline("`` x ``", colors).text.trim { it == ' ' })
    }

    @Test
    fun `generic types and stray tags are text, not markup`() {
        assertEquals("Optional<String> and Map<K, V>", parseInline("Optional<String> and Map<K, V>", colors).text)
        assertEquals("a<br>b".replace("<br>", "\n"), parseInline("a<br>b", colors).text)
    }
}
