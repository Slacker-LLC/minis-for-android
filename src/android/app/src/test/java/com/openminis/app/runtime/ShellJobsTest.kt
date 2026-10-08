package com.openminis.app.runtime

import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.JobTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Background shell jobs: what runs, what is bounded, who may end them. The guest process is a fake. */
class ShellJobsTest {
    /** A job process the test controls: output lines it prints at start, and when (and how) it ends. */
    private class FakeProcess(private val lines: List<String> = emptyList(), private val failToStart: String? = null) :
        ShellJobs.JobProcess {
        val end = CompletableDeferred<ShellJobs.JobProcess.Result>()
        @Volatile var stopped = false

        override suspend fun run(command: String, timeoutMs: Long, onLine: (String) -> Unit): ShellJobs.JobProcess.Result {
            failToStart?.let { throw IllegalStateException(it) }
            lines.forEach(onLine)
            return end.await()
        }

        val received = StringBuilder()
        @Volatile var inputClosed = false

        override suspend fun writeInput(data: String, eof: Boolean): String? {
            received.append(data)
            if (eof) inputClosed = true
            return null
        }

        override fun stop() {
            stopped = true
            end.cancel()
        }
    }

    private val started = mutableListOf<String>()

    @After
    fun cleanUp() {
        started.forEach { JobRegistry.kill(it, "test cleanup") }
        ShellJobs.killAll()
    }

    private fun start(session: String, process: FakeProcess = FakeProcess(), command: String = "sleep 100"): String =
        ShellJobs.start(session, command, newProcess = { process }).also { started += it }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!check()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("timed out waiting for $what")
        }
    }

    private fun status(id: String) = JobRegistry.get(id)!!.status

    @Test
    fun `output reaches the registry as it is written and a clean exit completes the job`() {
        val process = FakeProcess(lines = listOf("one", "two"))
        val id = start("s1", process)
        waitFor("output") { JobRegistry.output(id) == "one\ntwo\n" }
        assertEquals(JobRegistry.JobStatus.RUNNING, status(id))
        assertEquals("shell", JobRegistry.get(id)!!.kind)

        process.end.complete(ShellJobs.JobProcess.Result(0))
        waitFor("completion") { status(id) == JobRegistry.JobStatus.COMPLETED }
        assertEquals("exit code 0", JobRegistry.get(id)!!.detail)
    }

    @Test
    fun `a non-zero exit and a timeout are failures that say why`() {
        val bad = FakeProcess()
        val badId = start("s1", bad)
        bad.end.complete(ShellJobs.JobProcess.Result(2))
        waitFor("failure") { status(badId) == JobRegistry.JobStatus.FAILED }
        assertEquals("exit code 2", JobRegistry.get(badId)!!.detail)

        val slow = FakeProcess()
        val slowId = start("s1", slow)
        slow.end.complete(ShellJobs.JobProcess.Result(124, timedOut = true))
        waitFor("timeout") { status(slowId) == JobRegistry.JobStatus.FAILED }
        assertTrue(JobRegistry.get(slowId)!!.detail.startsWith("timed out"))
    }

    @Test
    fun `a job that cannot start fails and keeps the reason in its output`() {
        val id = start("s1", FakeProcess(failToStart = "ubuntu unavailable"))
        waitFor("failure") { status(id) == JobRegistry.JobStatus.FAILED }
        assertTrue(JobRegistry.output(id)!!.contains("ubuntu unavailable"))
    }

    @Test
    fun `the generic job kill stops the guest process`() {
        val process = FakeProcess()
        val id = start("s1", process)
        assertTrue(JobRegistry.kill(id, "no longer needed"))
        waitFor("process stop") { process.stopped }
        assertEquals(JobRegistry.JobStatus.KILLED, status(id))
        assertEquals("no longer needed", JobRegistry.get(id)!!.detail)
    }

    @Test
    fun `a session runs only a few jobs and says so instead of queueing`() {
        repeat(ShellJobs.MAX_RUNNING_PER_SESSION) { start("busy") }
        try {
            start("busy")
            fail("the seventh job of one session must be refused")
        } catch (limit: ShellJobs.LimitExceeded) {
            assertTrue(limit.message!!.contains("${ShellJobs.MAX_RUNNING_PER_SESSION}"))
        }
        // Another session is not affected by it.
        start("other")
    }

    @Test
    fun `a finished job frees its slot`() {
        val ids = (1..ShellJobs.MAX_RUNNING_PER_SESSION).map { i ->
            val p = FakeProcess()
            start("slots", p) to p
        }
        ids.first().second.end.complete(ShellJobs.JobProcess.Result(0))
        waitFor("slot") { status(ids.first().first) == JobRegistry.JobStatus.COMPLETED }
        start("slots")
    }

    @Test
    fun `ending a session kills its jobs and only its jobs`() {
        val mine = FakeProcess()
        val theirs = FakeProcess()
        val mineId = start("ending", mine)
        val theirId = start("staying", theirs)
        ShellJobs.killSession("ending")
        waitFor("kill") { mine.stopped }
        assertEquals(JobRegistry.JobStatus.KILLED, status(mineId))
        assertFalse(theirs.stopped)
        assertEquals(JobRegistry.JobStatus.RUNNING, status(theirId))
    }

    @Test
    fun `a runtime-wide stop kills every job`() {
        val a = FakeProcess()
        val b = FakeProcess()
        start("a", a)
        start("b", b)
        ShellJobs.killAll()
        waitFor("both stopped") { a.stopped && b.stopped }
    }

    @Test
    fun `output keeps only a bounded tail and positions still count from the first character`() {
        val id = JobRegistry.start("test", "chatty").also { started += it }
        val line = "x".repeat(99) + "\n"
        repeat(3_000) { JobRegistry.appendOutput(id, line) } // 300,000 characters
        val total = 3_000L * 100
        val read = JobRegistry.read(id, 0L, 20_000)!!
        assertEquals(total, read.nextOffset)
        assertEquals(20_000, read.text.length)
        assertEquals(total - 20_000, read.from)
        assertTrue("the head and the middle are reported as missed", read.missed > 0)
        assertTrue(JobRegistry.output(id)!!.length <= JobRegistry.MAX_OUTPUT_CHARS)

        // A reader that remembers its place gets exactly what is new.
        JobRegistry.appendOutput(id, "tail\n")
        val next = JobRegistry.read(id, read.nextOffset, 20_000)!!
        assertEquals("tail\n", next.text)
        assertEquals(0L, next.missed)
        assertEquals(total + 5, next.nextOffset)
    }

    @Test
    fun `job_output with an offset returns only the new output and a next_offset`() = runBlocking {
        val id = JobRegistry.start("test", "poller").also { started += it }
        JobRegistry.appendOutput(id, "first\n")
        val one = JobTools.jobOutput("""{"tool_title":"t","job_id":"$id"}""")
        assertTrue(one.output.contains("first"))
        val offset = Regex("""\[next_offset: (\d+)]""").find(one.output)!!.groupValues[1]
        assertEquals("6", offset)

        JobRegistry.appendOutput(id, "second\n")
        val two = JobTools.jobOutput("""{"tool_title":"t","job_id":"$id","offset":$offset}""")
        assertTrue(two.output.contains("second"))
        assertFalse("already-read output is not repeated", two.output.contains("first"))
        assertTrue(two.output.contains("[status: RUNNING]"))

        JobRegistry.finish(id, JobRegistry.JobStatus.FAILED, "exit code 3")
        val done = JobTools.jobOutput("""{"tool_title":"t","job_id":"$id","offset":99}""")
        assertTrue(done.output.contains("[status: FAILED — exit code 3]"))
        assertNotNull(done.output)
    }

    @Test
    fun `input reaches only a running job of the same session`() = runBlocking {
        val process = FakeProcess()
        val id = start("s1", process)
        waitFor("running") { status(id) == JobRegistry.JobStatus.RUNNING }

        assertEquals(null, ShellJobs.writeInput("s1", id, "yes\n", eof = false))
        assertEquals(null, ShellJobs.writeInput("s1", id, "", eof = true))
        assertEquals("yes\n", process.received.toString())
        assertTrue(process.inputClosed)

        assertTrue("another session is refused", ShellJobs.writeInput("s2", id, "x", false)!!.contains("another session"))
        assertTrue("an unknown job is refused", ShellJobs.writeInput("s1", "nope", "x", false)!!.contains("no such job"))
        assertEquals("yes\n", process.received.toString())

        process.end.complete(ShellJobs.JobProcess.Result(0))
        waitFor("completion") { status(id) == JobRegistry.JobStatus.COMPLETED }
        assertTrue("a finished job takes no input", ShellJobs.writeInput("s1", id, "late", false)!!.isNotEmpty())
    }
}
