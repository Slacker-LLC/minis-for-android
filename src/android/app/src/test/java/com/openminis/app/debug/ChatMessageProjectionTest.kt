package com.openminis.app.debug

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageProjectionTest {
    private fun block(type: String) = JSONObject().put("type", type).put("value", "v")

    @Test
    fun `the current mediaRef blocks count as attachments, as do the older kinds`() {
        for (t in listOf("mediaRef", "image", "file", "attachment")) assertTrue(t, ChatDebugMethods.isAttachmentPart(t))
        for (t in listOf("text", "toolUse", "reasoning", "")) assertFalse(t, ChatDebugMethods.isAttachmentPart(t))
    }

    @Test
    fun `includeTools false removes tool blocks from the raw parts and keeps the rest`() {
        val parts = JSONArray()
            .put(block("text")).put(block("toolUse")).put(block("mediaRef")).put(block("toolResult"))
            .put(block("tool_call")).put("a bare string")
        val kept = ChatDebugMethods.visibleParts(parts, includeTools = false)
        val types = (0 until kept.length()).map { (kept.opt(it) as? JSONObject)?.optString("type") ?: "bare" }
        assertEquals(listOf("text", "mediaRef", "bare"), types)
    }

    @Test
    fun `includeTools true returns every block untouched`() {
        val parts = JSONArray().put(block("text")).put(block("toolUse"))
        assertEquals(2, ChatDebugMethods.visibleParts(parts, includeTools = true).length())
    }
}
