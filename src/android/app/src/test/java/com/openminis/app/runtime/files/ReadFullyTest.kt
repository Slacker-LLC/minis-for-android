package com.openminis.app.runtime.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class ReadFullyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun channelOf(vararg content: Byte): FileChannel {
        val file = tmp.newFile().apply { writeBytes(content) }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ)
    }

    @Test
    fun aFileLongerThanTheBufferFillsIt() {
        channelOf(1, 2, 3, 4, 5).use { ch ->
            val out = ByteArray(3)
            assertEquals(3, SecureFileAccess.readFully(ch, out))
            assertArrayEquals(byteArrayOf(1, 2, 3), out)
        }
    }

    @Test
    fun aFileThatShrankAfterTheStatReportsWhatWasThere() {
        // The caller sized the buffer for 5 bytes; only 2 are left.
        channelOf(9, 8).use { ch ->
            val out = ByteArray(5)
            val read = SecureFileAccess.readFully(ch, out)
            assertEquals(2, read)
            assertArrayEquals(byteArrayOf(9, 8), out.copyOf(read))
        }
    }

    @Test
    fun anEmptyFileReadsZeroBytes() {
        channelOf().use { ch ->
            assertEquals(0, SecureFileAccess.readFully(ch, ByteArray(4)))
        }
    }

    @Test
    fun readingFromAnOffsetPastTheEndReadsNothing() {
        channelOf(1, 2, 3).use { ch ->
            ch.position(10)
            assertEquals(0, SecureFileAccess.readFully(ch, ByteArray(4)))
        }
    }
}
