package com.openminis.app.ui.sandbox

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Word, Excel and PowerPoint files (the zipped XML ones: docx, xlsx, pptx) as Markdown, so they open in the same
 * reader as a Markdown file. Text, headings, lists and tables come across; pictures, charts and layout do not.
 * Null when the file is not one of those or cannot be read (the old binary doc, xls, ppt are not handled).
 */
internal object OfficeToMarkdown {
    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    private const val MAX_SHEETS = 12
    private const val MAX_ROWS = 300
    private const val MAX_COLS = 40
    private const val MAX_SLIDES = 200

    fun convert(file: File): String? = try {
        when (file.extension.lowercase()) {
            "docx", "docm", "dotx" -> ZipFile(file).use { docx(it) }
            "xlsx", "xlsm", "xltx" -> ZipFile(file).use { xlsx(it) }
            "pptx", "pptm", "potx" -> ZipFile(file).use { pptx(it) }
            else -> null
        }?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun ZipFile.open(name: String): InputStream? {
        val entry = getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        return getInputStream(entry)
    }

    private fun parse(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        // A document must not pull in external files or entities.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        input.use { factory.newSAXParser().parse(InputSource(it), handler) }
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace(Regex("\\s+"), " ").trim()

    private fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val width = rows.maxOf { it.size }.coerceAtLeast(1)
        fun line(r: List<String>) = "| " + (0 until width).joinToString(" | ") { cell(r.getOrElse(it) { "" }) } + " |"
        return buildString {
            appendLine(line(rows.first()))
            appendLine("|" + " --- |".repeat(width))
            rows.drop(1).forEach { appendLine(line(it)) }
        }
    }

    // ---- docx ----

    private fun docx(zip: ZipFile): String? {
        val input = zip.open("word/document.xml") ?: return null
        val out = StringBuilder()
        val handler = object : DefaultHandler() {
            var para = StringBuilder()
            var style = ""
            var listItem = false
            var inText = false
            var tableDepth = 0
            var rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            var cellText = StringBuilder()

            override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                when (q) {
                    "w:p" -> { para = StringBuilder(); style = ""; listItem = false }
                    "w:pStyle" -> style = a.getValue("w:val").orEmpty()
                    "w:numPr" -> listItem = true
                    "w:t" -> inText = true
                    "w:tab" -> para.append(' ')
                    "w:br", "w:cr" -> para.append(' ')
                    "w:tbl" -> { tableDepth++; if (tableDepth == 1) rows = ArrayList() }
                    "w:tr" -> if (tableDepth == 1) row = ArrayList()
                    "w:tc" -> if (tableDepth == 1) cellText = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) para.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, q: String) {
                when (q) {
                    "w:t" -> inText = false
                    "w:p" -> {
                        val text = para.toString().trim()
                        if (tableDepth > 0) {
                            if (tableDepth == 1 && text.isNotEmpty()) {
                                if (cellText.isNotEmpty()) cellText.append(' ')
                                cellText.append(text)
                            }
                        } else if (text.isNotEmpty()) {
                            val level = Regex("^(?:Heading|heading)\\s?(\\d)$").find(style)?.groupValues?.get(1)?.toIntOrNull()
                            when {
                                style.equals("Title", true) -> out.append("# ").append(text)
                                level != null -> out.append("#".repeat(level.coerceIn(1, 6))).append(' ').append(text)
                                listItem -> out.append("- ").append(text)
                                else -> out.append(text)
                            }
                            out.append("\n\n")
                        }
                    }
                    "w:tc" -> if (tableDepth == 1) row.add(cellText.toString())
                    "w:tr" -> if (tableDepth == 1) rows.add(row)
                    "w:tbl" -> {
                        if (tableDepth == 1) out.append(table(rows)).append('\n')
                        tableDepth--
                    }
                }
            }
        }
        parse(input, handler)
        return out.toString()
    }

    // ---- xlsx ----

    private fun xlsx(zip: ZipFile): String? {
        val shared = ArrayList<String>()
        zip.open("xl/sharedStrings.xml")?.let { input ->
            parse(input, object : DefaultHandler() {
                var cur = StringBuilder()
                var inT = false
                override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                    if (q == "si") cur = StringBuilder() else if (q == "t") inT = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) cur.append(ch, start, length) }
                override fun endElement(uri: String?, local: String?, q: String) {
                    if (q == "t") inT = false else if (q == "si") shared.add(cur.toString())
                }
            })
        }
        // Sheet names and the files they live in.
        val names = ArrayList<Pair<String, String>>() // name to relationship id
        zip.open("xl/workbook.xml")?.let { input ->
            parse(input, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                    if (q == "sheet") names.add((a.getValue("name") ?: "Sheet") to (a.getValue("r:id") ?: ""))
                }
            })
        }
        val targets = HashMap<String, String>()
        zip.open("xl/_rels/workbook.xml.rels")?.let { input ->
            parse(input, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                    if (q == "Relationship") targets[a.getValue("Id").orEmpty()] = a.getValue("Target").orEmpty()
                }
            })
        }
        val out = StringBuilder()
        names.take(MAX_SHEETS).forEachIndexed { index, (name, rid) ->
            val target = targets[rid] ?: "worksheets/sheet${index + 1}.xml"
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val input = zip.open(path) ?: return@forEachIndexed
            val rows = ArrayList<List<String>>()
            parse(input, object : DefaultHandler() {
                var row = ArrayList<String>()
                var col = 0
                var type = ""
                var value = StringBuilder()
                var inV = false
                override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                    when (q) {
                        "row" -> row = ArrayList()
                        "c" -> {
                            type = a.getValue("t").orEmpty()
                            value = StringBuilder()
                            col = columnIndex(a.getValue("r").orEmpty()).takeIf { it >= 0 } ?: row.size
                        }
                        "v", "t" -> inV = true
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inV) value.append(ch, start, length) }
                override fun endElement(uri: String?, local: String?, q: String) {
                    when (q) {
                        "v", "t" -> inV = false
                        "c" -> {
                            if (col < MAX_COLS) {
                                while (row.size < col) row.add("")
                                val raw = value.toString()
                                row.add(
                                    when (type) {
                                        "s" -> shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
                                        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                                        else -> raw
                                    },
                                )
                            }
                        }
                        "row" -> if (rows.size < MAX_ROWS && row.any { it.isNotBlank() }) rows.add(row)
                    }
                }
            })
            if (rows.isNotEmpty()) {
                out.append("## ").append(name).append("\n\n").append(table(rows)).append('\n')
                if (rows.size >= MAX_ROWS) out.append("_Only the first $MAX_ROWS rows are shown._\n\n")
            }
        }
        return out.toString()
    }

    /** `C7` -> 2; -1 when the reference has no column letters. */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        var seen = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); seen = true } else break
        }
        return if (seen) n - 1 else -1
    }

    // ---- pptx ----

    private fun pptx(zip: ZipFile): String? {
        val slides = zip.entries().asSequence()
            .map { it.name }
            .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt() }
            .take(MAX_SLIDES)
            .toList()
        val out = StringBuilder()
        slides.forEachIndexed { index, name ->
            val input = zip.open(name) ?: return@forEachIndexed
            val paragraphs = ArrayList<String>()
            parse(input, object : DefaultHandler() {
                var cur = StringBuilder()
                var inT = false
                override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                    if (q == "a:p") cur = StringBuilder() else if (q == "a:t") inT = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) cur.append(ch, start, length) }
                override fun endElement(uri: String?, local: String?, q: String) {
                    if (q == "a:t") inT = false
                    else if (q == "a:p" && cur.isNotBlank()) paragraphs.add(cur.toString().trim())
                }
            })
            if (paragraphs.isNotEmpty()) {
                out.append("## Slide ${index + 1}\n\n")
                paragraphs.forEachIndexed { i, p -> out.append(if (i == 0) "**$p**\n\n" else "- $p\n") }
                out.append('\n')
            }
        }
        return out.toString()
    }
}
