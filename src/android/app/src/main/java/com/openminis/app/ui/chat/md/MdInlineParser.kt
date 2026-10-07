package com.openminis.app.ui.chat.md

/** Hooks for what only the chat knows about; the parser itself stays plain CommonMark + GFM. */
internal interface InlineExtensions {
    /** Inline math starting at [i] (`$…$` or `\(…\)`): the LaTeX and the index just past it, or null. */
    fun mathAt(s: String, i: Int): Pair<String, Int>? = null

    companion object {
        val NONE = object : InlineExtensions {}
    }
}

internal class Delim(
    val cc: Char,
    var numdelims: Int,
    val origdelims: Int,
    val node: INode,
    var previous: Delim?,
    var next: Delim? = null,
    val canOpen: Boolean,
    val canClose: Boolean,
)

internal class SourceEdit(val start: Int, val end: Int, val replacement: String)

internal class Bracket(
    val node: INode,
    val previous: Bracket?,
    val previousDelimiter: Delim?,
    val index: Int,
    val image: Boolean,
    var active: Boolean = true,
    var bracketAfter: Boolean = false,
)

/**
 * Inline content of one block (CommonMark 0.31 inline algorithm). [refmap] holds the document's link reference
 * definitions, [footnotes] the labels of its footnote definitions.
 */
internal class InlineParser(
    private val refmap: Map<String, LinkRef>,
    private val footnotes: Set<String> = emptySet(),
    private val ext: InlineExtensions = InlineExtensions.NONE,
    /** Order footnote labels were first referenced in, shared across the whole document. */
    private val footnoteOrder: MutableList<String> = ArrayList(),
    /** GFM on: strikethrough, extended autolinks, footnote references. Off gives plain CommonMark. */
    private val gfm: Boolean = true,
) {
    private var edits: MutableList<SourceEdit>? = null
    private var subject = ""
    private var pos = 0
    private var delimiters: Delim? = null
    private var brackets: Bracket? = null

    // ─── entry points ───────────────────────────────────────────────────────

    /** Parses [source] (a block's raw inline text) into children of [parent]. */
    fun parse(source: String, parent: INode) {
        subject = source.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        pos = 0
        delimiters = null
        brackets = null
        while (parseInline(parent)) { /* consume */ }
        processEmphasis(null)
        mergeTextNodes(parent)
        if (gfm) autolinkTextNodes(parent)
    }

    /**
     * [source] with its reference links (`[text][ref]`, `[ref][]`, `[ref]`) written as inline links and its
     * footnote references as `<sup>N</sup>`. For a message whose inline text is rendered piece by piece, so the
     * references are settled once, up front, while the whole document's definitions are known.
     */
    fun resolveReferences(source: String): String {
        val list = ArrayList<SourceEdit>()
        edits = list
        try {
            parse(source, INode(IType.TEXT))
        } finally {
            edits = null
        }
        if (list.isEmpty()) return source
        val lead = source.length - source.trimStart(' ', '\t', '\n', '\r').length
        val sb = StringBuilder(source)
        var applied = Int.MAX_VALUE
        for (e in list.sortedByDescending { it.start }) {
            if (e.end > applied) continue
            sb.replace(e.start + lead, e.end + lead, e.replacement)
            applied = e.start
        }
        return sb.toString()
    }

    /** Consumes link reference definitions at the start of [s]; returns how many characters they took. */
    fun parseReference(s: String, refs: MutableMap<String, LinkRef>): Int {
        subject = s
        pos = 0
        val startpos = pos
        val matchChars = parseLinkLabel()
        if (matchChars == 0) return 0
        val rawlabel = subject.substring(0, matchChars)
        if (peek() == ':'.code) pos++ else {
            pos = startpos
            return 0
        }
        spnl()
        val dest = parseLinkDestination()
        if (dest == null) {
            pos = startpos
            return 0
        }
        val beforetitle = pos
        spnl()
        var title: String? = null
        if (pos != beforetitle) title = parseLinkTitle()
        if (title == null) {
            title = ""
            pos = beforetitle
        }
        var atLineEnd = true
        if (!matchSpaceAtEndOfLine()) {
            if (title == "") {
                atLineEnd = false
            } else {
                title = ""
                pos = beforetitle
                atLineEnd = matchSpaceAtEndOfLine()
            }
        }
        if (!atLineEnd) {
            pos = startpos
            return 0
        }
        val normlabel = MdText.normalizeReference(rawlabel.substring(1, rawlabel.length - 1))
        if (normlabel.isEmpty()) {
            pos = startpos
            return 0
        }
        if (!refs.containsKey(normlabel)) refs[normlabel] = LinkRef(dest, title)
        return pos - startpos
    }

    // ─── scanning helpers ───────────────────────────────────────────────────

    private fun peek(): Int = if (pos < subject.length) subject[pos].code else -1

    private fun spnl(): Boolean {
        while (pos < subject.length && (subject[pos] == ' ' || subject[pos] == '\t')) pos++
        if (pos < subject.length && subject[pos] == '\n') {
            pos++
            while (pos < subject.length && (subject[pos] == ' ' || subject[pos] == '\t')) pos++
        }
        return true
    }

    private fun matchSpaceAtEndOfLine(): Boolean {
        var p = pos
        while (p < subject.length && (subject[p] == ' ' || subject[p] == '\t')) p++
        if (p == subject.length) {
            pos = p
            return true
        }
        if (subject[p] == '\n') {
            pos = p + 1
            return true
        }
        return false
    }

    /** `[label]` as the spec defines it (no unescaped brackets, at most 999 characters). Returns its length or 0. */
    private fun parseLinkLabel(): Int {
        if (peek() != '['.code) return 0
        var p = pos + 1
        var n = 0
        while (p < subject.length) {
            val c = subject[p]
            when {
                c == '\\' && p + 1 < subject.length -> {
                    p += 2
                    n += 2
                }
                c == ']' -> {
                    val len = p + 1 - pos
                    if (n > 999) return 0
                    pos += len
                    return len
                }
                c == '[' -> return 0
                else -> {
                    p++
                    n++
                }
            }
            if (n > 999) return 0
        }
        return 0
    }

    private fun parseLinkDestination(): String? {
        if (peek() == '<'.code) {
            var p = pos + 1
            while (p < subject.length) {
                val c = subject[p]
                if (c == '\\' && p + 1 < subject.length && MdText.isEscapable(subject[p + 1])) {
                    p += 2
                } else if (c == '>') {
                    val raw = subject.substring(pos + 1, p)
                    pos = p + 1
                    return MdText.normalizeUri(MdText.unescapeString(raw))
                } else if (c == '<' || c == '\n' || c == '\u0000') {
                    return null
                } else {
                    p++
                }
            }
            return null
        }
        val savepos = pos
        var openparens = 0
        var c = peek()
        while (c != -1) {
            if (c == '\\'.code && pos + 1 < subject.length && MdText.isEscapable(subject[pos + 1])) {
                pos += 1
                if (peek() != -1) pos += 1
            } else if (c == '('.code) {
                pos += 1
                openparens += 1
            } else if (c == ')'.code) {
                if (openparens < 1) break
                pos += 1
                openparens -= 1
            } else if (isAsciiWhitespaceOrControl(c)) {
                break
            } else {
                pos += 1
            }
            c = peek()
        }
        if (pos == savepos && c != ')'.code) return null
        if (openparens != 0) return null
        val res = subject.substring(savepos, pos)
        return MdText.normalizeUri(MdText.unescapeString(res))
    }

    private fun isAsciiWhitespaceOrControl(c: Int) = c <= 0x20

    private fun parseLinkTitle(): String? {
        if (pos >= subject.length) return null
        val open = subject[pos]
        val close = when (open) {
            '"' -> '"'
            '\'' -> '\''
            '(' -> ')'
            else -> return null
        }
        var p = pos + 1
        while (p < subject.length) {
            val c = subject[p]
            if (c == '\\' && p + 1 < subject.length && MdText.isEscapable(subject[p + 1])) {
                p += 2
            } else if (c == close) {
                val raw = subject.substring(pos + 1, p)
                pos = p + 1
                return MdText.unescapeString(raw)
            } else if (open == '(' && c == '(') {
                return null
            } else {
                p++
            }
        }
        return null
    }

    // ─── inline dispatch ────────────────────────────────────────────────────

    private fun text(s: String) = INode(IType.TEXT, s)

    private fun parseInline(block: INode): Boolean {
        val c = peek()
        if (c == -1) return false
        val ok = when (c.toChar()) {
            '\n' -> parseNewline(block)
            '\\' -> parseMath(block) || parseBackslash(block)
            '`' -> parseBackticks(block)
            '*', '_' -> handleDelim(subject[pos], block)
            '~' -> if (gfm) handleTilde(block) else false
            '[' -> parseOpenBracket(block)
            '!' -> parseBang(block)
            ']' -> parseCloseBracket(block)
            '<' -> parseAutolink(block) || parseHtmlTag(block)
            '&' -> parseEntity(block)
            '$' -> parseMath(block)
            else -> false
        }
        if (ok) return true
        if (!ok && isSpecial(c.toChar()).not()) {
            val start = pos
            while (pos < subject.length && !isSpecial(subject[pos])) pos++
            block.appendChild(text(subject.substring(start, pos)))
            return true
        }
        // A special character nothing claimed: it is just text.
        pos += 1
        block.appendChild(text(c.toChar().toString()))
        return true
    }

    private fun isSpecial(c: Char) = when (c) {
        '\n', '\\', '`', '*', '_', '~', '[', ']', '!', '<', '&', '$' -> true
        else -> false
    }

    private fun parseNewline(block: INode): Boolean {
        pos++
        val lastc = block.last
        if (lastc != null && lastc.type == IType.TEXT && lastc.literal.endsWith(" ")) {
            val hard = lastc.literal.endsWith("  ")
            lastc.literal = lastc.literal.trimEnd(' ')
            block.appendChild(INode(if (hard) IType.HARD_BREAK else IType.SOFT_BREAK))
        } else {
            block.appendChild(INode(IType.SOFT_BREAK))
        }
        while (pos < subject.length && subject[pos] == ' ') pos++
        return true
    }

    private fun parseBackslash(block: INode): Boolean {
        pos++
        if (peek() == '\n'.code) {
            pos++
            block.appendChild(INode(IType.HARD_BREAK))
        } else if (pos < subject.length && MdText.isEscapable(subject[pos])) {
            block.appendChild(text(subject[pos].toString()))
            pos++
        } else {
            block.appendChild(text("\\"))
        }
        return true
    }

    private fun parseMath(block: INode): Boolean {
        val m = ext.mathAt(subject, pos) ?: return false
        val node = INode(IType.MATH, m.first)
        block.appendChild(node)
        pos = m.second
        return true
    }

    private fun parseBackticks(block: INode): Boolean {
        val start = pos
        var p = pos
        while (p < subject.length && subject[p] == '`') p++
        val ticks = p - start
        val afterOpen = p
        var i = afterOpen
        while (i < subject.length) {
            if (subject[i] == '`') {
                var j = i
                while (j < subject.length && subject[j] == '`') j++
                if (j - i == ticks) {
                    var contents = subject.substring(afterOpen, i).replace('\n', ' ')
                    if (contents.isNotEmpty() && contents.any { it != ' ' } && contents.first() == ' ' && contents.last() == ' ') {
                        contents = contents.substring(1, contents.length - 1)
                    }
                    block.appendChild(INode(IType.CODE, contents))
                    pos = j
                    return true
                }
                i = j
            } else {
                i++
            }
        }
        pos = afterOpen
        block.appendChild(text(subject.substring(start, afterOpen)))
        return true
    }

    // ─── delimiters (emphasis, strikethrough) ───────────────────────────────

    private class Scan(val numdelims: Int, val canOpen: Boolean, val canClose: Boolean)

    private fun scanDelims(cc: Char): Scan? {
        var numdelims = 0
        val startpos = pos
        while (pos < subject.length && subject[pos] == cc) {
            numdelims++
            pos++
        }
        if (numdelims == 0) {
            pos = startpos
            return null
        }
        val before = if (startpos == 0) '\n'.code else subject.codePointBefore(startpos)
        val after = if (pos >= subject.length) '\n'.code else subject.codePointAt(pos)
        val afterIsWhitespace = MdText.isWhitespace(after)
        val afterIsPunct = MdText.isPunctuation(after)
        val beforeIsWhitespace = MdText.isWhitespace(before)
        val beforeIsPunct = MdText.isPunctuation(before)
        val leftFlanking = !afterIsWhitespace && (!afterIsPunct || beforeIsWhitespace || beforeIsPunct)
        val rightFlanking = !beforeIsWhitespace && (!beforeIsPunct || afterIsWhitespace || afterIsPunct)
        val canOpen: Boolean
        val canClose: Boolean
        if (cc == '_') {
            canOpen = leftFlanking && (!rightFlanking || beforeIsPunct)
            canClose = rightFlanking && (!leftFlanking || afterIsPunct)
        } else {
            canOpen = leftFlanking
            canClose = rightFlanking
        }
        pos = startpos
        return Scan(numdelims, canOpen, canClose)
    }

    private fun handleDelim(cc: Char, block: INode): Boolean {
        val res = scanDelims(cc) ?: return false
        val startpos = pos
        pos += res.numdelims
        val node = text(subject.substring(startpos, pos))
        block.appendChild(node)
        if (res.canOpen || res.canClose) {
            val d = Delim(cc, res.numdelims, res.numdelims, node, delimiters, null, res.canOpen, res.canClose)
            delimiters?.next = d
            delimiters = d
        }
        return true
    }

    /** `~` and `~~` are strikethrough delimiters; longer runs are just text. */
    private fun handleTilde(block: INode): Boolean {
        val res = scanDelims('~') ?: return false
        if (res.numdelims > 2) {
            val startpos = pos
            pos += res.numdelims
            block.appendChild(text(subject.substring(startpos, pos)))
            return true
        }
        return handleDelim('~', block)
    }

    private fun removeDelimiter(d: Delim) {
        d.previous?.next = d.next
        if (d.next == null) delimiters = d.previous else d.next!!.previous = d.previous
    }

    private fun removeDelimitersBetween(bottom: Delim, top: Delim) {
        if (bottom.next !== top) {
            bottom.next = top
            top.previous = bottom
        }
    }

    private fun processEmphasis(stackBottom: Delim?) {
        val openersBottom = HashMap<String, Delim?>()
        var closer = delimiters
        while (closer != null && closer.previous !== stackBottom) closer = closer.previous
        while (closer != null) {
            if (!closer.canClose) {
                closer = closer.next
                continue
            }
            val key = "${closer.cc}${if (closer.canOpen) 1 else 0}${closer.origdelims % 3}"
            val bottom = if (openersBottom.containsKey(key)) openersBottom[key] else stackBottom
            var opener = closer.previous
            var found = false
            while (opener != null && opener !== stackBottom && opener !== bottom) {
                val cc = closer.cc
                val oddMatch = cc != '~' && (closer.canOpen || opener.canClose) &&
                    closer.origdelims % 3 != 0 && (opener.origdelims + closer.origdelims) % 3 == 0
                val sameLength = cc != '~' || opener.numdelims == closer.numdelims
                if (opener.cc == cc && opener.canOpen && !oddMatch && sameLength) {
                    found = true
                    break
                }
                opener = opener.previous
            }
            val oldCloser = closer
            if (!found) {
                closer = closer.next
            } else {
                val op = opener!!
                val useDelims = when {
                    closer.cc == '~' -> closer.numdelims
                    closer.numdelims >= 2 && op.numdelims >= 2 -> 2
                    else -> 1
                }
                val openerInl = op.node
                val closerInl = closer.node
                op.numdelims -= useDelims
                closer.numdelims -= useDelims
                openerInl.literal = openerInl.literal.dropLast(useDelims)
                closerInl.literal = closerInl.literal.dropLast(useDelims)
                val emph = INode(
                    when {
                        closer.cc == '~' -> IType.STRIKE
                        useDelims == 1 -> IType.EMPH
                        else -> IType.STRONG
                    },
                )
                var tmp = openerInl.next
                while (tmp != null && tmp !== closerInl) {
                    val nxt = tmp.next
                    tmp.unlink()
                    emph.appendChild(tmp)
                    tmp = nxt
                }
                openerInl.insertAfter(emph)
                removeDelimitersBetween(op, closer)
                if (op.numdelims == 0) {
                    openerInl.unlink()
                    removeDelimiter(op)
                }
                if (closer.numdelims == 0) {
                    closerInl.unlink()
                    val temp = closer.next
                    removeDelimiter(closer)
                    closer = temp
                }
            }
            if (!found) {
                openersBottom[key] = oldCloser.previous
                if (!oldCloser.canOpen) removeDelimiter(oldCloser)
            }
        }
        while (delimiters != null && delimiters !== stackBottom) removeDelimiter(delimiters!!)
    }

    // ─── links, images, footnote references ─────────────────────────────────

    private fun addBracket(node: INode, index: Int, image: Boolean) {
        brackets?.bracketAfter = true
        brackets = Bracket(node, brackets, delimiters, index, image)
    }

    private fun removeBracket() {
        brackets = brackets?.previous
    }

    private fun parseOpenBracket(block: INode): Boolean {
        val startpos = pos
        // [^label] when a footnote of that name is defined
        if (gfm && footnotes.isNotEmpty() && pos + 1 < subject.length && subject[pos + 1] == '^') {
            val close = subject.indexOf(']', pos + 2)
            if (close > pos + 2) {
                val label = subject.substring(pos + 2, close)
                if (label.none { it.isWhitespace() || it == '[' } && MdText.normalizeReference(label) in footnotes) {
                    val norm = MdText.normalizeReference(label)
                    if (norm !in footnoteOrder) footnoteOrder += norm
                    val ref = INode(IType.FOOTNOTE_REF, label)
                    ref.label = norm
                    ref.number = footnoteOrder.indexOf(norm) + 1
                    edits?.add(SourceEdit(startpos, close + 1, "<sup>${ref.number}</sup>"))
                    block.appendChild(ref)
                    pos = close + 1
                    return true
                }
            }
        }
        pos++
        val node = text("[")
        block.appendChild(node)
        addBracket(node, startpos, false)
        return true
    }

    private fun parseBang(block: INode): Boolean {
        val startpos = pos
        pos++
        if (peek() == '['.code) {
            pos++
            val node = text("![")
            block.appendChild(node)
            addBracket(node, startpos + 1, true)
        } else {
            block.appendChild(text("!"))
        }
        return true
    }

    private fun parseCloseBracket(block: INode): Boolean {
        pos++
        val startpos = pos
        val opener = brackets
        if (opener == null) {
            block.appendChild(text("]"))
            return true
        }
        if (!opener.active) {
            block.appendChild(text("]"))
            removeBracket()
            return true
        }
        val isImage = opener.image
        val savepos = pos
        var matched = false
        var dest: String? = null
        var title: String? = null
        var viaReference = false
        // inline link: [text](dest "title")
        if (peek() == '('.code) {
            pos++
            spnl()
            val d = parseLinkDestination()
            if (d != null) {
                spnl()
                var t: String? = null
                if (pos > 0 && pos - 1 < subject.length && (subject[pos - 1] == ' ' || subject[pos - 1] == '\t' || subject[pos - 1] == '\n')) {
                    t = parseLinkTitle()
                }
                spnl()
                if (peek() == ')'.code) {
                    pos += 1
                    matched = true
                    dest = d
                    title = t ?: ""
                } else {
                    pos = savepos
                }
            } else {
                pos = savepos
            }
        }
        if (!matched) {
            // reference link: [text][label], [text][] or [text]
            val beforelabel = pos
            val n = parseLinkLabel()
            var reflabel: String? = null
            if (n > 2) {
                reflabel = subject.substring(beforelabel + 1, beforelabel + n - 1)
            } else if (!opener.bracketAfter) {
                reflabel = subject.substring(opener.index + 1, startpos - 1)
            }
            if (n == 0) pos = savepos
            if (reflabel != null) {
                val link = refmap[MdText.normalizeReference(reflabel)]
                if (link != null) {
                    dest = link.destination
                    title = link.title
                    matched = true
                    viaReference = true
                }
            }
        }
        if (matched) {
            if (viaReference && edits != null) {
                val from = if (isImage) opener.index - 1 else opener.index
                val labelText = subject.substring(opener.index + 1, startpos - 1)
                val titlePart = if (title.isNullOrEmpty()) "" else " \"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                edits?.add(SourceEdit(from, pos, (if (isImage) "!" else "") + "[$labelText](<${dest ?: ""}>$titlePart)"))
            }
            val node = INode(if (isImage) IType.IMAGE else IType.LINK)
            node.destination = dest ?: ""
            node.title = title ?: ""
            var tmp = opener.node.next
            while (tmp != null) {
                val nxt = tmp.next
                tmp.unlink()
                node.appendChild(tmp)
                tmp = nxt
            }
            block.appendChild(node)
            processEmphasis(opener.previousDelimiter)
            removeBracket()
            opener.node.unlink()
            if (!isImage) {
                var o = brackets
                while (o != null) {
                    if (!o.image) o.active = false
                    o = o.previous
                }
            }
            return true
        }
        removeBracket()
        pos = startpos
        block.appendChild(text("]"))
        return true
    }

    // ─── autolinks, raw HTML, entities ──────────────────────────────────────

    private fun parseAutolink(block: INode): Boolean {
        val rest = subject.substring(pos)
        EMAIL_AUTOLINK.matchAt(rest, 0)?.let { m ->
            val dest = m.groupValues[1]
            val node = INode(IType.LINK)
            node.destination = MdText.normalizeUri("mailto:$dest")
            node.appendChild(text(dest))
            block.appendChild(node)
            pos += m.value.length
            return true
        }
        AUTOLINK.matchAt(rest, 0)?.let { m ->
            val dest = m.value.substring(1, m.value.length - 1)
            val node = INode(IType.LINK)
            node.destination = MdText.normalizeUri(dest)
            node.appendChild(text(dest))
            block.appendChild(node)
            pos += m.value.length
            return true
        }
        return false
    }

    private fun parseHtmlTag(block: INode): Boolean {
        val m = HTML_TAG.matchAt(subject, pos) ?: return false
        block.appendChild(INode(IType.HTML_INLINE, m.value))
        pos += m.value.length
        return true
    }

    private fun parseEntity(block: INode): Boolean {
        val m = MdText.ENTITY_HERE.matchAt(subject, pos) ?: return false
        val decoded = MdEntities.decode(m.value)
        // An unknown name stays text, as written.
        block.appendChild(text(decoded))
        pos += m.value.length
        return true
    }

    // ─── post-processing ────────────────────────────────────────────────────

    private fun mergeTextNodes(parent: INode) {
        var c = parent.first
        while (c != null) {
            val n = c.next
            if (c.type == IType.TEXT && n != null && n.type == IType.TEXT) {
                n.literal = c.literal + n.literal
                c.unlink()
            } else if (c.first != null) {
                mergeTextNodes(c)
            }
            c = n
        }
    }

    /** GFM extended autolinks: `http(s)://…`, `www.…` and e-mail addresses in plain text. */
    private fun autolinkTextNodes(parent: INode) {
        var c = parent.first
        while (c != null) {
            val n = c.next
            when {
                c.type == IType.TEXT -> splitAutolinks(c)
                c.type == IType.LINK || c.type == IType.IMAGE || c.type == IType.CODE || c.type == IType.MATH -> Unit
                c.first != null -> autolinkTextNodes(c)
            }
            c = n
        }
    }

    private fun splitAutolinks(textNode: INode) {
        val s = textNode.literal
        var cursor = 0
        var last: INode = textNode
        val pieces = ArrayList<INode>()
        while (cursor < s.length) {
            val email = EMAIL_LITERAL.find(s, cursor)?.takeIf { it.value.last() != '-' && it.value.last() != '_' }
            val limit = email?.range?.first ?: s.length
            var urlHit: Auto? = null
            var urlStart = -1
            var i = cursor
            while (i < limit) {
                val ch = s[i]
                if (ch == 'h' || ch == 'H' || ch == 'w' || ch == 'W' || ch == 'f' || ch == 'F') {
                    val h = urlAt(s, i)
                    if (h != null) {
                        urlHit = h
                        urlStart = i
                        break
                    }
                }
                i++
            }
            val link: Auto
            val start: Int
            if (urlHit != null) {
                link = urlHit
                start = urlStart
            } else if (email != null) {
                link = Auto(email.value, "mailto:${email.value}", email.range.last + 1)
                start = email.range.first
            } else {
                break
            }
            if (start > cursor) pieces += text(s.substring(cursor, start))
            val node = INode(IType.LINK)
            node.destination = link.destination
            node.appendChild(text(link.label))
            pieces += node
            cursor = link.end
        }
        if (pieces.isEmpty()) return
        if (cursor < s.length) pieces += text(s.substring(cursor))
        for (p in pieces) {
            last.insertAfter(p)
            last = p
        }
        textNode.unlink()
    }

    private class Auto(val label: String, val destination: String, val end: Int)

    private fun urlAt(s: String, i: Int): Auto? {
        // Must start a word: beginning of text, or after whitespace or one of `*_~(`.
        // (A Chinese/Japanese character right before the address counts as a boundary: "链接https://…".)
        if (i > 0) {
            val b = s[i - 1]
            val asciiWord = b.code < 128 && (b.isLetterOrDigit() || b == '/' || b == '.' || b == '-' || b == '@')
            if (asciiWord) return null
        }
        val rest = s.substring(i)
        val scheme = when {
            rest.startsWith("http://", true) -> "http://"
            rest.startsWith("https://", true) -> "https://"
            rest.startsWith("ftp://", true) -> "ftp://"
            rest.startsWith("www.", true) -> ""
            else -> return null
        }
        var end = 0
        // full-width punctuation ends an address in running Chinese/Japanese text
        while (end < rest.length && !rest[end].isWhitespace() && rest[end] != '<' && rest[end] !in "，。！？；：、）】」』》\u201C\u201D") end++
        // trailing punctuation is not part of the address
        var url = rest.substring(0, end)
        while (url.isNotEmpty()) {
            val l = url.last()
            val closeParen = l == ')' && url.count { it == ')' } > url.count { it == '(' }
            if (l in "?!.,:*_~'\"" || closeParen) {
                url = url.dropLast(1)
                continue
            }
            if (l == ';') {
                val amp = url.lastIndexOf('&')
                if (amp >= 0 && MdText.ENTITY_HERE.matches(url.substring(amp))) {
                    url = url.substring(0, amp)
                    continue
                }
            }
            break
        }
        val hostPart = url.removePrefix(scheme.ifEmpty { "" })
        if (scheme.isEmpty()) {
            // www. needs a dotted domain after it
            val domain = hostPart.substringBefore('/').substringBefore('?').substringBefore('#')
            if (!domain.startsWith("www.") || domain.length <= 4 || !domain.drop(4).contains(Regex("[A-Za-z0-9]"))) return null
        } else if (hostPart.isEmpty() || !hostPart.first().isLetterOrDigit()) {
            return null
        }
        val dest = if (scheme.isEmpty()) "http://$url" else url
        return Auto(url, MdText.normalizeUri(dest), i + url.length)
    }

    companion object {
        private const val TAGNAME = "[A-Za-z][A-Za-z0-9-]*"
        private const val ATTRIBUTENAME = "[a-zA-Z_:][a-zA-Z0-9:._-]*"
        private const val UNQUOTEDVALUE = "[^\"'=<>`\\x00-\\x20]+"
        private const val SINGLEQUOTEDVALUE = "'[^']*'"
        private const val DOUBLEQUOTEDVALUE = "\"[^\"]*\""
        private const val ATTRIBUTEVALUE = "(?:$UNQUOTEDVALUE|$SINGLEQUOTEDVALUE|$DOUBLEQUOTEDVALUE)"
        private const val ATTRIBUTEVALUESPEC = "(?:\\s*=\\s*$ATTRIBUTEVALUE)"
        private const val ATTRIBUTE = "(?:\\s+$ATTRIBUTENAME$ATTRIBUTEVALUESPEC?)"
        const val OPENTAG = "<$TAGNAME$ATTRIBUTE*\\s*/?>"
        const val CLOSETAG = "</$TAGNAME\\s*[>]"
        private const val HTMLCOMMENT = "<!-->|<!--->|<!--[\\s\\S]*?-->"
        private const val PROCESSINGINSTRUCTION = "[<][?][\\s\\S]*?[?][>]"
        private const val DECLARATION = "<![A-Za-z]+[^>]*>"
        private const val CDATA = "<!\\[CDATA\\[[\\s\\S]*?\\]\\]>"
        private val EMAIL_LITERAL = Regex("[A-Za-z0-9._+-]+@[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
        private val HTML_TAG = Regex("(?:$OPENTAG|$CLOSETAG|$HTMLCOMMENT|$PROCESSINGINSTRUCTION|$DECLARATION|$CDATA)")
        private val EMAIL_AUTOLINK = Regex(
            "<([a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)>",
        )
        private val AUTOLINK = Regex("<[A-Za-z][A-Za-z0-9.+-]{1,31}:[^<>\\x00-\\x20]*>", RegexOption.IGNORE_CASE)
    }
}
