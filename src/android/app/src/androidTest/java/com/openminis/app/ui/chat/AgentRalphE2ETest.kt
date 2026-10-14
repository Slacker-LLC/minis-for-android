package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.debug.handle
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.*
import com.openminis.app.tools.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Model transport and held tool cleanup are controlled; Ralph/session/Room/files/RPC are real. */
@RunWith(AndroidJUnit4::class)
class AgentRalphE2ETest {
    @Test
    fun settleOwnedChildrenAndExposeRealSettings(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val configuration = app.providerRepository.config.value
        val prefs = app.getSharedPreferences("minis_subagent_prefs", android.content.Context.MODE_PRIVATE)
        val savedTimeout = prefs.all["timeout_minutes"]
        val savedDepth = prefs.all["max_depth"]
        val original = checkNotNull(ToolRegistry.handler("todo_write"))
        val sessions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = mutableListOf<Job>()
        val permits = mutableListOf<CompletableDeferred<Unit>>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject = JSONObject(DebugRPCHandler(app).handle(
            JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()))
        suspend fun parent(): String = app.chatRepository.createSession("scripted-model", title = "Ralph E2E parent", memoryEnabled = false).id.also(sessions::add)
        suspend fun prepare(id: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, id) }
            withTimeout(10_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "ralph-scripted-entry" }
        }
        fun observe(tag: String, providers: List<ScriptedProvider>): Deferred<List<String>> = async {
            val found = mutableListOf<String>()
            for (provider in providers) {
                val id = withTimeout(10_000) {
                    var match: String? = null
                    while (match == null) {
                        match = app.chatRepository.dao.listSessions().firstOrNull {
                            it.source == "ralph" && it.title?.contains(tag) == true && it.id !in found
                        }?.id
                        if (match == null) delay(10)
                    }
                    match
                }
                sessions.add(id); found.add(id); prepare(id, provider)
            }
            found
        }.also(workers::add)
        fun args(tag: String, rounds: Int = 1) = JSONObject().put("tool_title", tag).put("objective", "核验 $tag 的实际工作流").put("maxRounds", rounds)
        fun result(status: String = "complete", summary: String = "已核验", evidence: String = "", next: String = "") =
            JSONObject().put("status", status).put("summary", summary).put("evidence", evidence).put("nextSteps", next).put("blockedReason", "").toString()
        fun statePath(history: List<com.openminis.app.data.model.LLMMessage>): String =
            Regex("/var/minis/shared/ralph/[a-f0-9-]+").find(history.joinToString("\n") { it.content })?.value.orEmpty()
        fun holding(entered: CompletableDeferred<String>, cleaning: CompletableDeferred<Unit>, permit: CompletableDeferred<Unit>) {
            permits.add(permit)
            ToolRegistry.register(object : ToolHandler {
                override val definition = original.definition.copy(timeoutMs = null)
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String): ToolExecutionResult {
                    WorkspaceFileClient.writeBytes(sessionId, "/workspace/ralph-effect.txt", "one real child effect\n".toByteArray())
                    entered.complete(sessionId)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); permit.await() }
                    }
                }
            })
        }
        try {
            // Prevent accidental real Provider traffic while observing newly created production children.
            app.providerRepository._config.value = ProviderConfig()
            prefs.edit().putLong("timeout_minutes", 7L).putInt("max_depth", 4).commit()
            val old = rpc("agent.settings.get").getJSONObject("result")
            assertEquals(7L, old.getLong("timeoutMinutes")); assertEquals("ralph_round", old.getString("scope")); assertFalse(old.has("maxDepth"))
            assertEquals(420_000L, RalphLimits.timeoutMs(app))
            passed("installed timeout preference is reused while the inert depth option is absent")
            val set = rpc("agent.settings.set", JSONObject().put("timeoutMinutes", 1)).getJSONObject("result")
            assertTrue(set.getBoolean("ok")); assertEquals(60_000L, RalphLimits.timeoutMs(app))
            assertTrue(app.getString(R.string.settings_ralph_limit).contains("Ralph"))
            assertTrue(app.getString(R.string.settings_ralph_limit_subtitle, 1).contains("1"))
            passed("RPC and native settings resources expose the actual stored Ralph per-round budget")
            for (invalid in listOf(JSONObject(), JSONObject().put("timeoutMinutes", 0), JSONObject().put("timeoutMinutes", 31),
                JSONObject().put("timeoutMinutes", "1"), JSONObject().put("timeoutMinutes", 1.5), JSONObject().put("maxDepth", 3).put("timeoutMinutes", 10))) {
                assertEquals(-32602, rpc("agent.settings.set", invalid).getJSONObject("error").getInt("code"))
                assertEquals(60_000L, RalphLimits.timeoutMs(app))
            }
            passed("invalid budgets and obsolete depth writes fail before modifying the real preference")
            val discovery = rpc("rpc.discover").getJSONObject("result").toString()
            assertTrue(discovery.contains("Ralph per-round")); assertFalse(discovery.contains("Delegation depth cap"))
            passed("actual RPC discovery describes the same settings as the execution path")

            val entered = CompletableDeferred<String>(); val cleaning = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            holding(entered, cleaning, release)
            val childWatch = observe("Held-parent", listOf(ScriptedProvider { _, _, _ -> toolCall("ralph-held", "todo_write", JSONObject().put("todos", JSONArray())) }))
            val first = async { ToolExecutor.execute("ralph", args("Held-parent").toString(), parent(), app) }.also(workers::add)
            val heldChild = withTimeout(10_000) { entered.await() }; assertEquals(listOf(heldChild), childWatch.await())
            val count = app.chatRepository.dao.listSessions().size
            val nested = ToolExecutor.execute("ralph", args("Nested-rejected").toString(), heldChild, app)
            assertFalse(nested.success); assertEquals(ToolFailureKind.PERMISSION_DENIED, nested.failureKind); assertEquals(false, nested.executionStarted)
            assertEquals(count, app.chatRepository.dao.listSessions().size)
            passed("real Ralph child source rejects nesting before creating another child")

            var artifactPath = ""
            val independentProvider = ScriptedProvider { call, history, _ ->
                if (call == 1) {
                    artifactPath = statePath(history) + "/delivered.txt"
                    toolCall("ralph-delivery", "file_write", JSONObject().put("path", artifactPath).put("content", "Ralph delivered\n"))
                } else text(result(evidence = artifactPath))
            }
            val independentWatch = observe("Independent-parent", listOf(independentProvider))
            val independent = ToolExecutor.execute("ralph", args("Independent-parent").toString(), parent(), app)
            val deliveredChild = independentWatch.await().single()
            assertTrue(independent.output, independent.success); assertFalse(first.isCompleted)
            val artifact = WorkspaceFileClient.readAll(deliveredChild, artifactPath)
            assertEquals("Ralph delivered\n", artifact.decodeToString())
            File(app.filesDir, "agent-ralph-delivery.txt").writeBytes(artifact)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(artifact).joinToString("") { "%02x".format(it) })
            report.put("artifactPath", artifactPath).put("artifactSourceSession", deliveredChild)
            passed("unrelated parent completes a real file delivery while another Ralph child is still running")
            first.cancel(); withTimeout(10_000) { cleaning.await() }
            assertFalse(first.isCompleted)
            release.complete(Unit); withTimeout(10_000) { first.join() }
            assertFalse(AgentRunner.isInFlight(app, heldChild)); assertTrue(AgentRunner.awaitStreamExit(app, heldChild))
            assertEquals("one real child effect\n", WorkspaceFileClient.readAll(heldChild, "/workspace/ralph-effect.txt").decodeToString())
            assertEquals("ralph-held", ToolCheckpointStore.drainPending(app, heldChild).single().callId)
            passed("parent cancellation waits for actual child tool cleanup and preserves its unknown effect")
            ToolRegistry.register(original)

            var firstState = ""; var nextSawHandoff = false
            val roundsWatch = observe("Fresh-rounds", listOf(
                ScriptedProvider { call, history, _ -> if (call == 1) {
                    firstState = statePath(history)
                    toolCall("ralph-state", "file_write", JSONObject().put("path", "$firstState/notes.txt").put("content", "handoff on disk\n"))
                } else text(result("continue", "第一轮现场已保存", "$firstState/notes.txt", "读取上一轮现场并交付")) },
                ScriptedProvider { call, history, _ -> if (call == 1) {
                    nextSawHandoff = history.any { it.content.contains("第一轮现场已保存") && it.content.contains(firstState) }
                    toolCall("ralph-read-state", "file_read", JSONObject().put("path", "$firstState/notes.txt"))
                } else text(result(evidence = "$firstState/notes.txt")) }
            ))
            val fresh = ToolExecutor.execute("ralph", args("Fresh-rounds", 2).toString(), parent(), app)
            val roundIds = roundsWatch.await()
            assertTrue(fresh.output, fresh.success); assertEquals(2, roundIds.toSet().size); assertTrue(nextSawHandoff)
            for (id in roundIds) assertFalse(AgentRunner.isInFlight(app, id))
            assertTrue(app.chatRepository.loadMessages(roundIds[1]).any { it.partsJson.contains("handoff on disk") })
            passed("fresh sessions share actual run files and a bounded handoff without inheriting old conversation")

            for ((tag, answer) in listOf("Missing-next" to result("continue"), "Missing-reason" to result("blocked"),
                "Oversized-report" to result(evidence = "x".repeat(9_000)))) {
                val watcher = observe(tag, listOf(ScriptedProvider { _, _, _ -> text(answer) }))
                val invalid = ToolExecutor.execute("ralph", args(tag, 2).toString(), parent(), app)
                val id = watcher.await().single()
                assertFalse(invalid.success); assertTrue(invalid.output.contains("invalid report")); assertFalse(AgentRunner.isInFlight(app, id))
            }
            passed("missing continuation/block reasons and oversized evidence reject the report without silently truncating it")

            ToolRegistry.register(object : ToolHandler {
                override val definition = original.definition
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String): ToolExecutionResult {
                    WorkspaceFileClient.writeBytes(sessionId, "/workspace/unknown.txt", "accepted child effect\n".toByteArray())
                    return ToolExecutionResult("accepted but not observed", true, failureKind = ToolFailureKind.OBSERVATION_FAILED)
                }
            })
            val unknownWatch = observe("Unknown-child", listOf(ScriptedProvider { call, _, _ ->
                if (call == 1) toolCall("ralph-unknown", "todo_write", JSONObject().put("todos", JSONArray())) else text(result()) }))
            val unknown = ToolExecutor.execute("ralph", args("Unknown-child").toString(), parent(), app)
            val unknownChild = unknownWatch.await().single()
            assertFalse(unknown.success); assertEquals(ToolFailureKind.OUTCOME_UNKNOWN, unknown.failureKind)
            assertEquals("ralph-unknown", ToolCheckpointStore.drainPending(app, unknownChild).single().callId)
            passed("a completed child with an unresolved effect cannot turn a complete report into Ralph success")

            val timeoutEntered = CompletableDeferred<String>(); val timeoutCleaning = CompletableDeferred<Unit>(); val timeoutRelease = CompletableDeferred<Unit>()
            holding(timeoutEntered, timeoutCleaning, timeoutRelease)
            val timeoutWatch = observe("Actual-minute-budget", listOf(ScriptedProvider { _, _, _ -> toolCall("ralph-timeout", "todo_write", JSONObject().put("todos", JSONArray())) }))
            val start = android.os.SystemClock.elapsedRealtime()
            val deadline = async { ToolExecutor.execute("ralph", args("Actual-minute-budget").toString(), parent(), app) }.also(workers::add)
            val timeoutChild = withTimeout(10_000) { timeoutEntered.await() }; timeoutWatch.await()
            withTimeout(80_000) { timeoutCleaning.await() }
            assertFalse(deadline.isCompleted)
            timeoutRelease.complete(Unit)
            val timed = withTimeout(10_000) { deadline.await() }
            assertFalse(timed.success); assertTrue(timed.timedOut); assertEquals(ToolFailureKind.TOOL_TIMEOUT, timed.failureKind)
            assertTrue(timed.output.contains(timeoutChild)); assertFalse(timed.output.contains("invalid report"))
            assertFalse(AgentRunner.isInFlight(app, timeoutChild)); assertTrue(AgentRunner.awaitStreamExit(app, timeoutChild))
            assertEquals("ralph-timeout", ToolCheckpointStore.drainPending(app, timeoutChild).single().callId)
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            assertTrue("real configured minute must elapse: $elapsed", elapsed >= 60_000L)
            report.put("actualRoundTimeoutMs", elapsed)
            passed("the real stored one-minute limit cancels its exact child and awaits cleanup before typed timeout delivery")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            withContext(NonCancellable) {
                permits.forEach { it.complete(Unit) }; workers.forEach { it.cancel(); it.join() }
                ToolRegistry.register(original)
                sessions.toList().forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
                app.providerRepository._config.value = configuration
                val editor = prefs.edit()
                if (savedTimeout == null) editor.remove("timeout_minutes") else editor.putLong("timeout_minutes", (savedTimeout as Number).toLong())
                if (savedDepth == null) editor.remove("max_depth") else editor.putInt("max_depth", (savedDepth as Number).toInt())
                editor.commit()
                File(app.filesDir, "agent-ralph-e2e.json").writeText(report.toString(2))
            }
        }
    }
}
