package com.openminis.app.ui.chat.md

/*
 * A CommonMark (0.31) + GFM Markdown parser, written for the chat renderer.
 *
 * Why a parser of our own: the renderer used to recognise Markdown with a pile of line-based rules, and every
 * model reply that did something the rules did not expect (a setext heading, a list item with two paragraphs,
 * a link with parentheses in its address, a code fence of four backticks) rendered wrongly. The specification
 * defines all of that exactly, so this implements the specification - block structure first (containers and
 * leaves, line by line), then inline content (code spans, emphasis by delimiter runs, links and references,
 * autolinks, HTML) - plus the GitHub extensions models actually use: tables, strikethrough, task lists,
 * footnotes and extended autolinks. The chat's own additions (inline math, media and file links) are hooks
 * the renderer supplies, not special cases in here.
 *
 * Structure follows the reference implementation's algorithm (commonmark.js, BSD-2-Clause; see
 * THIRD_PARTY_LICENSES.md), which is what the conformance tests are measured against.
 */

internal enum class BType {
    DOCUMENT, BLOCK_QUOTE, LIST, ITEM, PARAGRAPH, HEADING, THEMATIC_BREAK, CODE_BLOCK, HTML_BLOCK,
    TABLE, FOOTNOTE_DEFINITION,
}

internal class ListData(
    val ordered: Boolean,
    val bulletChar: Char?,
    val start: Int,
    val delimiter: Char?,
    var padding: Int,
    val markerOffset: Int,
    var tight: Boolean = true,
)

/** One block of the document. Doubles as the finished tree: [children] hold nested blocks, text lives in [content]/[literal]. */
internal class BNode(var type: BType) {
    var parent: BNode? = null
    val children = ArrayList<BNode>()
    var open = true
    var lastLineBlank = false
    var lastLineChecked = false

    /** Raw text collected while the block is open (paragraphs and headings: inline source; code: lines). */
    val content = StringBuilder()
    var literal = ""
    var info = ""
    var level = 0
    var isFenced = false
    var fenceChar = '`'
    var fenceLength = 0
    var fenceOffset = 0
    var list: ListData? = null
    var htmlBlockType = 0
    var startLine = 0

    // GFM table
    var tableAligns: List<Align> = emptyList()
    var tableHeader: List<String> = emptyList()
    var tableRows: List<List<String>> = emptyList()

    // GFM task list item: null = not a task, otherwise checked or not
    var task: Boolean? = null

    // footnote definition label (normalised) and its 1-based number once referenced
    var label = ""

    val lastChild: BNode? get() = children.lastOrNull()
    val firstChild: BNode? get() = children.firstOrNull()

    fun append(child: BNode) {
        child.parent = this
        children.add(child)
    }

    fun unlink() {
        parent?.children?.remove(this)
        parent = null
    }

    fun next(): BNode? {
        val p = parent ?: return null
        val i = p.children.indexOf(this)
        return if (i >= 0 && i + 1 < p.children.size) p.children[i + 1] else null
    }
}

internal enum class Align { NONE, LEFT, CENTER, RIGHT }

// ─── inline nodes ───────────────────────────────────────────────────────────

internal enum class IType {
    TEXT, SOFT_BREAK, HARD_BREAK, CODE, EMPH, STRONG, STRIKE, LINK, IMAGE, HTML_INLINE, FOOTNOTE_REF, MATH,
}

/** One inline element, in a doubly linked tree (emphasis and links move whole runs of siblings). */
internal class INode(val type: IType, var literal: String = "") {
    var parent: INode? = null
    var first: INode? = null
    var last: INode? = null
    var prev: INode? = null
    var next: INode? = null
    var destination = ""
    var title = ""
    var label = ""
    var number = 0

    fun appendChild(child: INode) {
        child.unlink()
        child.parent = this
        val l = last
        if (l != null) {
            l.next = child
            child.prev = l
            last = child
        } else {
            first = child
            last = child
        }
    }

    fun insertAfter(sibling: INode) {
        sibling.unlink()
        sibling.prev = this
        val n = next
        if (n != null) {
            sibling.next = n
            n.prev = sibling
        } else {
            parent?.last = sibling
        }
        sibling.parent = parent
        next = sibling
    }

    fun insertBefore(sibling: INode) {
        sibling.unlink()
        sibling.next = this
        val p = prev
        if (p != null) {
            sibling.prev = p
            p.next = sibling
        } else {
            parent?.first = sibling
        }
        sibling.parent = parent
        prev = sibling
    }

    fun unlink() {
        val p = prev
        val n = next
        if (p != null) p.next = n else parent?.first = n
        if (n != null) n.prev = p else parent?.last = p
        parent = null
        prev = null
        next = null
    }

    fun children(): List<INode> {
        val out = ArrayList<INode>()
        var c = first
        while (c != null) {
            out += c
            c = c.next
        }
        return out
    }
}

internal class LinkRef(val destination: String, val title: String)
