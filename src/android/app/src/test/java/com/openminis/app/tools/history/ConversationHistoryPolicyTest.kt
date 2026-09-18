package com.openminis.app.tools.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-conversation-history] Ported from Eta's `conversation_history` tool
 * (Mangi-11/Eta @ c15de97). Paging must be followable without looping, bounded, and
 * resumable mid-message; search must stay literal and bounded.
 */
class ConversationHistoryPolicyTest {

    private fun entry(index: Int, text: String, role: String = if (index % 2 == 0) "user" else "assistant") =
        ConversationHistoryPolicy.Entry(index, role, text)

    @Test
    fun `max chars defaults and clamps to the advertised range`() {
        assertEquals(ConversationHistoryPolicy.DEFAULT_MAX_CHARS, ConversationHistoryPolicy.clampMaxChars(null))
        assertEquals(ConversationHistoryPolicy.MIN_MAX_CHARS, ConversationHistoryPolicy.clampMaxChars(1))
        assertEquals(ConversationHistoryPolicy.MAX_MAX_CHARS, ConversationHistoryPolicy.clampMaxChars(999_999))
        assertEquals(1_000, ConversationHistoryPolicy.clampMaxChars(1_000))
    }

    @Test
    fun `a short transcript is returned whole with no continuation`() {
        val window = ConversationHistoryPolicy.window(
            entries = listOf(entry(0, "hello"), entry(1, "hi there")),
            startIndex = 0,
            startOffset = 0,
            maxChars = 1_000,
        )

        assertEquals("#0 user: hello\n#1 assistant: hi there", window.text)
        assertNull(window.nextMessageIndex)
        assertNull(window.nextOffset)
        assertTrue(!window.truncated)
    }

    @Test
    fun `following the pointers walks the transcript once`() {
        val entries = listOf(entry(0, "a".repeat(40)), entry(1, "b".repeat(40)), entry(2, "c".repeat(40)))
        val first = ConversationHistoryPolicy.window(entries, 0, 0, maxChars = 60)

        assertTrue(first.truncated)
        assertEquals("the continuation starts at the next message", 1, first.nextMessageIndex)
        assertEquals(0, first.nextOffset)

        val second = ConversationHistoryPolicy.window(
            entries,
            first.nextMessageIndex!!,
            first.nextOffset!!,
            maxChars = 60,
        )
        assertTrue(second.text.startsWith("#1 "))

        val third = ConversationHistoryPolicy.window(
            entries,
            second.nextMessageIndex!!,
            second.nextOffset!!,
            maxChars = 60,
        )
        assertTrue(third.text.startsWith("#2 "))
        assertNull("the last page ends the walk", third.nextMessageIndex)
    }

    @Test
    fun `a page resumes in the middle of one long message`() {
        val entries = listOf(entry(0, "x".repeat(500)))
        val first = ConversationHistoryPolicy.window(entries, 0, 0, maxChars = 100)

        val index = first.nextMessageIndex!!
        val offset = first.nextOffset!!
        assertEquals(0, index)
        assertTrue(offset > 0)

        val second = ConversationHistoryPolicy.window(entries, index, offset, maxChars = 100)
        assertTrue(second.text.startsWith("#0 "))
        assertTrue("the second page continues the same message", second.text.contains("x".repeat(50)))
    }

    @Test
    fun `reaching the end reports no continuation`() {
        val window = ConversationHistoryPolicy.window(listOf(entry(0, "short")), 0, 0, maxChars = 256)

        assertNull(window.nextMessageIndex)
        assertTrue(!window.truncated)
    }

    @Test
    fun `starting past the loaded range yields an empty page`() {
        val window = ConversationHistoryPolicy.window(listOf(entry(0, "only")), 5, 0, maxChars = 256)

        assertEquals("", window.text)
        assertNull(window.nextMessageIndex)
    }

    @Test
    fun `entries are bounded so one huge message cannot flood a page`() {
        val long = "y".repeat(ConversationHistoryPolicy.MAX_ENTRY_CHARS + 500)

        assertEquals(
            ConversationHistoryPolicy.MAX_ENTRY_CHARS,
            ConversationHistoryPolicy.boundedEntry(long).length,
        )
    }

    @Test
    fun `search is case insensitive and literal`() {
        val entries = listOf(
            entry(0, "Deploy the staging build"),
            entry(1, "unrelated"),
            entry(2, "deploy again with the rollback flag"),
        )

        val matches = ConversationHistoryPolicy.search(entries, "DEPLOY")

        assertEquals(listOf(0, 2), matches.map { it.index })
        assertTrue(matches.all { it.snippet.isNotBlank() })
        assertTrue(ConversationHistoryPolicy.search(entries, "   ").isEmpty())
        assertTrue(ConversationHistoryPolicy.search(entries, "nothing here").isEmpty())
    }

    @Test
    fun `search caps the number of matches and the snippet length`() {
        val entries = (0 until 50).map { entry(it, "needle " + "z".repeat(600)) }

        val matches = ConversationHistoryPolicy.search(entries, "needle")

        assertEquals(ConversationHistoryPolicy.MAX_SEARCH_MATCHES, matches.size)
        assertTrue(matches.all { it.snippet.length <= ConversationHistoryPolicy.MAX_SEARCH_SNIPPET_CHARS })
    }
}
