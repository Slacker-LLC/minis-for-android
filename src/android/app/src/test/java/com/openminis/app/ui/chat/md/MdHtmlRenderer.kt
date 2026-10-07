package com.openminis.app.ui.chat.md

/** Test-side HTML rendering of the parsed tree, in the reference renderer's format, so the specification's expected output can be compared as written. */
internal object MdHtmlRenderer {
    fun render(markdown: String, gfm: Boolean): String {
        val doc = BlockParser(gfm).parse(markdown)
        val order = ArrayList<String>()
        val inline = InlineParser(doc.refmap, doc.footnoteLabels.toSet(), InlineExtensions.NONE, order, gfm)
        val out = StringBuilder()
        Renderer(out, inline).block(doc.root, tightParent = false)
        return out.toString()
    }

    private class Renderer(val out: StringBuilder, val inline: InlineParser) {
        fun cr() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun inlines(text: String) {
            val root = INode(IType.TEXT)
            inline.parse(text, root)
            node(root)
        }

        fun node(parent: INode) {
            var c = parent.first
            while (c != null) {
                when (c.type) {
                    IType.TEXT -> out.append(esc(c.literal))
                    IType.SOFT_BREAK -> out.append("\n")
                    IType.HARD_BREAK -> out.append("<br />\n")
                    IType.CODE -> out.append("<code>").append(esc(c.literal)).append("</code>")
                    IType.HTML_INLINE -> out.append(c.literal)
                    IType.EMPH -> { out.append("<em>"); node(c); out.append("</em>") }
                    IType.STRONG -> { out.append("<strong>"); node(c); out.append("</strong>") }
                    IType.STRIKE -> { out.append("<del>"); node(c); out.append("</del>") }
                    IType.LINK -> {
                        out.append("<a href=\"").append(esc(c.destination)).append("\"")
                        if (c.title.isNotEmpty()) out.append(" title=\"").append(esc(c.title)).append("\"")
                        out.append(">")
                        node(c)
                        out.append("</a>")
                    }
                    IType.IMAGE -> {
                        out.append("<img src=\"").append(esc(c.destination)).append("\" alt=\"")
                        out.append(esc(plain(c)))
                        out.append("\"")
                        if (c.title.isNotEmpty()) out.append(" title=\"").append(esc(c.title)).append("\"")
                        out.append(" />")
                    }
                    IType.FOOTNOTE_REF -> out.append("<sup>${c.number}</sup>")
                    IType.MATH -> out.append(esc(c.literal))
                }
                c = c.next
            }
        }

        fun plain(n: INode): String {
            val sb = StringBuilder()
            var c = n.first
            while (c != null) {
                when (c.type) {
                    IType.TEXT, IType.CODE -> sb.append(c.literal)
                    IType.SOFT_BREAK, IType.HARD_BREAK -> sb.append('\n')
                    else -> sb.append(plain(c))
                }
                c = c.next
            }
            return sb.toString()
        }

        fun block(b: BNode, tightParent: Boolean) {
            when (b.type) {
                BType.DOCUMENT -> b.children.forEach { block(it, false) }
                BType.PARAGRAPH -> {
                    if (tightParent) {
                        inlines(b.content.toString())
                    } else {
                        cr(); out.append("<p>"); inlines(b.content.toString()); out.append("</p>"); cr()
                    }
                }
                BType.HEADING -> {
                    cr(); out.append("<h${b.level}>"); inlines(b.content.toString()); out.append("</h${b.level}>"); cr()
                }
                BType.THEMATIC_BREAK -> { cr(); out.append("<hr />"); cr() }
                BType.CODE_BLOCK -> {
                    cr()
                    val lang = b.info.trim().split(Regex("\\s+"))[0]
                    out.append("<pre><code")
                    if (b.isFenced && lang.isNotEmpty()) out.append(" class=\"language-${esc(lang)}\"")
                    out.append(">").append(esc(b.literal)).append("</code></pre>")
                    cr()
                }
                BType.HTML_BLOCK -> { cr(); out.append(b.literal); cr() }
                BType.BLOCK_QUOTE -> {
                    cr(); out.append("<blockquote>"); cr()
                    b.children.forEach { block(it, false) }
                    cr(); out.append("</blockquote>"); cr()
                }
                BType.LIST -> {
                    val d = b.list!!
                    cr()
                    if (d.ordered) {
                        out.append("<ol")
                        if (d.start != 1) out.append(" start=\"${d.start}\"")
                        out.append(">")
                    } else out.append("<ul>")
                    cr()
                    b.children.forEach { item ->
                        out.append("<li>")
                        if (item.task != null) {
                            out.append(if (item.task == true) "<input checked=\"\" disabled=\"\" type=\"checkbox\"> " else "<input disabled=\"\" type=\"checkbox\"> ")
                        }
                        item.children.forEach { block(it, d.tight) }
                        out.append("</li>"); cr()
                    }
                    cr(); out.append(if (d.ordered) "</ol>" else "</ul>"); cr()
                }
                BType.ITEM -> Unit
                BType.TABLE -> {
                    cr(); out.append("<table>"); cr()
                    out.append("<thead>"); cr(); out.append("<tr>"); cr()
                    b.tableHeader.forEachIndexed { i, cell -> cellOut("th", cell, b.tableAligns.getOrNull(i) ?: Align.NONE) }
                    out.append("</tr>"); cr(); out.append("</thead>"); cr()
                    if (b.tableRows.isNotEmpty()) {
                        out.append("<tbody>"); cr()
                        for (row in b.tableRows) {
                            out.append("<tr>"); cr()
                            row.forEachIndexed { i, cell -> cellOut("td", cell, b.tableAligns.getOrNull(i) ?: Align.NONE) }
                            out.append("</tr>"); cr()
                        }
                        out.append("</tbody>"); cr()
                    }
                    out.append("</table>"); cr()
                }
                BType.FOOTNOTE_DEFINITION -> Unit
            }
        }

        fun cellOut(tag: String, cell: String, align: Align) {
            out.append("<$tag")
            when (align) {
                Align.LEFT -> out.append(" align=\"left\"")
                Align.CENTER -> out.append(" align=\"center\"")
                Align.RIGHT -> out.append(" align=\"right\"")
                Align.NONE -> Unit
            }
            out.append(">")
            inlines(cell)
            out.append("</$tag>")
            cr()
        }
    }
}
