package com.openminis.app.ui.sandbox

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeToMarkdownTest {
    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File.createTempFile("office", ".$name")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (path, body) ->
                z.putNextEntry(ZipEntry(path))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    @Test
    fun aDocxKeepsHeadingsParagraphsListsAndTables() {
        val body = """<w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Report</w:t></w:r></w:p>
            <w:p><w:r><w:t>First </w:t></w:r><w:r><w:t>paragraph</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>an item</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>2|3</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val md = OfficeToMarkdown.convert(zip("docx", mapOf("word/document.xml" to body)))!!
        assertTrue(md, md.contains("# Report"))
        assertTrue(md, md.contains("First paragraph"))
        assertTrue(md, md.contains("- an item"))
        assertTrue(md, md.contains("| a | b |"))
        assertTrue(md, md.contains("| 1 | 2\\|3 |"))
    }

    @Test
    fun anXlsxBecomesATablePerSheet() {
        val md = OfficeToMarkdown.convert(
            zip(
                "xlsx",
                mapOf(
                    "xl/workbook.xml" to """<workbook><sheets><sheet name="Prices" r:id="rId1"/></sheets></workbook>""",
                    "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
                    "xl/sharedStrings.xml" to """<sst><si><t>brand</t></si><si><t>price</t></si></sst>""",
                    "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
                        <row r="2"><c r="A2" t="inlineStr"><is><t>X</t></is></c><c r="C2"><v>299</v></c></row>
                        </sheetData></worksheet>""",
                ),
            ),
        )!!
        assertTrue(md, md.contains("## Prices"))
        assertTrue(md, md.contains("| brand |  | price |"))
        assertTrue(md, md.contains("| X |  | 299 |"))
    }

    @Test
    fun aPptxListsTheTextOfEachSlideInOrder() {
        val slide = { text: String -> """<p:sld><a:p><a:r><a:t>$text</a:t></a:r></a:p><a:p><a:r><a:t>point</a:t></a:r></a:p></p:sld>""" }
        val md = OfficeToMarkdown.convert(
            zip("pptx", mapOf("ppt/slides/slide10.xml" to slide("Ten"), "ppt/slides/slide2.xml" to slide("Two"))),
        )!!
        assertTrue(md, md.indexOf("Two") < md.indexOf("Ten"))
        assertTrue(md, md.contains("- point"))
    }

    @Test
    fun otherFilesAndBrokenOnesGiveNull() {
        assertNull(OfficeToMarkdown.convert(zip("doc", emptyMap())))
        val broken = File.createTempFile("broken", ".docx").apply { writeText("not a zip"); deleteOnExit() }
        assertNull(OfficeToMarkdown.convert(broken))
    }

    @Test
    fun columnLettersBecomeIndexes() {
        assertEquals(0, OfficeToMarkdown.columnIndex("A1"))
        assertEquals(2, OfficeToMarkdown.columnIndex("C7"))
        assertEquals(26, OfficeToMarkdown.columnIndex("AA3"))
        assertEquals(-1, OfficeToMarkdown.columnIndex("12"))
    }
}
