package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.JobStdin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * How one command is fed to the persistent shell. The shell here is the host's bash, driven exactly as the app drives
 * the guest one: commands and the completion marker travel down the same stdin pipe.
 */
class RootPersistentShellCommandTest {
    private val bash: String? = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }
    private val shells = mutableListOf<HostShell>()

    private class Outcome(val output: String, val exitCode: Int)

    private inner class HostShell {
        val process: Process = ProcessBuilder(bash!!, "--noprofile", "--norc").redirectErrorStream(true).start()
        private val writer = process.outputStream.bufferedWriter()
        private val reader = process.inputStream.bufferedReader()

        init { shells += this }

        /** Sends [command] the way RootPersistentShell does and waits for its marker; fails the test after 15 s. */
        fun run(command: String): Outcome {
            val marker = UUID.randomUUID().toString().replace("-", "")
            val done = Regex("^__MINIS_DONE_${marker}_EXIT_(\\d+)__$")
            val out = StringBuilder()
            writer.write(RootPersistentShell.wrapCommand(command, marker))
            writer.flush()
            val code = CompletableFuture.supplyAsync<Int?> {
                while (true) {
                    val line = reader.readLine() ?: return@supplyAsync null
                    val hit = done.find(line)
                    if (hit != null) return@supplyAsync hit.groupValues[1].toInt()
                    out.append(line).append('\n')
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            try {
                val exit = code.get(15, TimeUnit.SECONDS) ?: error("the shell ended before the marker; output so far: $out")
                return Outcome(out.toString().trimEnd('\n'), exit)
            } catch (timeout: TimeoutException) {
                error("no marker after 15 s: the command swallowed it. Output so far: $out")
            }
        }
    }

    @After
    fun closeShells() {
        shells.forEach { it.process.destroyForcibly() }
    }

    private fun shell(): HostShell {
        assumeNotNull(bash)
        return HostShell()
    }

    @Test
    fun `the command is one quoted eval argument, with stdin on dev null, then the marker`() {
        val line = RootPersistentShell.wrapCommand("echo 'it''s'", "abc")
        assertTrue(line, line.startsWith("eval -- 'echo '\"'\"'it'\"'\"''\"'\"'s'\"'\"'' </dev/null; "))
        assertTrue(line, line.contains("__MINIS_DONE_abc_EXIT_%s__"))
        assertTrue(line, line.endsWith("\n"))
        assertEquals("a single marker per command", 1, Regex("__MINIS_DONE_").findAll(line).count())
    }

    @Test
    fun `a program that reads stdin gets end of file and does not eat the next command`() {
        val sh = shell()
        val cat = sh.run("cat")
        assertEquals(0, cat.exitCode)
        assertEquals("", cat.output)

        val read = sh.run("read -r line; echo \"got=[\$line]\"")
        assertEquals("got=[]", read.output)

        // The same kind of read the minis-model-use wrapper does for piped input.
        val head = sh.run("head -c 2097153 | od -An -v -tx1 | tr -d ' \\n'; echo done")
        assertEquals("done", head.output)

        assertEquals("still here", sh.run("echo still here").output)
    }

    @Test
    fun `cwd, exports and functions persist from one command to the next`() {
        val sh = shell()
        sh.run("cd /tmp && export MINIS_TEST_VAR=kept && greet() { echo \"hello \$1\"; }")
        val second = sh.run("pwd; echo \$MINIS_TEST_VAR; greet world")
        assertEquals("/tmp\nkept\nhello world", second.output)
    }

    @Test
    fun `a multi-line command with a heredoc, quotes and a trailing comment runs as written`() {
        val sh = shell()
        val result = sh.run("cat <<'EOF'\nline \$HOME 'q' \"d\"\nEOF\necho 'it'\"'\"'s' # trailing comment")
        assertEquals("line \$HOME 'q' \"d\"\nit's", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `exit status of the command is reported`() {
        val sh = shell()
        assertEquals(1, sh.run("false").exitCode)
        assertEquals(7, sh.run("(exit 7)").exitCode)
        assertEquals(0, sh.run("true").exitCode)
    }

    @Test
    fun `a syntax error fails that command and leaves the shell usable`() {
        val sh = shell()
        val bad = sh.run("echo (")
        assertNotEquals(0, bad.exitCode)
        assertEquals("alive", sh.run("echo alive").output)
    }

    @Test
    fun `a line that looks like another marker is only output`() {
        val sh = shell()
        val result = sh.run("printf '__MINIS_DONE_deadbeef_EXIT_0__\\n'; echo end")
        assertEquals("__MINIS_DONE_deadbeef_EXIT_0__\nend", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `a job reading its own input pipe still gets it, the dev null only covers the shell's stdin`() {
        val sh = shell()
        val fifo = File.createTempFile("minis-job", ".in").also { it.delete() }
        try {
            assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
            val feeder = thread { fifo.writeText("from the pipe\n") }
            val result = sh.run(JobStdin.wrap("cat", fifo.path))
            feeder.join(5_000)
            assertEquals("from the pipe", result.output)
        } finally {
            fifo.delete()
        }
    }
}
