package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileEditToolRequestTest {

    private fun args(edits: String) =
        JSONObject("""{"path":"/workspace/a.txt","edits":$edits}""")

    @Test
    fun everyEditIsParsed() {
        val parsed = FileEditTool.parseEdits(
            args("""[{"old_text":"a","new_text":"A"},{"oldText":"b","newText":""}]"""),
        )
        assertEquals(listOf("a" to "A", "b" to ""), parsed.map { it.oldText to it.newText })
    }

    @Test
    fun aNullOrNonObjectEditRejectsTheWholeRequest() {
        for (bad in listOf(
            """[{"old_text":"a","new_text":"A"},null]""",
            """[null,{"old_text":"a","new_text":"A"}]""",
            """[{"old_text":"a","new_text":"A"},"oops"]""",
        )) {
            try {
                FileEditTool.parseEdits(args(bad))
                fail("expected rejection of $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("must be an object"))
            }
        }
    }
}
