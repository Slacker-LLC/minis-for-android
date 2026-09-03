package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReadOutputFormatterTest {
    @Test
    fun headTruncationReportsSurvivingRangeAndNextOffset() {
        val output = FileReadOutputFormatter.format(
            path = "/workspace/large.log",
            size = 1_024,
            totalLines = 10,
            selectedLines = (1..10).map { "line-%02d".format(it) },
            showStart = 1,
            direction = "head",
            maxLength = 16,
        )

        assertTrue(output.startsWith(
            "[/workspace/large.log | 1024 bytes | 10 lines | " +
                "showing 1-2 of 10 | truncated at 16 chars, next_offset=3]\n",
        ))
        assertEquals("line-01\nline-02", output.substringAfter('\n'))
        assertFalse(output.contains("showing 1-10 of 10"))
    }

    @Test
    fun tailTruncationReportsEndRangeWithoutForwardCursor() {
        val output = FileReadOutputFormatter.format(
            path = "/workspace/large.log",
            size = 1_024,
            totalLines = 10,
            selectedLines = (1..10).map { "line-%02d".format(it) },
            showStart = 1,
            direction = "tail",
            maxLength = 16,
        )

        assertTrue(output.startsWith(
            "[/workspace/large.log | 1024 bytes | 10 lines | " +
                "showing 9-10 of 10 | truncated at 16 chars, " +
                "retry with a smaller lines value]\n",
        ))
        assertEquals("line-09\nline-10", output.substringAfter('\n'))
        assertFalse(output.contains("next_offset="))
    }

    @Test
    fun fittingReadKeepsRangeAndBodyUnchanged() {
        val output = FileReadOutputFormatter.format(
            path = "/workspace/notes.txt",
            size = 8,
            totalLines = 5,
            selectedLines = listOf("three", "four"),
            showStart = 3,
            direction = "head",
            maxLength = 100,
        )

        assertEquals(
            "[/workspace/notes.txt | 8 bytes | 5 lines | showing 3-4 of 5]\nthree\nfour",
            output,
        )
        assertFalse(output.contains("truncated"))
    }

    @Test
    fun definitionExplainsHowToContinueAfterTruncation() {
        val offsetDescription = FileReadTool.definition().parameters["offset"]?.description.orEmpty()
        assertTrue(offsetDescription.contains("next_offset=N"))
        assertTrue(offsetDescription.contains("offset"))
    }
}
