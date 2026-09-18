package com.openminis.app.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-character-cards] Ported from Eta `data/repository/CharacterMemoryRepository.kt` and the two
 * memory tools (Mangi-11/Eta @ c15de97). Two properties matter: a read is bounded and honest about
 * what it cut off, and a write against a stale revision loses instead of overwriting somebody else's
 * scene.
 */
class CharacterMemoryDocumentTest {

    private val sample = "# 核心记忆\n\nAda met Bo at the market.\nBo is looking for a lost map.\n"

    @Test
    fun `a snapshot identifies the text by a sha256 revision`() {
        val snapshot = CharacterMemoryDocument.snapshot(sample)

        assertEquals(64, snapshot.revision.length)
        assertEquals(sample.toByteArray(Charsets.UTF_8).size, snapshot.byteSize)
        assertNotEquals(snapshot.revision, CharacterMemoryDocument.snapshot("other").revision)
        assertEquals(0, CharacterMemoryDocument.snapshot("").lineCount)
    }

    @Test
    fun `a query returns matching lines with their numbers`() {
        val read = CharacterMemoryDocument.read(sample, query = "market")

        assertEquals(1, read.matchedLines)
        assertTrue(read.content.contains("3: Ada met Bo at the market."))
        assertNull("a query read is not a range", read.startLine)
        assertFalse(read.hasMore)
        assertTrue("matching is case insensitive", CharacterMemoryDocument.read(sample, query = "MAP").matchedLines == 1)
    }

    @Test
    fun `a query read is bounded and says it cut off matches`() {
        val many = (1..200).joinToString("\n") { "line $it about the map" }

        // The budget clamps up to the advertised minimum, so the page can never come back empty
        // because a caller asked for too little.
        val read = CharacterMemoryDocument.read(
            many,
            query = "map",
            maxChars = CharacterMemoryDocument.MIN_MAX_READ_CHARS,
        )

        assertEquals(200, read.matchedLines)
        assertTrue(
            "the page is smaller than the matches",
            read.content.length <= CharacterMemoryDocument.MIN_MAX_READ_CHARS,
        )
        assertTrue(read.hasMore)
    }

    @Test
    fun `a paged read reports its range and when more follows`() {
        val many = (1..50).joinToString("\n") { "line $it" }

        val first = CharacterMemoryDocument.read(many, startLine = 1, maxChars = 60)
        assertEquals(1, first.startLine)
        assertTrue(first.endLine!! >= 1)
        assertTrue("there is more after the page", first.hasMore)

        val next = CharacterMemoryDocument.read(many, startLine = first.endLine!! + 1, maxChars = 60)
        assertTrue(next.startLine!! > first.startLine!!)
        assertFalse("the last page is honest about being the last", next.hasMore)
    }

    @Test
    fun `a start line beyond the text yields an empty page`() {
        val read = CharacterMemoryDocument.read(sample, startLine = 999)

        assertEquals("", read.content)
        assertNull(read.startLine)
        assertFalse(read.hasMore)
    }

    @Test
    fun `an append needs the current revision and lands at the end`() {
        val snapshot = CharacterMemoryDocument.snapshot(sample)

        val written = CharacterMemoryDocument.mutate(
            sample,
            CharacterMemoryMutation.Append(snapshot.revision, "They found the map."),
        ) as CharacterMemoryWrite.Success

        assertTrue(written.snapshot.content.trimEnd().endsWith("They found the map."))
        assertTrue("the header survives", written.snapshot.content.startsWith("# 核心记忆"))
    }

    @Test
    fun `a stale revision loses instead of overwriting`() {
        val stale = CharacterMemoryDocument.snapshot(sample).revision
        val moved = sample + "Someone else wrote here.\n"

        val result = CharacterMemoryDocument.mutate(
            moved,
            CharacterMemoryMutation.Append(stale, "late addition"),
        )

        assertTrue(result is CharacterMemoryWrite.Conflict)
        assertEquals(CharacterMemoryDocument.snapshot(moved).revision, (result as CharacterMemoryWrite.Conflict).snapshot.revision)
        assertFalse("nothing was written", moved.contains("late addition"))
    }

    @Test
    fun `replacing a line range corrects a stale fact in place`() {
        val snapshot = CharacterMemoryDocument.snapshot(sample)

        val written = CharacterMemoryDocument.mutate(
            sample,
            CharacterMemoryMutation.ReplaceRange(snapshot.revision, startLine = 4, endLine = 4, content = "Bo found the map."),
        ) as CharacterMemoryWrite.Success

        assertTrue(written.snapshot.content.contains("Bo found the map."))
        assertFalse(written.snapshot.content.contains("looking for a lost map"))
        assertEquals(4, written.snapshot.lineCount)
    }

    @Test
    fun `a range outside the text is refused`() {
        val snapshot = CharacterMemoryDocument.snapshot(sample)

        assertThrows(IllegalArgumentException::class.java) {
            CharacterMemoryDocument.mutate(sample, CharacterMemoryMutation.ReplaceRange(snapshot.revision, 2, 99, "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CharacterMemoryDocument.mutate(sample, CharacterMemoryMutation.ReplaceRange(snapshot.revision, 0, 1, "x"))
        }
    }

    @Test
    fun `clearing empties the memory and a blank append changes nothing`() {
        val snapshot = CharacterMemoryDocument.snapshot(sample)

        val cleared = CharacterMemoryDocument.mutate(sample, CharacterMemoryMutation.Clear(snapshot.revision))
            as CharacterMemoryWrite.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.lineCount)

        val blank = CharacterMemoryDocument.mutate(sample, CharacterMemoryMutation.Append(snapshot.revision, "   \n  "))
            as CharacterMemoryWrite.Success
        assertEquals(sample, blank.snapshot.content)
    }

    @Test
    fun `memory past the ceiling is refused with a reason`() {
        val big = "x".repeat(CharacterMemoryDocument.MAX_CHARS)
        val snapshot = CharacterMemoryDocument.snapshot(big)

        val failure = assertThrows(CharacterCardException::class.java) {
            CharacterMemoryDocument.mutate(big, CharacterMemoryMutation.Append(snapshot.revision, "more"))
        }

        assertEquals("MEMORY_TOO_LARGE", failure.code)
    }

    @Test
    fun `read budgets clamp to the advertised range`() {
        assertEquals(CharacterMemoryDocument.DEFAULT_MAX_READ_CHARS, CharacterMemoryDocument.clampMaxReadChars(null))
        assertEquals(CharacterMemoryDocument.MIN_MAX_READ_CHARS, CharacterMemoryDocument.clampMaxReadChars(1))
        assertEquals(CharacterMemoryDocument.MAX_CHARS, CharacterMemoryDocument.clampMaxReadChars(1_000_000))
    }
}
