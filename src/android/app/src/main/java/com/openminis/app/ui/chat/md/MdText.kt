package com.openminis.app.ui.chat.md

/** Character-level helpers shared by the block and inline parsers. */
internal object MdText {
    const val ESCAPABLE = "!\"#$%&'()*+,./:;<=>?@[\\]^_`{|}~-"

    fun isEscapable(c: Char): Boolean = ESCAPABLE.indexOf(c) >= 0

    /** Unicode punctuation for flanking rules: general categories P* and S* (CommonMark 0.31). */
    fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }

    fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == 0x0B || cp == 0x0C || cp == '\r'.code ||
            Character.getType(cp).toByte() == Character.SPACE_SEPARATOR

    /** `\` + escapable char and entity references decoded; used for link destinations, titles and info strings. */
    fun unescapeString(s: String): String {
        if (s.indexOf('\\') < 0 && s.indexOf('&') < 0) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && isEscapable(s[i + 1])) {
                out.append(s[i + 1])
                i += 2
            } else if (c == '&') {
                val m = ENTITY_HERE.matchAt(s, i)
                if (m != null) {
                    out.append(MdEntities.decode(m.value))
                    i += m.value.length
                } else {
                    out.append(c)
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    val ENTITY_HERE = Regex("&(?:#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,31});")

    /** Collapse internal whitespace, trim and case-fold: the key under which a link label is stored and looked up. */
    fun normalizeReference(label: String): String =
        label.trim().replace(Regex("[ \\t\\r\\n]+"), " ").lowercase().uppercase()

    private const val URI_SAFE = ";/?:@&=+$,-_.!~*'()#"

    /** Percent-encode what a URL may not contain, keeping existing %XX escapes (the reference's normalizeURI). */
    fun normalizeUri(uri: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < uri.length) {
            val c = uri[i]
            if (c == '%' && i + 2 < uri.length && uri[i + 1].isHexDigit() && uri[i + 2].isHexDigit()) {
                out.append(c)
                i++
                continue
            }
            if (c.isLetterOrDigit() && c.code < 128 || URI_SAFE.indexOf(c) >= 0) {
                out.append(c)
                i++
                continue
            }
            val cp = uri.codePointAt(i)
            val len = Character.charCount(cp)
            for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                out.append('%').append("%02X".format(b.toInt() and 0xFF))
            }
            i += len
        }
        return out.toString()
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** The line without its line ending and the NUL replacement the spec asks for. */
    fun sanitizeLine(line: String): String = line.replace('\u0000', '�')

    fun isBlank(s: String): Boolean = s.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
}
