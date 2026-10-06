package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTitleGeneratorTest {
    private fun parse(s: String) = SessionTitleGenerator.parseTitleResponse(s)

    @Test
    fun `a json reply gives title, category and no folder when none is named`() {
        val r = parse("""{"title": "Debug Login Page Issue", "category": "code"}""")
        assertEquals("Debug Login Page Issue", r.title)
        assertEquals("code", r.category)
        assertNull(r.folder)
    }

    @Test
    fun `a code-fenced reply is unwrapped`() {
        val r = parse("```json\n{\"title\": \"Plan Kyoto Trip\", \"category\": \"travel\"}\n```")
        assertEquals("Plan Kyoto Trip", r.title)
        assertEquals("travel", r.category)
    }

    @Test
    fun `a named group is kept and the word null is not a group`() {
        assertEquals("Work", parse("""{"title": "T", "category": "chat", "folder": "Work"}""").folder)
        assertNull(parse("""{"title": "T", "category": "chat", "folder": null}""").folder)
        assertNull(parse("""{"title": "T", "category": "chat", "folder": "null"}""").folder)
    }

    @Test
    fun `broken json falls back to pulling the fields out with patterns`() {
        val r = parse("""Sure! {"title": "Fix Build", "category": "code", "folder": "Dev" trailing""")
        assertEquals("Fix Build", r.title)
        assertEquals("code", r.category)
        assertEquals("Dev", r.folder)
    }

    @Test
    fun `plain text becomes the first line, cut to fifty characters`() {
        val r = parse("A short answer\nsecond line")
        assertEquals("A short answer", r.title)
        assertNull(r.category)
        assertEquals(50, parse("x".repeat(80)).title.length)
    }

    @Test
    fun `an empty reply has no title`() {
        assertEquals("", parse("").title)
        assertEquals("", parse("   ").title)
    }
}
