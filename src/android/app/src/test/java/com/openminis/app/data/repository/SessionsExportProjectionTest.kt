package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsExportProjectionTest {
    private val longOutput = "line of output ".repeat(80) // 1,200 chars

    private fun parts(vararg entries: JSONObject) = JSONArray(entries.toList()).toString()
    private fun text(v: String) = JSONObject().put("type", "text").put("value", v)
    private fun toolUse(name: String, input: String) = JSONObject().put("type", "toolUse")
        .put("value", JSONObject().put("toolUseId", "t1").put("name", name).put("input", input))
    private fun toolResult(output: String, success: Boolean = true) = JSONObject().put("type", "toolResult")
        .put("value", JSONObject().put("toolUseId", "t1").put("output", output).put("success", success))

    @Test
    fun `a full export keeps every part, tool input and whole results, in order`() {
        val message = parts(
            text("I will check the file."),
            toolUse("linux.shell", """{"command":"sha256sum big.bin","tool_title":"Hash it"}"""),
        )
        val out = ChatRepository.projectForOffload(message, full = true)
        assertEquals(
            "I will check the file.\n[Tool call: linux.shell] {\"command\":\"sha256sum big.bin\",\"tool_title\":\"Hash it\"}",
            out.text,
        )
        assertFalse(out.shortened)

        val result = ChatRepository.projectForOffload(parts(toolResult(longOutput, success = false)), full = true)
        assertEquals("[Tool result (error): $longOutput]", result.text)
        assertFalse(result.shortened)
    }

    @Test
    fun `the short form flags a cut result instead of passing it off as complete`() {
        val out = ChatRepository.projectForOffload(parts(toolResult(longOutput)), full = false)
        assertTrue(out.shortened)
        assertTrue(out.text.length < longOutput.length)
        assertFalse(ChatRepository.projectForOffload(parts(toolResult("short")), full = false).shortened)
        assertEquals("plain text stays as it was", "hello", ChatRepository.projectForOffload(parts(text("hello")), full = false).text)
    }
}
