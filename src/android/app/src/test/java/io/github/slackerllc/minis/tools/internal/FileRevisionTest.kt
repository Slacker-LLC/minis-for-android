package io.github.slackerllc.minis.tools.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileRevisionTest {
    @Test fun sha256TracksContent() {
        val f = File.createTempFile("minis-revision", ".txt")
        try {
            f.writeText("abc")
            val a = FileRevision.sha256(f)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", a)
            f.writeText("abcd")
            assertNotEquals(a, FileRevision.sha256(f))
        } finally { f.delete() }
    }
}
