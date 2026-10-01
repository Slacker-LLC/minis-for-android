package com.openminis.app.agent.subagents

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SubAgentRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** A child run the test can finish by hand. */
    private class FakePort(var rosterDefs: List<SubAgentDefinition> = SubAgentRoster.normalize(emptyList())) : SubAgentPort {
        val gates = ConcurrentHashMap<String, CompletableDeferred<ChildOutcome>>()
        val created = CopyOnWriteArrayList<String>()
        val briefs = ConcurrentHashMap<String, String>()
        val thinking: MutableMap<String, ThinkingLevel?> = java.util.Collections.synchronizedMap(HashMap())
        val cancelled = CopyOnWriteArrayList<String>()
        val steered = CopyOnWriteArrayList<Pair<String, String>>()
        val delivered = CopyOnWriteArrayList<Pair<String, String>>()
        var modelAvailable = true
        var steerResult = true
        var createFails = false
        private var next = 0

        override fun roster() = rosterDefs
        override suspend fun resolveModel(def: SubAgentDefinition, choice: SubAgentModelChoice, parentSessionId: String) =
            if (modelAvailable) SubAgentModel("entry-${choice.wire}", "Model ${choice.wire}", def.pinnedEntryId?.let { "agent" } ?: choice.wire) else null

        override suspend fun createChild(parentSessionId: String, title: String, model: SubAgentModel): String {
            if (createFails) error("no session")
            val id = "child-${++next}"
            created += id
            gates[id] = CompletableDeferred()
            return id
        }

        /** Children that were "restarted with the app": their next run gets a fresh gate. */
        val freshGate = java.util.concurrent.CopyOnWriteArraySet<String>()
        val messages = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun runChild(childSessionId: String, brief: String, thinking: ThinkingLevel?, timeoutMs: Long): ChildOutcome {
            briefs[childSessionId] = brief
            messages += childSessionId to brief
            this.thinking[childSessionId] = thinking
            if (freshGate.remove(childSessionId)) gates[childSessionId] = CompletableDeferred()
            return gates.getValue(childSessionId).await()
        }

        override suspend fun cancelChild(childSessionId: String): Boolean {
            cancelled += childSessionId
            gates[childSessionId]?.complete(ChildOutcome(completed = false, text = "partial work", timedOut = false))
            return true
        }

        override suspend fun steerChild(childSessionId: String, message: String): Boolean {
            steered += childSessionId to message
            return steerResult
        }

        @Volatile var progress: ChildProgress? = null
        @Volatile var wrapUp: String? = "wrapped up"
        val wrapUps = CopyOnWriteArrayList<String>()

        override suspend fun describeChild(childSessionId: String) = progress

        override suspend fun wrapUpChild(childSessionId: String, graceMs: Long): String? {
            wrapUps += childSessionId
            return wrapUp
        }

        override suspend fun deliverToParent(parentSessionId: String, text: String) {
            delivered += parentSessionId to text
        }

        fun finish(childId: String, text: String, completed: Boolean = true, timedOut: Boolean = false) {
            gates.getValue(childId).complete(ChildOutcome(completed, text, timedOut))
        }
    }

    private fun runtime(port: FakePort, maxConcurrent: Int = 3, maxQueued: Int = 10, progressMs: Long? = null) =
        if (progressMs == null) SubAgentRuntime(port, SubAgentJobRegistry(maxConcurrent, maxQueued), scope)
        else SubAgentRuntime(port, SubAgentJobRegistry(maxConcurrent, maxQueued), scope, progressIntervalMs = { if (it == "none") null else progressMs })

    private fun args(vararg kv: Pair<String, Any>) = JSONObject(mapOf<String, Any>("tool_title" to "t", "task" to "do it") + kv).toString()

    private suspend fun until(what: String, cond: () -> Boolean) = withTimeout(5_000) {
        while (!cond()) delay(5)
    }.also { assertTrue(what, cond()) }

    private fun json(reply: SubAgentReply) = JSONObject(reply.text)

    // ── delegate ────────────────────────────────────────────────────────────

    @Test
    fun `a background delegation returns at once and the result is posted back to the parent`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val reply = rt.execute(args("agent" to "General Sub Agent"), "chat-A")

        assertTrue(reply.text, reply.ok)
        val started = json(reply)
        assertEquals("running", started.getString("status"))
        val jobId = started.getString("job_id")

        until("child created") { port.created.isNotEmpty() }
        assertTrue(port.briefs.getValue("child-1").contains("do it"))
        port.finish("child-1", "the answer")

        until("callback delivered") { port.delivered.isNotEmpty() }
        val (parent, text) = port.delivered.single()
        assertEquals("chat-A", parent)
        assertTrue(text, text.startsWith("[Background task finished"))
        assertTrue(text, text.contains("status=completed"))
        assertTrue(text, text.contains("the answer"))
        assertEquals(SubAgentJobState.DONE, rt.registry.get(jobId)!!.state)
    }

    @Test
    fun `wait=true blocks for the result and posts no callback`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val call = scope.launch { }.let { CompletableDeferred<SubAgentReply>() }
        scope.launch { call.complete(rt.execute(args("wait" to true), "chat-A")) }
        until("child created") { port.created.isNotEmpty() }
        assertFalse("still blocked", call.isCompleted)
        port.finish("child-1", "final text")

        val reply = withTimeout(5_000) { call.await() }
        assertTrue(reply.ok)
        assertEquals("completed", json(reply).getString("status"))
        assertEquals("final text", json(reply).getString("result"))
        assertTrue(port.delivered.isEmpty())
    }

    @Test
    fun `an agent's thinking level and a pinned model reach the child`() = runBlocking {
        val port = FakePort(
            SubAgentRoster.normalize(
                listOf(
                    SubAgentDefinition(
                        id = "r1", name = "researcher", description = "digs",
                        instructions = "Always cite sources.", thinkingLevelOverride = ThinkingLevel.HIGH,
                        modelBinding = ModelBinding.encodeEntry("entry-9"),
                    ),
                ),
            ),
        )
        val rt = runtime(port)
        rt.execute(args("agent" to "Researcher"), "chat-A")
        until("child created") { port.created.isNotEmpty() }
        assertEquals(ThinkingLevel.HIGH, port.thinking["child-1"])
        assertTrue(port.briefs.getValue("child-1").contains("Always cite sources."))
        port.finish("child-1", "ok")
    }

    @Test
    fun `extra delegations queue and start as slots free`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port, maxConcurrent = 1)
        val first = json(rt.execute(args("task" to "one"), "chat-A"))
        val second = json(rt.execute(args("task" to "two"), "chat-A"))
        assertEquals("running", first.getString("status"))
        assertEquals("queued", second.getString("status"))
        assertEquals(1, second.getInt("position"))

        until("first child created") { port.created.size == 1 }
        port.finish("child-1", "first done")
        until("second child created") { port.created.size == 2 }
        assertTrue(port.briefs.getValue("child-2").contains("two"))
        port.finish("child-2", "second done")
        until("both callbacks") { port.delivered.size == 2 }
        assertTrue(port.delivered[0].second.contains("first done"))
        assertTrue(port.delivered[1].second.contains("second done"))
    }

    // ── cancel / steer / status ─────────────────────────────────────────────

    @Test
    fun `cancelling a running job stops the child and posts the partial result`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child created") { port.created.isNotEmpty() }
        until("child attached") { rt.registry.get(jobId)!!.childSessionId != null }

        val reply = rt.execute(JSONObject().put("action", "cancel").put("job_id", jobId.take(8)).toString(), "chat-A")
        assertTrue(reply.ok)
        until("callback delivered") { port.delivered.isNotEmpty() }
        assertEquals(listOf("child-1"), port.cancelled.toList())
        assertEquals(SubAgentJobState.CANCELLED, rt.registry.get(jobId)!!.state)
        assertTrue(port.delivered.single().second.contains("status=cancelled"))
        assertTrue(port.delivered.single().second.contains("partial work"))
    }

    @Test
    fun `cancelling a queued job never starts a child`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port, maxConcurrent = 1)
        rt.execute(args(), "chat-A")
        val queuedId = json(rt.execute(args("task" to "later"), "chat-A")).getString("job_id")

        val reply = rt.execute(JSONObject().put("action", "cancel").put("job_id", queuedId).toString(), "chat-A")
        assertEquals("cancelled", json(reply).getString("status"))
        until("first child created") { port.created.size == 1 }
        port.finish("child-1", "done")
        until("first callback") { port.delivered.size >= 1 }
        delay(100)
        assertEquals("only the first job ever got a child", 1, port.created.size)
    }

    @Test
    fun `steering a running job reaches its child`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child attached") { rt.registry.get(jobId)?.childSessionId != null }

        val reply = rt.execute(
            JSONObject().put("action", "steer").put("job_id", jobId).put("message", "focus on pricing").toString(), "chat-A",
        )
        assertEquals("steered", json(reply).getString("status"))
        assertEquals(listOf("child-1" to "focus on pricing"), port.steered.toList())
        port.finish("child-1", "ok")
    }

    @Test
    fun `status lists only the calling conversation's jobs`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        rt.execute(args("task" to "mine"), "chat-A")
        rt.execute(args("task" to "theirs"), "chat-B")

        val mine = json(rt.execute(JSONObject().put("action", "status").toString(), "chat-A"))
        assertEquals(1, mine.getInt("count"))
        assertEquals("chat-B had its own", 1, json(rt.execute(JSONObject().put("action", "status").toString(), "chat-B")).getInt("count"))
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }

    // ── outcomes ────────────────────────────────────────────────────────────

    @Test
    fun `a timed-out run is reported as timeout and its child is stopped`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child created") { port.created.isNotEmpty() }
        port.finish("child-1", "so far", completed = false, timedOut = true)

        until("callback delivered") { port.delivered.isNotEmpty() }
        assertEquals(SubAgentJobState.TIMEOUT, rt.registry.get(jobId)!!.state)
        assertEquals(listOf("child-1"), port.cancelled.toList())
        assertTrue(port.delivered.single().second.contains("status=timeout"))
    }

    @Test
    fun `a child that cannot start is reported as failed`() = runBlocking {
        val port = FakePort().also { it.createFails = true }
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("callback delivered") { port.delivered.isNotEmpty() }
        assertEquals(SubAgentJobState.FAILED, rt.registry.get(jobId)!!.state)
        assertTrue(port.delivered.single().second.contains("Sub agent failed"))
    }

    @Test
    fun `cancelling the waiting call stops the sub agent`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val waiting = scope.launch { rt.execute(args("wait" to true), "chat-A") }
        until("child created") { port.created.isNotEmpty() }
        until("child attached") { rt.registry.jobsOf("chat-A").firstOrNull()?.childSessionId != null }
        waiting.cancel()
        until("child cancelled") { port.cancelled.isNotEmpty() }
        assertEquals(listOf("child-1"), port.cancelled.toList())
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `invalid arguments are refused with a reason`() = runBlocking {
        val rt = runtime(FakePort())
        for ((raw, code) in listOf(
            "not json" to "invalid_arguments",
            """{"action":"explode"}""" to "unknown_action",
            """{"tool_title":"t"}""" to "task_required",
            """{"action":"steer","job_id":"x"}""" to "message_required",
            """{"action":"steer","message":"m"}""" to "job_id_required",
            """{"action":"cancel"}""" to "job_id_required",
        )) {
            val reply = rt.execute(raw, "chat-A")
            assertFalse(raw, reply.ok)
            assertEquals(raw, code, json(reply).getString("error"))
        }
    }

    @Test
    fun `an unknown agent lists the available ones and starts nothing`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val reply = rt.execute(args("agent" to "ghost"), "chat-A")
        assertFalse(reply.ok)
        assertEquals("unknown_agent", json(reply).getString("error"))
        assertEquals("General Sub Agent", json(reply).getJSONArray("available").getString(0))
        assertTrue(rt.registry.jobsOf("chat-A").isEmpty())
    }

    @Test
    fun `a sub agent's own session cannot delegate`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val reply = rt.execute(args(), "child-session", callerIsSubAgent = true)
        assertFalse(reply.ok)
        assertEquals("depth_limit", json(reply).getString("error"))
        assertTrue(rt.registry.jobsOf("child-session").isEmpty())
    }

    @Test
    fun `no usable model means no job`() = runBlocking {
        val port = FakePort().also { it.modelAvailable = false }
        val rt = runtime(port)
        val reply = rt.execute(args(), "chat-A")
        assertEquals("model_unavailable", json(reply).getString("error"))
        assertTrue(rt.registry.jobsOf("chat-A").isEmpty())
    }

    @Test
    fun `a full queue is refused`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port, maxConcurrent = 1, maxQueued = 1)
        rt.execute(args(), "chat-A"); rt.execute(args(), "chat-A")
        val third = rt.execute(args(), "chat-A")
        assertEquals("queue_full", json(third).getString("error"))
        until("first child created") { port.created.isNotEmpty() }
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }

    @Test
    fun `another conversation cannot cancel or steer or see a job`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child attached") { rt.registry.get(jobId)?.childSessionId != null }

        for (action in listOf("cancel", "steer")) {
            val reply = rt.execute(
                JSONObject().put("action", action).put("job_id", jobId).put("message", "hijack").toString(), "chat-B",
            )
            assertFalse(action, reply.ok)
            assertEquals(action, "job_not_found", json(reply).getString("error"))
        }
        assertTrue("the job was left alone", port.cancelled.isEmpty() && port.steered.isEmpty())
        port.finish("child-1", "ok")
    }

    @Test
    fun `only a running job can be steered and a failed delivery is reported`() = runBlocking {
        val port = FakePort()
        val rt = runtime(port, maxConcurrent = 1)
        rt.execute(args(), "chat-A")
        val queuedId = json(rt.execute(args("task" to "later"), "chat-A")).getString("job_id")
        val steerQueued = rt.execute(
            JSONObject().put("action", "steer").put("job_id", queuedId).put("message", "m").toString(), "chat-A",
        )
        assertEquals("not_running", json(steerQueued).getString("error"))

        until("first child attached") { rt.registry.jobsOf("chat-A").first().childSessionId != null }
        port.steerResult = false
        val firstId = rt.registry.jobsOf("chat-A").first().id
        val failed = rt.execute(
            JSONObject().put("action", "steer").put("job_id", firstId).put("message", "m").toString(), "chat-A",
        )
        assertEquals("steer_failed", json(failed).getString("error"))
        assertNotNull(port.steered.firstOrNull())
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }

    // ── Interrupted runs: a restart loses the coroutines, the stored jobs say what was lost ──

    private class MemStore : SubAgentJobStore {
        var saved: List<SubAgentJob> = emptyList()
        override fun load() = saved
        override fun save(jobs: List<SubAgentJob>) { saved = jobs }
    }

    /** "Restart the app": a new registry and runtime over the same stored jobs. */
    private fun restarted(store: MemStore, port: FakePort) =
        SubAgentRuntime(port, SubAgentJobRegistry(store = store), scope)

    private suspend fun startAndLose(store: MemStore, port: FakePort): String {
        val rt = SubAgentRuntime(port, SubAgentJobRegistry(store = store), scope)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child attached") { rt.registry.get(jobId)?.childSessionId != null }
        return jobId
    }

    @Test
    fun `a run lost to a restart shows up as interrupted`() = runBlocking {
        val store = MemStore()
        val jobId = startAndLose(store, FakePort())
        val rt = restarted(store, FakePort())
        assertEquals(SubAgentJobState.INTERRUPTED, rt.registry.get(jobId)!!.state)
        val status = json(rt.execute(JSONObject().put("action", "status").toString(), "chat-A"))
        assertEquals("interrupted", status.getJSONArray("jobs").getJSONObject(0).getString("status"))
    }

    @Test
    fun `resuming continues in the same child with the resume notice and reports back`() = runBlocking {
        val store = MemStore()
        val jobId = startAndLose(store, FakePort())
        val port = FakePort().also { it.gates["child-1"] = CompletableDeferred() }
        val rt = restarted(store, port)

        val reply = rt.execute(JSONObject().put("action", "resume").put("job_id", jobId.take(8)).toString(), "chat-A")
        assertTrue(reply.text, reply.ok)
        until("resumed run started") { port.messages.isNotEmpty() }
        assertEquals("child-1", port.messages.single().first)
        assertTrue(port.messages.single().second.contains("interrupted and has been resumed"))
        assertTrue("no new child", port.created.isEmpty())

        port.finish("child-1", "carried on")
        until("callback delivered") { port.delivered.isNotEmpty() }
        assertTrue(port.delivered.single().second.contains("carried on"))
        assertEquals(SubAgentJobState.DONE, rt.registry.get(jobId)!!.state)
        assertTrue(rt.registry.get(jobId)!!.resumed)
    }

    @Test
    fun `omitting the job id resumes every interrupted run of this conversation only`() = runBlocking {
        val store = MemStore()
        val first = FakePort()
        val rtOld = SubAgentRuntime(first, SubAgentJobRegistry(store = store), scope)
        val a = json(rtOld.execute(args(), "chat-A")).getString("job_id")
        val b = json(rtOld.execute(args(), "chat-B")).getString("job_id")
        until("both attached") { rtOld.registry.get(a)?.childSessionId != null && rtOld.registry.get(b)?.childSessionId != null }

        val port = FakePort().also { p -> first.created.forEach { p.gates[it] = CompletableDeferred() } }
        val rt = restarted(store, port)
        rt.execute(JSONObject().put("action", "resume").toString(), "chat-A")
        until("one run resumed") { port.messages.size == 1 }
        delay(100)
        assertEquals("chat-B's run was left interrupted", SubAgentJobState.INTERRUPTED, rt.registry.get(b)!!.state)
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }

    @Test
    fun `a job that never got a child starts over with its brief when resumed`() = runBlocking {
        val store = MemStore()
        val port0 = FakePort().also { it.createFails = false }
        val rtOld = SubAgentRuntime(port0, SubAgentJobRegistry(maxConcurrent = 0, store = store), scope)
        val jobId = json(rtOld.execute(args("task" to "survey the repo"), "chat-A")).getString("job_id")
        assertEquals("queued, never started", SubAgentJobState.QUEUED, rtOld.registry.get(jobId)!!.state)

        val port = FakePort()
        val rt = restarted(store, port)
        assertEquals(SubAgentJobState.INTERRUPTED, rt.registry.get(jobId)!!.state)
        rt.execute(JSONObject().put("action", "resume").put("job_id", jobId).toString(), "chat-A")
        until("fresh child created") { port.created.isNotEmpty() }
        until("brief sent") { port.messages.isNotEmpty() }
        assertTrue(port.messages.single().second.contains("survey the repo"))
        port.finish(port.created.single(), "done")
    }

    @Test
    fun `resuming is refused for a job that is not interrupted, unknown, or another conversation's`() = runBlocking {
        val store = MemStore()
        val port = FakePort()
        val rt = SubAgentRuntime(port, SubAgentJobRegistry(store = store), scope)
        val live = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("attached") { rt.registry.get(live)?.childSessionId != null }

        suspend fun resume(job: String, chat: String) =
            json(rt.execute(JSONObject().put("action", "resume").put("job_id", job).toString(), chat))
        assertEquals("not_interrupted", resume(live, "chat-A").getString("error"))
        assertEquals("job_not_found", resume("ghost", "chat-A").getString("error"))
        assertEquals("job_not_found", resume(live, "chat-B").getString("error"))
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }

    // ── Time budget: a wrap-up turn instead of an empty hand-over ──────────

    @Test
    fun `a run that ran out of time hands over its wrap-up message`() = runBlocking {
        val port = FakePort().also { it.wrapUp = "Done: A and B. Left: C." }
        val rt = runtime(port)
        val jobId = json(rt.execute(args(), "chat-A")).getString("job_id")
        until("child created") { port.created.isNotEmpty() }
        port.finish("child-1", "so far", completed = false, timedOut = true)

        until("callback delivered") { port.delivered.isNotEmpty() }
        assertEquals(listOf("child-1"), port.wrapUps.toList())
        assertEquals(SubAgentJobState.TIMEOUT, rt.registry.get(jobId)!!.state)
        assertTrue(port.delivered.single().second.contains("Done: A and B. Left: C."))
    }

    @Test
    fun `without a wrap-up the partial output is kept`() = runBlocking {
        val port = FakePort().also { it.wrapUp = null }
        val rt = runtime(port)
        rt.execute(args(), "chat-A")
        until("child created") { port.created.isNotEmpty() }
        port.finish("child-1", "partial notes", completed = false, timedOut = true)
        until("callback delivered") { port.delivered.isNotEmpty() }
        assertTrue(port.delivered.single().second.contains("partial notes"))
    }

    // ── Progress reports ───────────────────────────────────────────────────

    @Test
    fun `a background run that asked for progress reports posts them only when something changed`() = runBlocking {
        val port = FakePort().also { it.progress = ChildProgress("shell_execute", "reading files") }
        val rt = runtime(port, progressMs = 30)
        rt.execute(args("progress_report" to "frequent"), "chat-A")
        until("first report") { port.delivered.isNotEmpty() }
        assertTrue(port.delivered.first().second.startsWith("[Background task progress"))
        assertTrue(port.delivered.first().second.contains("Current tool: shell_execute"))
        assertTrue(port.delivered.first().second.contains("reading files"))

        delay(150)
        assertEquals("unchanged progress is not repeated", 1, port.delivered.size)
        port.progress = ChildProgress("file_read", "now reading the tests")
        until("second report") { port.delivered.size == 2 }
        port.finish("child-1", "all done")
        until("final callback") { port.delivered.any { it.second.startsWith("[Background task finished") } }
        val before = port.delivered.size
        delay(150)
        assertEquals("no reports after the run ends", before, port.delivered.size)
    }

    @Test
    fun `no progress reports by default, for a blocking call, or when the level is unknown`() = runBlocking {
        val port = FakePort().also { it.progress = ChildProgress("t", "text") }
        val rt = runtime(port, maxConcurrent = 5, progressMs = 30)
        rt.execute(args(), "chat-A")                                              // default none
        rt.execute(args("progress_report" to "chatty"), "chat-A")                // unknown -> none
        scope.launch { rt.execute(args("wait" to true, "progress_report" to "frequent"), "chat-A") } // wait ignores it
        until("three children") { port.created.size == 3 }
        delay(200)
        assertTrue("no progress message was posted", port.delivered.none { it.second.startsWith("[Background task progress") })
        port.gates.values.forEach { it.complete(ChildOutcome(true, "x", false)) }
    }
}
