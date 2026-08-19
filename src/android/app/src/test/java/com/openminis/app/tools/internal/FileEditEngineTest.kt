package com.openminis.app.tools.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileEditEngineTest {
    @Test fun multiEditUsesOriginalSnapshot() {
        val src = "alpha\nbeta\ngamma\n"
        val r = FileEditEngine.apply(src, listOf(
            FileEditEngine.Edit("alpha", "A"),
            FileEditEngine.Edit("gamma", "G"),
        ), "x.txt")
        assertEquals("A\nbeta\nG\n", r.newContent)
        assertEquals(2, r.replacementCount)
        assertTrue(r.diff.contains("-alpha"))
        assertTrue(r.diff.contains("+A"))
    }

    @Test fun rejectsOverlappingEdits() {
        try {
            FileEditEngine.apply("abcdef", listOf(
                FileEditEngine.Edit("abcd", "X"),
                FileEditEngine.Edit("cdef", "Y"),
            ), "x.txt")
            fail("expected overlap rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("overlap"))
        }
    }

    @Test fun rejectsAmbiguousMatch() {
        try {
            FileEditEngine.apply("x x x", listOf(FileEditEngine.Edit("x", "y")), "x.txt")
            fail("expected ambiguity rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("found 3 times"))
        }
    }

    @Test fun fuzzyMatchesSmartQuotesAndTrailingWhitespace() {
        val src = "val s = “hello”   \nnext\n"
        val r = FileEditEngine.apply(src, listOf(
            FileEditEngine.Edit("val s = \"hello\"", "val s = \"world\"")
        ), "x.kt")
        assertEquals("val s = \"world\"\nnext\n", r.newContent)
        assertEquals(1, r.fuzzyMatchCount)
    }

    @Test fun preservesBomAndCrLf() {
        val src = "\uFEFFa\r\nb\r\n"
        val r = FileEditEngine.apply(src, listOf(FileEditEngine.Edit("b", "B")), "x.txt")
        assertEquals("\uFEFFa\r\nB\r\n", r.newContent)
    }
}
