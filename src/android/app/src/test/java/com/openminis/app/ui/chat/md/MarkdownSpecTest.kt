package com.openminis.app.ui.chat.md

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The CommonMark 0.31.2 specification's own examples (CC BY-SA 4.0, see THIRD_PARTY_LICENSES.md) and the GitHub
 * extension examples, run through the parser and compared with the expected HTML as written.
 */
class MarkdownSpecTest {
    private fun load(name: String): JSONArray =
        JSONArray(javaClass.getResourceAsStream("/md/$name")!!.bufferedReader().readText())

    private fun run(name: String, gfm: Boolean, dump: String): List<Int> {
        val arr = load(name)
        val failures = ArrayList<Int>()
        val report = StringBuilder()
        for (i in 0 until arr.length()) {
            val ex = arr.getJSONObject(i)
            val n = ex.optInt("example", ex.optInt("n", i + 1))
            val md = ex.getString("markdown")
            val expected = ex.getString("html")
            val actual = try { MdHtmlRenderer.render(md, gfm) } catch (t: Throwable) { "EXCEPTION " + t }
            if (actual != expected) {
                failures += n
                report.append("--- #$n [${ex.optString("section")}]\n")
                    .append("md:       ").append(md.replace("\n", "\\n").replace("\t", "\\t")).append('\n')
                    .append("expected: ").append(expected.replace("\n", "\\n")).append('\n')
                    .append("actual:   ").append(actual.replace("\n", "\\n")).append('\n')
            }
        }
        File("build/$dump").apply { parentFile.mkdirs() }.writeText("failed ${failures.size}/${arr.length()}\n" + report)
        return failures
    }

    @Test
    fun commonMarkSpecExamples() {
        val failures = run("commonmark-0.31.2.json", gfm = false, dump = "md-spec-commonmark.txt")
        println("COMMONMARK failed ${failures.size}: $failures")
        assertTrue("CommonMark conformance: ${failures.size} failing, see build/md-spec-commonmark.txt", failures.size <= MAX_COMMONMARK_FAILURES)
    }

    @Test
    fun gfmExtensionExamples() {
        val failures = run("gfm-extensions.json", gfm = true, dump = "md-spec-gfm.txt")
        println("GFM failed ${failures.size}: $failures")
        assertTrue("GFM conformance: ${failures.size} failing, see build/md-spec-gfm.txt", failures.size <= MAX_GFM_FAILURES)
    }

    companion object {
        const val MAX_COMMONMARK_FAILURES = 0
        /** The one GFM example not supported is `tagfilter`: the chat never emits raw HTML, so there is nothing to filter. */
        const val MAX_GFM_FAILURES = 1
    }
}
