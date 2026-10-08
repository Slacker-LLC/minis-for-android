package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.md.Align
import com.openminis.app.ui.chat.md.BNode
import com.openminis.app.ui.chat.md.BType
import com.openminis.app.ui.chat.md.BlockParser
import com.openminis.app.ui.chat.md.InlineParser
import com.openminis.app.ui.chat.md.MdDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Turns the specification-conformant parse of a message ([BlockParser]) into the blocks the chat renderer draws.
 *
 * The parser knows CommonMark and GFM and nothing else. What only the chat knows is added around it:
 *  - display math (`$$ … $$`, `\[ … \]`) is lifted out before parsing, so LaTeX never meets Markdown rules, and
 *    comes back as [MdBlock.MathDisplay];
 *  - a line that is only `![alt](url)` or a link to a sandbox file becomes a media / file card;
 *  - wide inline math is promoted to a display block;
 *  - HTML blocks (`<details>`, `<div>`, `<br>` …) are shown as their content rather than as markup;
 *  - link reference definitions and footnotes are resolved here (the renderer parses each piece of inline text on
 *    its own, so the references are folded into the text before it gets there), footnotes ending the message.
 */
internal suspend fun parseMarkdownBlocks(content: String): List<MdBlock> {
    currentCoroutineContext().ensureActive()
    return MarkdownBlockAdapter().convertDocument(content, depth = 0)
}

internal class MarkdownBlockAdapter {
    private lateinit var doc: MdDocument
    private lateinit var math: ExtractedMath
    private val footnoteOrder = ArrayList<String>()
    private lateinit var inline: InlineParser
    private var needsRefs = false

    fun convertDocument(content: String, depth: Int): List<MdBlock> {
        math = extractDisplayMath(content)
        doc = BlockParser(gfm = true).parse(math.text)
        needsRefs = doc.refmap.isNotEmpty() || doc.footnoteLabels.isNotEmpty()
        inline = InlineParser(doc.refmap, doc.footnoteLabels.toSet(), ChatInlineExtensions, footnoteOrder, gfm = true)
        val out = ArrayList<MdBlock>()
        convertChildren(doc.root.children, out, depth)
        appendFootnotes(out, depth)
        return out
    }

    /** Inline text with its reference links and footnote references folded in; unchanged when the message has none. */
    private fun inl(text: String): String = if (needsRefs) inline.resolveReferences(text) else text

    private fun convertChildren(nodes: List<BNode>, out: MutableList<MdBlock>, depth: Int) {
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            if (node.type == BType.HTML_BLOCK && DETAILS_OPEN.containsMatchIn(node.literal) && depth < MAX_NESTING) {
                val end = detailsGroupEnd(nodes, i)
                details(nodes.subList(i, end + 1), out, depth)
                i = end + 1
            } else {
                convert(node, out, depth)
                i++
            }
        }
    }

    /**
     * A `<details>` element. Its opening tag, its content and its closing tag are separate blocks whenever the
     * content is Markdown (a blank line ends an HTML block), so the group is read from the sibling blocks
     * [group] holds: the first block carries the opening tag and the `<summary>`, the last one the closing tag.
     */
    private fun details(group: List<BNode>, out: MutableList<MdBlock>, depth: Int) {
        var head = group.first().literal
        val net = group.sumOf { n ->
            if (n.type != BType.HTML_BLOCK) 0 else DETAILS_OPEN.findAll(n.literal).count() - DETAILS_CLOSE.findAll(n.literal).count()
        }
        val closed = net <= 0
        val summary = SUMMARY.find(head)?.groupValues?.get(1)?.let { stripTags(it) }?.trim().orEmpty()
        head = head.replaceFirst(DETAILS_OPEN, "").replaceFirst(SUMMARY, "")
        var tail = ""
        if (group.size == 1) {
            head = head.dropLastClosingTag()
        } else if (closed) {
            tail = group.last().literal.dropLastClosingTag()
        }
        val inner = ArrayList<MdBlock>()
        if (head.isNotBlank()) inner += MarkdownBlockAdapter().convertDocument(htmlToMarkdown(head), depth + 1)
        val middle = if (group.size > 1) group.subList(1, group.size - if (closed) 1 else 0) else emptyList()
        convertChildren(middle, inner, depth + 1)
        if (tail.isNotBlank()) inner += MarkdownBlockAdapter().convertDocument(htmlToMarkdown(tail), depth + 1)
        val title = summary.ifEmpty { "Details" }
        out += MdBlock.Details(title + "\n" + inner.joinToString("\n") { it.raw }, title, inner)
    }

    private fun String.dropLastClosingTag(): String {
        val last = DETAILS_CLOSE.findAll(this).lastOrNull() ?: return this
        return removeRange(last.range)
    }

    private fun convert(node: BNode, out: MutableList<MdBlock>, depth: Int) {
        when (node.type) {
            BType.PARAGRAPH -> paragraph(node.content.toString().trimEnd('\n'), out)
            BType.HEADING -> {
                val text = inl(node.content.toString().trim())
                out += MdBlock.Heading("#".repeat(node.level) + " " + text, node.level, text)
            }
            BType.THEMATIC_BREAK -> out += MdBlock.HorizontalRule("---")
            BType.CODE_BLOCK -> {
                val code = node.literal.removeSuffix("\n")
                val lang = node.info.trim()
                val fence = "```"
                out += MdBlock.CodeBlock("$fence$lang\n$code\n$fence", lang, code)
            }
            BType.HTML_BLOCK -> htmlBlock(node.literal, out, depth)
            BType.BLOCK_QUOTE -> {
                val inner = ArrayList<MdBlock>()
                convertChildren(node.children, inner, depth)
                out += MdBlock.BlockQuote(inner.joinToString("\n") { "> " + it.raw }, inner)
            }
            BType.LIST -> list(node, out, depth)
            BType.TABLE -> {
                val headers = node.tableHeader.map(::inl)
                val rows = node.tableRows.map { row -> row.map(::inl) }
                val raw = buildString {
                    appendLine(headers.joinToString(" | ", "| ", " |"))
                    appendLine(headers.joinToString(" | ", "| ", " |") { "---" })
                    rows.forEach { appendLine(it.joinToString(" | ", "| ", " |")) }
                }.trimEnd()
                out += MdBlock.Table(raw, headers, rows, node.tableAligns)
            }
            BType.FOOTNOTE_DEFINITION, BType.ITEM, BType.DOCUMENT -> Unit
        }
    }

    // ─── paragraphs: media/file cards, display math, wide inline math ───────

    private fun paragraph(text: String, out: MutableList<MdBlock>) {
        if (text.isBlank()) return
        val pending = ArrayList<String>()
        fun flush() {
            if (pending.isEmpty()) return
            val joined = inl(pending.joinToString("\n"))
            pending.clear()
            if (joined.isBlank()) return
            for (b in splitParagraphOnMediaAndFiles(joined)) {
                if (b is MdBlock.Paragraph) out.addAll(splitParagraphOnWideMath(b.raw)) else out += b
            }
        }
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            val token = MATH_TOKEN.matchEntire(trimmed)
            when {
                token != null -> {
                    flush()
                    val m = math.blocks[token.groupValues[1].toInt()]
                    out += MdBlock.MathDisplay(m.raw, m.latex)
                }
                standaloneImageLineRegex.matches(trimmed) -> {
                    flush()
                    val match = imageMatchRegex.find(trimmed)
                    if (match != null) out += mediaBlockFrom(line, match.groupValues[1], match.groupValues[2])
                }
                standaloneFileLinkRegex.matches(trimmed) -> {
                    val match = standaloneFileLinkRegex.find(trimmed)!!
                    if (isSandboxOrLocalUrl(match.groupValues[2])) {
                        flush()
                        out += MdBlock.FileAttachment(line, match.groupValues[1], match.groupValues[2])
                    } else {
                        pending += line
                    }
                }
                else -> pending += line
            }
        }
        flush()
    }

    // ─── lists ──────────────────────────────────────────────────────────────

    private fun list(node: BNode, out: MutableList<MdBlock>, depth: Int) {
        val data = node.list!!
        val items = node.children.map { item -> listItem(item, depth) }
        val raw = items.joinToString("\n") { "- " + it.first.text }
        val allTasks = node.children.all { it.task != null } && items.all { it.first.children.isEmpty() }
        when {
            data.ordered -> out += MdBlock.OrderedList(raw, items.map { taskPrefixed(it.first, it.second) }, data.start)
            allTasks -> out += MdBlock.TaskList(raw, node.children.mapIndexed { i, item -> TaskItem(item.task == true, items[i].first.text) })
            else -> out += MdBlock.UnorderedList(raw, items.map { taskPrefixed(it.first, it.second) })
        }
    }

    /** A task item among ordinary items keeps its box as a glyph, since the list is not a pure task list. */
    private fun taskPrefixed(item: ListItem, task: Boolean?): ListItem =
        if (task == null) item else item.copy(text = (if (task) "☑ " else "☐ ") + item.text)

    private fun listItem(item: BNode, depth: Int): Pair<ListItem, Boolean?> {
        val first = item.children.firstOrNull()
        val text: String
        val rest: List<BNode>
        if (first != null && first.type == BType.PARAGRAPH) {
            text = inl(first.content.toString().trimEnd('\n'))
            rest = item.children.drop(1)
        } else {
            text = ""
            rest = item.children
        }
        val children = ArrayList<MdBlock>()
        convertChildren(rest, children, depth)
        return ListItem(text, children) to item.task
    }

    // ─── HTML blocks ────────────────────────────────────────────────────────

    /**
     * Raw HTML in a chat message is shown as what it says, not as markup: comments vanish, `<summary>` becomes a
     * bold line, headings/lists/code keep their meaning, `<br>` is a line break, any other tag is dropped and
     * its text is read as Markdown.
     */
    private fun htmlBlock(literal: String, out: MutableList<MdBlock>, depth: Int) {
        val markdown = htmlToMarkdown(literal)
        if (markdown.isBlank()) return
        if (depth >= MAX_NESTING) {
            out += MdBlock.Paragraph(markdown)
            return
        }
        out += MarkdownBlockAdapter().convertDocument(markdown, depth + 1)
    }

    // ─── footnotes ──────────────────────────────────────────────────────────

    private fun appendFootnotes(out: MutableList<MdBlock>, depth: Int) {
        if (footnoteOrder.isEmpty()) return
        val definitions = HashMap<String, BNode>()
        fun collect(n: BNode) {
            if (n.type == BType.FOOTNOTE_DEFINITION) definitions.putIfAbsent(n.label, n)
            n.children.forEach(::collect)
        }
        collect(doc.root)
        val items = ArrayList<ListItem>()
        for (label in footnoteOrder) {
            val def = definitions[label] ?: continue
            items += listItem(def, depth).first
        }
        if (items.isEmpty()) return
        out += MdBlock.HorizontalRule("---")
        out += MdBlock.OrderedList(items.joinToString("\n") { "1. " + it.text }, items, 1)
    }

    companion object {
        private const val MAX_NESTING = 4
        private val MATH_TOKEN = Regex("MATH(\\d+)")
    }
}

// ─── display math ───────────────────────────────────────────────────────────

internal class MathBlock(val raw: String, val latex: String)

internal class ExtractedMath(val text: String, val blocks: List<MathBlock>)

/**
 * Lifts display math out of [content] before it is parsed as Markdown, leaving a one-line placeholder in its place
 * (keeping the line's indentation, so a block inside a list item stays inside it). Fenced code is left alone, and an
 * opener with no plausible closer stays ordinary text (see [findDisplayMathClose]).
 */
internal fun extractDisplayMath(content: String): ExtractedMath {
    if (!content.contains("$$") && !content.contains("\\[")) return ExtractedMath(content, emptyList())
    val lines = content.lines()
    val blocks = ArrayList<MathBlock>()
    val out = ArrayList<String>(lines.size)
    var fenceChar = ' '
    var fenceLen = 0
    var i = 0
    fun placeholder(indent: String, raw: String, latex: String) {
        out += indent + "MATH${blocks.size}"
        blocks += MathBlock(raw, latex)
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val indent = line.substring(0, line.length - trimmed.length)
        // fenced code regions pass through untouched
        val fence = FENCE_OPEN.find(trimmed)
        if (fenceLen == 0 && fence != null && indent.length < 4 + 8) {
            fenceChar = fence.value[0]
            fenceLen = fence.value.length
            out += line
            i++
            continue
        }
        if (fenceLen > 0) {
            val close = FENCE_CLOSE.find(trimmed)
            if (close != null && close.value[0] == fenceChar && close.value.length >= fenceLen) fenceLen = 0
            out += line
            i++
            continue
        }
        if (trimmed.startsWith("$$")) {
            val rest = trimmed.removePrefix("$$")
            val inlineEnd = rest.indexOf("$$")
            if (inlineEnd >= 0) {
                placeholder(indent, line, rest.substring(0, inlineEnd).trim())
                i++
                continue
            }
            val closeIdx = findDisplayMathClose(lines, i + 1)
            if (closeIdx != null) {
                val raw = lines.subList(i, closeIdx + 1).joinToString("\n")
                val body = ArrayList<String>()
                if (rest.isNotEmpty()) body += rest
                for (j in i + 1..closeIdx) {
                    if (j == closeIdx) {
                        val pre = lines[j].substring(0, lines[j].indexOf("$$"))
                        if (pre.isNotEmpty()) body += pre
                    } else {
                        body += lines[j]
                    }
                }
                placeholder(indent, raw, body.joinToString("\n").trim())
                i = closeIdx + 1
                continue
            }
        } else if (trimmed.startsWith("\\[")) {
            val rest = trimmed.removePrefix("\\[")
            val inlineEnd = rest.indexOf("\\]")
            if (inlineEnd >= 0) {
                placeholder(indent, line, rest.substring(0, inlineEnd).trim())
                i++
                continue
            }
            var j = i + 1
            var closeIdx = -1
            while (j < lines.size) {
                if (lines[j].contains("\\]")) {
                    closeIdx = j
                    break
                }
                j++
            }
            if (closeIdx >= 0) {
                val body = ArrayList<String>()
                if (rest.isNotEmpty()) body += rest
                for (k in i + 1..closeIdx) {
                    if (k == closeIdx) {
                        val pre = lines[k].substring(0, lines[k].indexOf("\\]"))
                        if (pre.isNotEmpty()) body += pre
                    } else {
                        body += lines[k]
                    }
                }
                placeholder(indent, lines.subList(i, closeIdx + 1).joinToString("\n"), body.joinToString("\n").trim())
                i = closeIdx + 1
                continue
            }
        }
        out += line
        i++
    }
    return ExtractedMath(out.joinToString("\n"), blocks)
}

private val DETAILS_OPEN = Regex("<details\\b[^>]*>", RegexOption.IGNORE_CASE)
private val DETAILS_CLOSE = Regex("</details\\s*>", RegexOption.IGNORE_CASE)
private val SUMMARY = Regex("<summary[^>]*>([\\s\\S]*?)</summary>", RegexOption.IGNORE_CASE)
private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "")

/**
 * The index of the block that closes the `<details>` element opened by `nodes[from]`, counting nested elements; the
 * last block when it is never closed (a message still streaming, or a model that forgot the closing tag).
 */
internal fun detailsGroupEnd(nodes: List<BNode>, from: Int): Int {
    var open = 0
    for (k in from until nodes.size) {
        if (nodes[k].type != BType.HTML_BLOCK) continue
        open += DETAILS_OPEN.findAll(nodes[k].literal).count() - DETAILS_CLOSE.findAll(nodes[k].literal).count()
        if (open <= 0) return k
    }
    return nodes.lastIndex
}

/** Whether this block opens a `<details>` element (the check [detailsGroupEnd] starts from). */
internal fun opensDetails(node: BNode) = node.type == BType.HTML_BLOCK && DETAILS_OPEN.containsMatchIn(node.literal)

private val FENCE_OPEN = Regex("^(?:`{3,}(?!.*`)|~{3,})")
private val FENCE_CLOSE = Regex("^(?:`{3,}|~{3,})(?=[ \\t]*$)")

// ─── HTML blocks as text ────────────────────────────────────────────────────

/** The Markdown a block of HTML stands for (see [MarkdownBlockAdapter.htmlBlock]). */
internal fun htmlToMarkdown(html: String): String {
    var s = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
    s = s.replace(Regex("<summary[^>]*>([\\s\\S]*?)</summary>", RegexOption.IGNORE_CASE)) { "\n\n**" + it.groupValues[1].trim() + "**\n\n" }
    s = s.replace(Regex("<pre[^>]*>\\s*<code[^>]*>([\\s\\S]*?)</code>\\s*</pre>", RegexOption.IGNORE_CASE)) { "\n\n```\n" + decodeBasicEntities(it.groupValues[1]) + "\n```\n\n" }
    s = s.replace(Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE)) { "\n\n```\n" + decodeBasicEntities(it.groupValues[1]) + "\n```\n\n" }
    for (level in 1..6) {
        s = s.replace(Regex("<h$level[^>]*>([\\s\\S]*?)</h$level>", RegexOption.IGNORE_CASE)) { "\n\n" + "#".repeat(level) + " " + it.groupValues[1].trim() + "\n\n" }
    }
    s = s.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
    s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    s = s.replace(Regex("</?(?:p|div|section|article|header|footer|main|nav|aside|figure|figcaption|center|blockquote|ul|ol|table|thead|tbody|tfoot|tr|details|hr)[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
    s = s.replace(Regex("</t[dh]>", RegexOption.IGNORE_CASE), " | ")
    s = s.replace(Regex("</?(?:td|th|span|font|small|big|label)[^>]*>", RegexOption.IGNORE_CASE), "")
    return s.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun decodeBasicEntities(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

@Suppress("unused")
private fun Align.isSet() = this != Align.NONE
