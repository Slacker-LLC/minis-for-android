package io.github.slackerllc.minis.tools.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for the output-retention / tool-result-pruner / spill-policy
 * contracts ([TextRetainer], [ItemRetainer], [ToolResultPruner], [SpillPolicy]
 * and [formatRetentionNotice]).
 *
 * Pure JVM tests (JUnit 4): no Android dependencies are touched.
 */
class OutputRetainerTest {

    // ---------- TextRetainer ----------

    @Test
    fun textRetainerKeepsHeadAndTailWithExactOmittedCount() {
        val retainer = TextRetainer(maxChars = 100, headChars = 40, tailChars = 20)
        retainer.push("h".repeat(40))
        retainer.push("m".repeat(100))
        retainer.push("t".repeat(20))
        val retained = retainer.finish()

        assertEquals("exact", retained.omitted.kind)
        assertEquals(100L, retained.omitted.count)
        assertTrue(retained.text.startsWith("h".repeat(40)))
        assertTrue(retained.text.endsWith("t".repeat(20)))
        assertTrue(retained.text.contains("…[omitted 100 chars]…"))
    }

    @Test
    fun textRetainerNeverLeavesLoneSurrogates() {
        val emoji = "\uD83D\uDE00" // 😀
        // 100 filler chars followed by 200 emoji: the 25-char tail slice starts
        // exactly inside an emoji surrogate pair.
        val text = "x".repeat(100) + emoji.repeat(200)
        val retainer = TextRetainer(maxChars = 100, headChars = 50, tailChars = 25)
        retainer.push(text)
        assertNoLoneSurrogates(retainer.finish().text)
    }

    @Test
    fun textRetainerKeepsPairWholeAcrossHeadTailJunction() {
        val emoji = "\uD83D\uDE00" // 😀
        // 39 filler + emoji + 9 filler = 50 chars, exactly headChars=40 +
        // tailChars=10, with the cut landing between the emoji's two halves.
        val text = "y".repeat(39) + emoji + "z".repeat(9)
        val retainer = TextRetainer(maxChars = 100, headChars = 40, tailChars = 10)
        retainer.push(text)
        val retained = retainer.finish()

        assertEquals("none", retained.omitted.kind)
        assertEquals(text, retained.text)
        assertNoLoneSurrogates(retained.text)
    }

    @Test
    fun textRetainerPushReportsPerPushTruncation() {
        val retainer = TextRetainer(maxChars = 100, headChars = 40, tailChars = 20)

        val first = retainer.push("a".repeat(60)) // 40 head + 20 tail, nothing omitted
        assertTrue(first.kept)
        assertFalse(first.truncated)
        assertEquals(0L, first.omittedChars)

        val second = retainer.push("b".repeat(100)) // partially omitted
        assertTrue(second.kept)
        assertTrue(second.truncated)
        assertTrue(second.omittedChars > 0L)

        val third = retainer.push("c".repeat(100)) // only the newest 20 chars survive
        assertTrue(third.kept)
        assertTrue(third.truncated)
        assertEquals(80L, third.omittedChars)
    }

    // ---------- ItemRetainer ----------

    @Test
    fun itemRetainerKeepsFirstItemsAndCountsOmittedExactly() {
        val retainer = ItemRetainer<String>(3)
        assertTrue(retainer.push("a").kept)
        assertTrue(retainer.push("b").kept)
        assertTrue(retainer.push("c").kept)

        val dropped = retainer.push("d")
        assertFalse(dropped.kept)
        assertTrue(dropped.truncated)

        val retained = retainer.finish()
        assertEquals(listOf("a", "b", "c"), retained.items)
        assertEquals("exact", retained.omitted.kind)
        assertEquals(1L, retained.omitted.count ?: -1L)
    }

    @Test
    fun itemRetainerReportsNothingOmittedWhenWithinCapacity() {
        val retainer = ItemRetainer<Int>(5)
        (1..3).forEach { retainer.push(it) }
        val retained = retainer.finish()
        assertEquals(listOf(1, 2, 3), retained.items)
        assertEquals("none", retained.omitted.kind)
        assertEquals(null, retained.omitted.count)
    }

    @Test
    fun itemRetainerWithZeroCapacityDropsEverything() {
        val retainer = ItemRetainer<String>(0)
        retainer.push("a")
        retainer.push("b")
        val retained = retainer.finish()
        assertTrue(retained.items.isEmpty())
        assertEquals("exact", retained.omitted.kind)
        assertEquals(2L, retained.omitted.count ?: -1L)
    }

    // ---------- ToolResultPruner ----------

    @Test
    fun prunerLeavesUnderThresholdResultsUntouched() {
        assertNull(ToolResultPruner.prune("x".repeat(100)))
        assertNull(ToolResultPruner.prune("x".repeat(ToolResultPruner.THRESHOLD_CHARS)))
    }

    @Test
    fun prunerCutsOversizedResultsToHeadMarkerTail() {
        val text = "A".repeat(ToolResultPruner.THRESHOLD_CHARS + 5_000)
        val pruned = ToolResultPruner.prune(text)
        assertNotNull(pruned)
        assertTrue(pruned!!.startsWith("A".repeat(ToolResultPruner.HEAD_CHARS)))
        assertTrue(pruned.endsWith("A".repeat(ToolResultPruner.TAIL_CHARS)))
        assertTrue(pruned.contains("omitted"))
    }

    @Test
    fun prunerIsIdempotentAndResultStaysUnderThreshold() {
        val text = "B".repeat(ToolResultPruner.THRESHOLD_CHARS + 5_000)
        val once = ToolResultPruner.prune(text)!!
        assertTrue(once.length <= ToolResultPruner.THRESHOLD_CHARS)
        assertNull(ToolResultPruner.prune(once))
    }

    // ---------- SpillPolicy ----------

    @Test
    fun spillPolicyKeepsSmallTextInlineWithoutWriting() {
        val dir = spillTestDir("small")
        val result = SpillPolicy.spillIfOversized("hello world", spillDir = dir, baseName = "out")

        assertFalse(result.spilled)
        assertNull(result.fullPath)
        assertEquals("hello world", result.inline)
        assertFalse(dir.exists())
    }

    @Test
    fun spillPolicyWritesOversizedTextAndPointsInlineAtTheFile() {
        val dir = spillTestDir("big")
        try {
            val big = "x".repeat(60 * 1024) // 60 KiB > 50 KiB default cap
            val result = SpillPolicy.spillIfOversized(big, spillDir = dir, baseName = "big")

            assertTrue(result.spilled)
            assertNotNull(result.fullPath)
            val file = File(result.fullPath!!)
            assertTrue(file.isFile)
            assertEquals(big, file.readText(Charsets.UTF_8))
            assertTrue(file.name.startsWith("big-"))
            assertTrue(file.name.endsWith(".txt"))

            // The inline payload is a small head/tail preview, not the full text.
            assertTrue(result.inline.length < big.length)
            assertTrue(result.inline.contains("Omitted"))
            assertTrue(result.inline.contains("stored at:"))
            assertTrue(result.inline.contains(result.fullPath!!))
            assertTrue(result.inline.contains("Use file_read with offset/limit to search within it."))
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun spillPolicyHonorsExplicitMaxInlineBytes() {
        val dir = spillTestDir("explicit")
        try {
            val result = SpillPolicy.spillIfOversized("hello world", maxInlineBytes = 5, spillDir = dir, baseName = "tiny")
            assertTrue(result.spilled)
            assertNotNull(result.fullPath)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    // ---------- formatRetentionNotice ----------

    @Test
    fun retentionNoticeFormatsCharsItemsAndUnknown() {
        assertEquals(
            "…omitted 100 chars… Use file_read to recover.",
            formatRetentionNotice(Omitted("exact", 100L), "Use file_read to recover.")
        )
        assertEquals(
            "…omitted 3 items… See the spill file.",
            formatRetentionNotice(Omitted("exact", 3L), "See the spill file.", unit = "items")
        )
        assertEquals("…omitted chars…", formatRetentionNotice(Omitted("unknown", null), ""))
        assertEquals("", formatRetentionNotice(Omitted("none", null), "nothing to recover"))
    }

    // ---------- helpers ----------

    private fun assertNoLoneSurrogates(text: String) {
        for (i in text.indices) {
            val c = text[i]
            if (c in '\uD800'..'\uDBFF') {
                assertTrue(
                    "lone high surrogate at index $i",
                    i + 1 < text.length && text[i + 1] in '\uDC00'..'\uDFFF'
                )
            } else if (c in '\uDC00'..'\uDFFF') {
                assertTrue(
                    "lone low surrogate at index $i",
                    i > 0 && text[i - 1] in '\uD800'..'\uDBFF'
                )
            }
        }
    }

    private fun spillTestDir(label: String): File =
        File(System.getProperty("java.io.tmpdir"), "openminis-spill-$label-${System.nanoTime()}")
}
