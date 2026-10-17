package com.openminis.app.runtime

import com.openminis.app.tools.JobRegistry
import com.openminis.app.tools.ToolFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A foreground command that outlives its window is handed back as a job. The guest shell is a fake. */
class ShellYieldTest {
    private val session = "yield-test-session"
    private val started = mutableListOf<String>()

    /** A command the test controls: lines it has printed, and when (and how) it ends. */
    private class FakeCommand {
        val end = CompletableDeferred<ExecutionCoordinator.CommandResult>()
        @Volatile var cancelled = false
        @Volatile var onLine: (String) -> Unit = {}

        suspend fun run(lines: List<String>, onLine: (String) -> Unit): ExecutionCoordinator.CommandResult {
            this.onLine = onLine
            lines.forEach(onLine)
            return try {
                end.await()
            } catch (c: CancellationException) {
                cancelled = true
                throw c
            }
        }
    }

    @After
    fun cleanUp() {
        ShellYield.stopAll("test cleanup")
        started.forEach { JobRegistry.kill(it, "test cleanup") }
    }

    private fun result(output: String = "", exit: Int = 0) = ExecutionCoordinator.CommandResult(output, exit, 1L)

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!check()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("timed out waiting for $what")
        }
    }

    private fun yielded(outcome: ShellYield.Outcome): String {
        val y = outcome as? ShellYield.Outcome.Yielded ?: error("expected Yielded, got $outcome")
        started += y.jobId
        return y.jobId
    }

    private fun run(command: FakeCommand, lines: List<String> = emptyList(), yieldMs: Long = 100, timeoutMs: Long = 900_000) = runBlocking {
        ShellYield.run(session, "sleep 100", timeoutMs, yieldMs) { onLine -> command.run(lines, onLine) }
    }

    @Test
    fun `a command that ends inside the window returns its plain result and leaves no job`() {
        val before = JobRegistry.list().size
        val command = FakeCommand().apply { end.complete(result("done", 0)) }
        val outcome = run(command, listOf("done"), yieldMs = 5_000)
        assertEquals("done", (outcome as ShellYield.Outcome.Finished).result.output)
        assertEquals(before, JobRegistry.list().size)
        assertNull(ShellYield.busyJob(session))
    }

    @Test
    fun `a failing command inside the window keeps its exit code`() {
        val command = FakeCommand().apply { end.complete(result("boom\n(exit code: 2)", 2)) }
        val outcome = run(command, yieldMs = 5_000) as ShellYield.Outcome.Finished
        assertEquals(2, outcome.result.exitCode)
    }

    @Test
    fun `a command still running at the end of the window is handed back with what it printed`() {
        val command = FakeCommand()
        val id = yielded(run(command, listOf("step 1", "step 2")))
        val job = JobRegistry.get(id)!!
        assertEquals(JobRegistry.JobStatus.RUNNING, job.status)
        assertEquals("shell", job.kind)
        assertEquals(session, job.sessionId)
        assertEquals("step 1\nstep 2\n", JobRegistry.output(id))
        assertEquals(id, ShellYield.busyJob(session))

        val reply = ShellYield.reply(id, 30_000)
        assertTrue(reply, reply.contains("job_id: $id"))
        assertTrue(reply, reply.contains("status: RUNNING"))
        assertTrue(reply, reply.contains("step 2"))
        assertTrue(reply, reply.contains("job_output"))
        assertTrue(reply, reply.contains("job_kill"))
    }

    @Test
    fun `output after the hand-back goes on into the job and the end settles it`() {
        val command = FakeCommand()
        val id = yielded(run(command, listOf("early")))
        command.onLine("late")
        assertEquals("early\nlate\n", JobRegistry.output(id))

        command.end.complete(result("early\nlate", 0))
        waitFor("completion") { JobRegistry.get(id)!!.status == JobRegistry.JobStatus.COMPLETED }
        assertEquals("exit code 0", JobRegistry.get(id)!!.detail)
        assertNull(ShellYield.busyJob(session))
    }

    @Test
    fun `a non-zero exit after the hand-back is a failed job`() {
        val command = FakeCommand()
        val id = yielded(run(command, listOf("oops")))
        command.end.complete(result("oops\n(exit code: 2)", 2))
        waitFor("failure") { JobRegistry.get(id)!!.status == JobRegistry.JobStatus.FAILED }
        assertEquals("exit code 2", JobRegistry.get(id)!!.detail)
        assertNull(ShellYield.busyJob(session))
    }

    @Test
    fun `the command deadline after the hand-back is a timeout failure`() {
        val command = FakeCommand()
        val id = yielded(run(command, timeoutMs = 90_000))
        command.end.complete(result("command timed out after 90000ms", 124))
        waitFor("timeout") { JobRegistry.get(id)!!.status == JobRegistry.JobStatus.FAILED }
        val job = JobRegistry.get(id)!!
        assertEquals("timed out after 90s", job.detail)
        assertEquals(ToolFailureKind.TOOL_TIMEOUT, job.failureKind)
        assertTrue(JobRegistry.output(id)!!.contains("command timed out"))
    }

    @Test
    fun `a command that printed nothing still shows why it failed`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        command.end.complete(result("session is being terminated", 1))
        waitFor("failure") { JobRegistry.get(id)!!.status == JobRegistry.JobStatus.FAILED }
        assertTrue(JobRegistry.output(id)!!.contains("session is being terminated"))
    }

    @Test
    fun `job_kill stops the running command and frees the shell`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        assertTrue(JobRegistry.kill(id, "no longer needed"))
        waitFor("cancellation") { command.cancelled }
        assertEquals(JobRegistry.JobStatus.KILLED, JobRegistry.get(id)!!.status)
        assertNull(ShellYield.busyJob(session))
    }

    @Test
    fun `a user Stop or the end of the session ends the handed-back command`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        ShellYield.stopSession(session, "stopped")
        waitFor("cancellation") { command.cancelled }
        assertEquals(JobRegistry.JobStatus.KILLED, JobRegistry.get(id)!!.status)
        assertEquals("stopped", JobRegistry.get(id)!!.detail)
    }

    @Test
    fun `a stop of another session leaves the command alone`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        ShellYield.stopSession("some-other-session", "stopped")
        assertEquals(JobRegistry.JobStatus.RUNNING, JobRegistry.get(id)!!.status)
        assertFalse(command.cancelled)
    }

    @Test
    fun `a runtime-wide stop ends every handed-back command`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        ShellYield.stopAll("runtime stopped")
        waitFor("cancellation") { command.cancelled }
        assertEquals(JobRegistry.JobStatus.KILLED, JobRegistry.get(id)!!.status)
    }

    @Test
    fun `cancelling the caller inside the window stops the command and creates no job`() {
        val before = JobRegistry.list().size
        val command = FakeCommand()
        runBlocking {
            val call = async { ShellYield.run(session, "sleep 100", 900_000, 30_000) { onLine -> command.run(emptyList(), onLine) } }
            delay(100)
            call.cancel()
            try { call.await() } catch (_: CancellationException) {}
        }
        waitFor("cancellation") { command.cancelled }
        assertEquals(before, JobRegistry.list().size)
        assertNull(ShellYield.busyJob(session))
    }

    @Test
    fun `an exception from the command becomes a failed result, not a crash`() {
        val outcome = runBlocking {
            ShellYield.run(session, "x", 900_000, 5_000) { _ -> throw IllegalStateException("shell unavailable") }
        }
        val finished = outcome as ShellYield.Outcome.Finished
        assertEquals(1, finished.result.exitCode)
        assertEquals("shell unavailable", finished.result.output)
    }

    @Test
    fun `the shell is only busy for the session that owns the command`() {
        val command = FakeCommand()
        val id = yielded(run(command))
        assertEquals(id, ShellYield.busyJob(session))
        assertNull(ShellYield.busyJob("some-other-session"))
        assertTrue(ShellYield.busyMessage(id).contains(id))
        assertTrue(ShellYield.busyMessage(id).contains("background=true"))
    }

    @Test
    fun `the yield window is bounded`() {
        assertEquals(ShellYield.DEFAULT_YIELD_MS, ShellYield.clampYield(null))
        assertEquals(ShellYield.MIN_YIELD_MS, ShellYield.clampYield(0))
        assertEquals(ShellYield.MIN_YIELD_MS, ShellYield.clampYield(-5))
        assertEquals(ShellYield.MAX_YIELD_MS, ShellYield.clampYield(10 * 60_000L))
        assertEquals(5_000L, ShellYield.clampYield(5_000))
    }

    @Test
    fun `a finished job is reported as finished, not running`() {
        val command = FakeCommand()
        val id = yielded(run(command, listOf("ok")))
        command.end.complete(result("ok", 0))
        waitFor("completion") { JobRegistry.get(id)!!.status == JobRegistry.JobStatus.COMPLETED }
        val reply = ShellYield.reply(id, 30_000)
        assertTrue(reply, reply.contains("status: COMPLETED"))
        assertFalse(reply, reply.contains("job_kill"))
        assertNotNull(JobRegistry.get(id))
    }
}
