package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-reveal-monotonic] The reveal clock's invariants: grapheme
 * boundaries survive incremental appends, progress only clamps when the text
 * shrinks, and the adaptive speed never goes below its floor or above the
 * frame budget.
 */
class RevealProgressTest {

    /** Append in [steps]-character slices and compare against a full rebuild. */
    private fun assertIncrementalMatchesFull(text: String, steps: Int = 1) {
        var previous = ""
        var boundaries = graphemeBoundaries("")
        var index = 0
        while (index < text.length) {
            val next = text.substring(0, (index + steps).coerceAtMost(text.length))
            boundaries = updateGraphemeBoundaries(previous, boundaries, next)
            assertEquals(
                "boundaries diverged at length ${next.length}",
                graphemeBoundaries(next).toList(),
                boundaries.toList(),
            )
            previous = next
            index += steps
        }
        assertEquals(graphemeBoundaries(text).toList(), boundaries.toList())
    }

    @Test
    fun `incremental boundaries match a full rebuild for plain appends`() {
        assertIncrementalMatchesFull("the quick brown fox jumps over the lazy dog")
    }

    @Test
    fun `a last grapheme that keeps growing stays correct`() {
        // Combining acute accent: "e" then "e" + U+0301 is ONE cluster.
        assertIncrementalMatchesFull("cafe\u0301 au lait")
        // ZWJ family emoji built one code point at a time.
        assertIncrementalMatchesFull("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 ok")
        // Regional-indicator flag arriving as two separate code points.
        assertIncrementalMatchesFull("\uD83C\uDDE8\uD83C\uDDF3 flag")
        // CRLF: "\r" then "\r\n" — the line break is one cluster.
        assertIncrementalMatchesFull("line\r\nnext")
    }

    @Test
    fun `inconsistent previous state falls back to a full rebuild`() {
        val rebuilt = updateGraphemeBoundaries(
            previousText = "abcdef",
            // Last boundary does not reach the previous text end: the index is
            // not usable, so the whole text is rescanned.
            previousBoundaries = intArrayOf(0, 3),
            text = "abcdefg",
        )
        assertEquals(graphemeBoundaries("abcdefg").toList(), rebuilt.toList())
        // Divergence is not an append either.
        val replaced = updateGraphemeBoundaries("abc", graphemeBoundaries("abc"), "xyz")
        assertEquals(graphemeBoundaries("xyz").toList(), replaced.toList())
        // A coarse-but-valid index (fewer boundaries than clusters) is still
        // refused: the incremental path may only extend a COMPLETE index, and a
        // first boundary that is not 0 is never usable.
        val offsetIndex = updateGraphemeBoundaries(
            previousText = "abcdef",
            previousBoundaries = intArrayOf(1, 3, 6),
            text = "abcdefg",
        )
        assertEquals(graphemeBoundaries("abcdefg").toList(), offsetIndex.toList())
    }

    @Test
    fun `shorter text clamps progress and never rewinds past the target`() {
        val progress = RevealProgress()
        assertEquals(RevealIngestKind.APPENDED, progress.ingest("hello world").kind)
        progress.snapToTarget()
        assertEquals(11, progress.revealedGraphemes)

        // The stream retracted its tail (or the render dropped it): the new text
        // is a strict prefix of the old one.
        val update = progress.ingest("hello wor")
        assertEquals(RevealIngestKind.SHRANK, update.kind)
        assertEquals(9, progress.totalGraphemes)
        assertEquals(9, progress.revealedGraphemes)
        assertTrue(progress.backlogGraphemes == 0)
        // The surviving prefix is grapheme-aligned and inside the new text.
        assertEquals(9, update.survivingPrefixEnd)

        // Growing again continues from the clamped value — it does not reset.
        assertEquals(RevealIngestKind.APPENDED, progress.ingest("hello world").kind)
        assertEquals(9, progress.revealedGraphemes)
        assertEquals(2, progress.backlogGraphemes)
    }

    @Test
    fun `a rewrite clamps to the graphemes that actually survived`() {
        val progress = RevealProgress()
        progress.ingest("**bold text")
        progress.snapToTarget()
        assertEquals(11, progress.revealedGraphemes)

        // Closing markers rewrite the head of the rendered text: nothing of the
        // old layout survives, so progress clamps to the shared prefix — it never
        // jumps backwards further than the text moved, and never past it.
        val update = progress.ingest("bold text")
        assertEquals(RevealIngestKind.REBUILT, update.kind)
        assertEquals(0, update.survivingPrefixEnd)
        assertEquals(0, progress.revealedGraphemes)
        assertEquals(9, progress.totalGraphemes)
    }

    @Test
    fun `a rewritten text rebuilds the baseline instead of reusing offsets`() {
        val progress = RevealProgress()
        progress.ingest("first message")
        progress.snapToTarget()
        val update = progress.ingest("second message")
        assertEquals(RevealIngestKind.REBUILT, update.kind)
        assertEquals(0, progress.revealedGraphemes)
        assertEquals(14, progress.totalGraphemes)
        assertEquals(14, progress.backlogGraphemes)
    }

    @Test
    fun `speed rises with the backlog and never drops below the floor`() {
        assertEquals(BASE_REVEAL_GRAPHEMES_PER_SECOND, smoothRevealSpeed(0f), 0.001f)
        assertEquals(BASE_REVEAL_GRAPHEMES_PER_SECOND, smoothRevealSpeed(1f), 0.001f)
        // A backlog is always caught up inside the target window.
        assertEquals(100f / TARGET_CATCH_UP_SECONDS, smoothRevealSpeed(100f), 0.001f)
        var previous = 0f
        for (backlog in listOf(0f, 5f, 20f, 100f, 1_000f)) {
            val speed = smoothRevealSpeed(backlog)
            assertTrue("speed must not decrease as the backlog grows", speed >= previous)
            previous = speed
        }
    }

    @Test
    fun `advance clamps to the target and never passes it`() {
        assertEquals(10f, advanceSmoothReveal(current = 0f, target = 10f, elapsedSeconds = 1f, totalBacklog = 10f), 0.001f)
        assertEquals(5f, advanceSmoothReveal(current = 5f, target = 5f, elapsedSeconds = 1f, totalBacklog = 0f), 0.001f)
        // The target moved backwards (the text shrank): the value retreats to it
        // rather than staying above the text length.
        assertEquals(3f, advanceSmoothReveal(current = 5f, target = 3f, elapsedSeconds = 1f, totalBacklog = 1f), 0.001f)
        // Progress never passes the target even with an unlimited step.
        assertEquals(1f, advanceSmoothReveal(current = 0f, target = 1f, elapsedSeconds = 100f, totalBacklog = 1f), 0.001f)
    }

    @Test
    fun `a single stalled step is bounded by the frame budget`() {
        val progress = RevealProgress()
        progress.ingest("x".repeat(1_000))
        // A one-second "frame" (backgrounded app, GC pause) may only advance by
        // the clamped budget, not dump the whole paragraph at once.
        val advanced = progress.advance(1f)
        val expected = (smoothRevealSpeed(1_000f) * MAX_REVEAL_FRAME_SECONDS).toInt()
        assertEquals(expected, advanced)
        assertTrue(advanced < 1_000)
        // Repeated steps never exceed the target.
        repeat(200) { progress.advance(MAX_REVEAL_FRAME_SECONDS) }
        assertEquals(1_000, progress.revealedGraphemes)
    }

    @Test
    fun `common prefix never splits a surrogate pair`() {
        assertEquals(3, commonUtf16PrefixLength("a\uD83D\uDE00b", "a\uD83D\uDE00c"))
        // The high surrogate is the last equal unit: the prefix must stop before it.
        assertEquals(1, commonUtf16PrefixLength("a\uD83D", "a\uDE00"))
        assertEquals(0, commonUtf16PrefixLength("abc", "xyz"))
    }

    @Test
    fun `grapheme index floors to a safe cut`() {
        val index = AppendOnlyGraphemeIndex()
        index.update("a\uD83D\uDE00b")
        // "a" + emoji + "b" = three clusters over four UTF-16 units.
        assertEquals(3, index.graphemeCount)
        // Offset 2 lands inside the emoji: the safe cut is the boundary before it.
        assertEquals(1, index.alignedOffsetFloor(2))
        assertEquals(3, index.alignedOffsetFloor(3))
        assertEquals(4, index.alignedOffsetFloor(99))
        assertEquals(0, index.alignedOffsetFloor(0))
        // …and the same cut must not claim the half-emoji as revealed progress.
        assertEquals(1, index.graphemeCountUpTo(2))
        assertEquals(2, index.graphemeCountUpTo(3))
        assertEquals(3, index.graphemeCountUpTo(4))
        assertEquals(0, index.graphemeCountUpTo(0))
    }

    @Test
    fun `ingesting the same text twice is a no-op`() {
        val progress = RevealProgress()
        progress.ingest("stable")
        val update = progress.ingest("stable")
        assertEquals(RevealIngestKind.UNCHANGED, update.kind)
        assertEquals(6, update.survivingPrefixEnd)
        assertEquals(6, progress.totalGraphemes)
    }
}
