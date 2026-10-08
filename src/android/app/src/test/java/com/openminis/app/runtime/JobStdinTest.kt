package com.openminis.app.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/** The pipe behind a job's stdin, on a real named pipe: input arrives in order and closing it is end-of-file. */
class JobStdinTest {
    private fun hostMkfifo(file: File) {
        val ok = ProcessBuilder("mkfifo", file.path).start().waitFor() == 0
        assumeTrue("mkfifo is unavailable on this test host", ok)
    }

    @Test
    fun `lines written reach the reader in order and eof ends its input`() = runBlocking {
        val dir = Files.createTempDirectory("minis-job-stdin").toFile()
        try {
            val pipe = File(dir, "in")
            val stdin = JobStdin(pipe, "/tmp/in", ::hostMkfifo)
            assertTrue(stdin.create())

            val seen = StringBuilder()
            val reader = Thread {
                FileInputStream(pipe).use { input -> input.readBytes().let { seen.append(String(it)) } }
            }.apply { start() }

            assertNull(stdin.write("first\n", eof = false, waitForJobMs = 5_000))
            assertNull(stdin.write("second\n", eof = true, waitForJobMs = 5_000))
            reader.join(5_000)
            assertFalse("the reader saw end-of-file", reader.isAlive)
            assertEquals("first\nsecond\n", seen.toString())

            assertTrue("input after eof is refused", stdin.write("late", eof = false, waitForJobMs = 100)!!.contains("closed"))
            stdin.close()
            assertFalse("the pipe is removed", pipe.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a job that never reads does not leak the waiting writer, and early input says so`() = runBlocking {
        val dir = Files.createTempDirectory("minis-job-stdin").toFile()
        try {
            val pipe = File(dir, "in")
            val stdin = JobStdin(pipe, "/tmp/in", ::hostMkfifo)
            assertTrue(stdin.create())
            assertTrue(stdin.write("hi", eof = false, waitForJobMs = 100)!!.contains("not started reading"))
            stdin.close() // must return even though no reader ever came
            assertFalse(pipe.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runWrapped(command: String, pipe: File, stdinText: String?): String {
        val script = JobStdin.wrap(command, pipe.path)
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        // A shell that waits for a writer that never comes must fail the test, not hang the whole suite.
        Thread { if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly() }
            .apply { isDaemon = true; start() }
        if (stdinText != null) Thread { pipe.outputStream().use { it.write(stdinText.toByteArray()) } }.start()
        return String(process.inputStream.readBytes()).trim()
    }

    @Test
    fun `the wrapped command reads the pipe, and without a pipe still runs`() {
        val dir = Files.createTempDirectory("minis-job-wrap").toFile()
        try {
            val pipe = File(dir, "in")
            hostMkfifo(pipe)
            assertEquals("hello", runWrapped("cat", pipe, "hello"))
            // the pipe has a reader (the job) and a writer (the app); a command that ignores stdin is unaffected
            assertEquals("multi\nline", runWrapped("echo multi\necho line", pipe, ""))
            pipe.delete()
            assertEquals("still runs", runWrapped("echo still runs", pipe, null))
        } finally {
            dir.deleteRecursively()
        }
    }
}
