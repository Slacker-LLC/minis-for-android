package com.openminis.app.ui.chat

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.chat.md.INode
import com.openminis.app.ui.chat.md.IType
import com.openminis.app.ui.chat.md.InlineExtensions
import com.openminis.app.ui.chat.md.InlineParser

/** What only the chat adds to inline parsing: `$…$` and `\(…\)` math, which the renderer draws with KaTeX. */
internal object ChatInlineExtensions : InlineExtensions {
    override fun mathAt(s: String, i: Int): Pair<String, Int>? {
        if (s[i] == '\\' && i + 1 < s.length && s[i + 1] == '(') {
            val end = s.indexOf("\\)", i + 2)
            if (end != -1) return s.substring(i + 2, end) to end + 2
            return null
        }
        if (s[i] == '$' && i + 1 < s.length && s[i + 1] != '$' && s[i + 1] != ' ') {
            val end = findInlineMathClose(s, i + 1)
            if (end != -1) {
                val latex = s.substring(i + 1, end)
                if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) return latex to end + 1
            }
        }
        return null
    }
}

internal fun newChatInlineParser() =
    InlineParser(emptyMap(), emptySet(), ChatInlineExtensions, ArrayList(), gfm = true)

/**
 * The styled text of one piece of inline Markdown. The parse is the specification's (emphasis by delimiter runs,
 * links and images, code spans, autolinks, raw HTML); this draws it: bold, italic, strikethrough, underlined
 * link-coloured links that carry their address as a `url` annotation, inline code with the `inline_code`
 * annotation the tap-to-copy handler reads, KaTeX placeholders for math, and the HTML a model sometimes writes
 * (`<br>`, `<b>`, `<sub>`, `<a href>` …) as what it means.
 */
internal fun parseInline(text: String, colors: MdColors): AnnotatedString {
    val root = INode(IType.TEXT)
    newChatInlineParser().parse(text, root)
    return buildAnnotatedString { InlineRenderer(this, colors).render(root) }
}

private class InlineRenderer(private val b: AnnotatedString.Builder, private val colors: MdColors) {
    /** Open HTML styles: tag name, the builder's push index, and for `<a>` where its text starts and its address. */
    private class Open(val name: String, val pushIndex: Int, val linkStart: Int = -1, val href: String = "")

    private val open = ArrayList<Open>()

    private val linkStyle get() = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)

    fun render(parent: INode) {
        val base = open.size
        var c = parent.first
        while (c != null) {
            node(c, base)
            c = c.next
        }
        // HTML opened inside this container closes with it: Compose styles are a stack, and Markdown nests.
        while (open.size > base) close(open.removeAt(open.size - 1))
    }

    private fun node(c: INode, base: Int) {
        when (c.type) {
            IType.TEXT -> b.append(c.literal)
            IType.SOFT_BREAK, IType.HARD_BREAK -> b.append('\n')
            IType.CODE -> code(c.literal)
            IType.EMPH -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { render(c) }
            IType.STRONG -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { render(c) }
            IType.STRIKE -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { render(c) }
            IType.LINK -> {
                val start = b.length
                b.withStyle(linkStyle) { render(c) }
                b.addStringAnnotation("url", c.destination, start, b.length)
            }
            IType.IMAGE -> {
                val alt = plain(c).ifEmpty { "image" }
                b.withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
            }
            IType.HTML_INLINE -> html(c.literal, base)
            IType.FOOTNOTE_REF -> b.withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 12.sp, color = colors.link)) { append(c.number.toString()) }
            IType.MATH -> b.appendInlineContent(katexInlineTagFor(c.literal), c.literal)
        }
    }

    private fun code(literal: String) {
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText)
        // U+2006 pads keep the background rect off the neighbouring line when the span wraps (T223); the
        // annotation covers the code itself, not the pads.
        b.withStyle(codeStyle) { append("\u2006") }
        val annStart = b.length
        b.withStyle(codeStyle) { append(literal) }
        val annEnd = b.length
        b.withStyle(codeStyle) { append("\u2006") }
        b.addStringAnnotation("inline_code", "", annStart, annEnd)
    }

    private fun plain(n: INode): String {
        val sb = StringBuilder()
        var c = n.first
        while (c != null) {
            when (c.type) {
                IType.TEXT, IType.CODE, IType.MATH -> sb.append(c.literal)
                IType.SOFT_BREAK, IType.HARD_BREAK -> sb.append(' ')
                else -> sb.append(plain(c))
            }
            c = c.next
        }
        return sb.toString()
    }

    // ─── raw HTML ───────────────────────────────────────────────────────────

    private fun html(literal: String, base: Int) {
        if (literal.startsWith("<!--") || literal.startsWith("<?") || literal.startsWith("<!")) return
        val m = TAG.matchEntire(literal) ?: return
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3]
        if (name == "br") {
            b.append('\n')
            return
        }
        if (name == "img" && !closing) {
            val alt = attr(attrs, "alt").ifEmpty { "image" }
            b.withStyle(SpanStyle(color = colors.link)) { append("[$alt]") }
            return
        }
        if (closing) {
            val idx = open.indexOfLast { it.name == name }
            if (idx < base) {
                b.append(literal) // closes nothing opened here: it is just text
                return
            }
            while (open.size > idx) {
                val o = open.removeAt(open.size - 1)
                close(o)
            }
            return
        }
        // Anything that is not a tag this renderer knows stays as typed: `Optional<String>` and "the <div> tag"
        // are text, not markup, and must not vanish.
        val style = styleFor(name, attrs) ?: run { b.append(literal); return }
        val pushIndex = b.pushStyle(style)
        open += if (name == "a") Open(name, pushIndex, b.length, attr(attrs, "href")) else Open(name, pushIndex)
    }

    private fun close(o: Open) {
        if (o.name == "a" && o.href.isNotEmpty()) b.addStringAnnotation("url", o.href, o.linkStart, b.length)
        b.pop(o.pushIndex)
    }

    private fun styleFor(name: String, attrs: String): SpanStyle? = when (name) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em", "cite", "var" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 12.sp)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 12.sp)
        "kbd", "code", "samp", "tt" -> SpanStyle(fontFamily = FontFamily.Monospace, color = colors.inlineCodeText, background = colors.inlineCodeBg)
        "mark" -> SpanStyle(background = colors.inlineCodeBg)
        "a" -> if (attr(attrs, "href").isNotEmpty()) linkStyle else null
        else -> null
    }

    private fun attr(attrs: String, name: String): String {
        val m = Regex("(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))", RegexOption.IGNORE_CASE).find(attrs)
            ?: return ""
        return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
    }

    private companion object {
        val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)([^>]*?)/?>")
    }
}
