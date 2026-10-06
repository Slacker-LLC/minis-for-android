package com.openminis.app.tools.runtime

import com.openminis.app.tools.JobRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The job_* names are published with their own schemas, which have no `action` parameter, and are
 * registered as aliases of the merged `system.jobs` handler. A call made exactly as published must run the
 * action its name says; and a generic cancel must stop the worker, not only relabel the job.
 */
class ActionAliasJobsTest {
    private var registeredHere = false

    @Before
    fun setUp() {
        if (ToolRegistry.handler("system.jobs") == null) {
            ToolRegistry.register(SystemJobsHandler(), listOf("job_list", "job_kill", "job_output"), aliasesAreActions = true)
            registeredHere = true
        }
    }

    @After
    fun tearDown() {
        if (registeredHere) ToolRegistry.unregister("system.jobs")
    }

    private fun call(name: String, args: JSONObject) = runBlocking {
        ToolExecutor.execute(name, args.toString(), "alias-jobs-session", TestContext.dummy())
    }

    @Test
    fun `an alias call without action runs the action the alias names`() {
        val jobId = JobRegistry.start("test", "alias list ${System.nanoTime()}")
        try {
            val result = call("job_list", JSONObject().put("tool_title", "list jobs"))
            assertTrue(result.output, result.success)
            assertTrue("the started job is listed", result.output.contains(jobId))
        } finally {
            JobRegistry.finish(jobId, JobRegistry.JobStatus.COMPLETED)
        }
    }

    @Test
    fun `an explicit action is kept and a plain call is left alone`() {
        assertEquals(
            "job_output",
            JSONObject(ToolRegistry.argsForCall("job_list", """{"action":"job_output"}""")).getString("action"),
        )
        assertEquals("""{"x":1}""", ToolRegistry.argsForCall("system.jobs", """{"x":1}"""))
        assertFalse(JSONObject(ToolRegistry.argsForCall("linux.shell", "{}")).has("action"))
    }

    @Test
    fun `job_kill stops the worker and later output is dropped`() {
        val jobId = JobRegistry.start("test", "alias kill ${System.nanoTime()}")
        var cancelled = false
        JobRegistry.setCanceller(jobId) { cancelled = true }

        val result = call("job_kill", JSONObject().put("tool_title", "stop").put("job_id", jobId))

        assertTrue(result.output, result.success)
        assertTrue("the worker's own cancel ran", cancelled)
        assertEquals(JobRegistry.JobStatus.KILLED, JobRegistry.get(jobId)?.status)
        JobRegistry.appendOutput(jobId, "late line")
        assertFalse("a stopped job takes no more output", JobRegistry.output(jobId).orEmpty().contains("late line"))
    }

    @Test
    fun `a canceller registered after the kill runs at once`() {
        val jobId = JobRegistry.start("test", "late canceller ${System.nanoTime()}")
        JobRegistry.kill(jobId, "test")
        var cancelled = false
        JobRegistry.setCanceller(jobId) { cancelled = true }
        assertTrue(cancelled)
    }
}
