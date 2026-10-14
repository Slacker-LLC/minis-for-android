package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.*
import com.openminis.app.tools.runtime.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Agent requests, Room receipts and files; only model transport is scripted. */
@RunWith(AndroidJUnit4::class)
class AgentOutcomeVerificationE2ETest {
    @Test fun verifyOneExecutionWithoutCompletingOrResumingTask(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val marker = "m2-verification-${UUID.randomUUID()}"
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("marker", marker).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "verification-scripted"
            }
        }
        suspend fun session(status: String = "waiting"): String {
            val sid = app.chatRepository.createSession("scripted-model", title = "M2 independent verification", memoryEnabled = false).id
            sessions.add(sid)
            AgentStateStore.goalSet(sid, "$marker 核验后保留任务控制状态", app, 3)
            val task = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            assertTrue(GoalTools.execute("update_goal", JSONObject().put("task_id", task.id).put("revision", task.revision)
                .put("stop_generation", task.stopGeneration).put("status", status).put("progress", "仍有后续工作，不等于完成")
                .toString(), sid, app).success)
            return sid
        }
        fun result(history: List<com.openminis.app.data.model.LLMMessage>, id: String) = history.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().single { it.id == id }
        suspend fun intent(sid: String, body: String, run: String, name: String = "file_write", args: JSONObject? = null): String {
            val task = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            return checkNotNull(ToolCheckpointStore.recordIntent(app, sid, "reused-call", name,
                (args ?: JSONObject().put("path", "/workspace/proof.txt").put("content", body)).toString(),
                run, task.id, task.revision, task.stopGeneration))
        }
        suspend fun verify(sid: String, execution: String, run: String, expectedStatus: String, rejected: String? = null,
            changeArgs: (JSONObject) -> Unit = {}): JSONObject? {
            var proof: JSONObject? = null
            var providerFailure: Throwable? = null
            val getCall = "verify-get-${UUID.randomUUID()}"
            val verifyCall = "verify-exact-${UUID.randomUUID()}"
            val task = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            val args = JSONObject().put("task_id", task.id).put("revision", task.revision).put("stop_generation", task.stopGeneration)
                .put("outcome", JSONObject().put("execution_id", execution).put("run_id", run)).also(changeArgs)
            val provider = ScriptedProvider { call, history, tools -> try { when (call) {
                1 -> {
                    val schema = tools.single { it.name == "verify_outcome" }
                    assertTrue(schema.required.containsAll(listOf("task_id", "revision", "stop_generation", "outcome")))
                    assertFalse(tools.single { it.name == "update_goal" }.parameters.containsKey("resolved_call_ids"))
                    toolCall(getCall, "get_goal")
                }
                2 -> {
                    val unknown = JSONObject(result(history, getCall).content).getJSONArray("unknown_outcomes")
                    assertFalse((0 until unknown.length()).any { unknown.getJSONObject(it).optString("call_id") == getCall })
                    toolCall(verifyCall, "verify_outcome", args)
                }
                else -> {
                    val outcome = result(history, verifyCall)
                    if (rejected != null) {
                        assertTrue(outcome.content, outcome.isError); assertTrue(outcome.content, outcome.content.contains(rejected))
                    } else {
                        assertFalse(outcome.content, outcome.isError)
                        proof = JSONObject(outcome.content).getJSONObject("verified_outcome")
                        assertEquals(execution, proof!!.getString("execution_id")); assertEquals(run, proof!!.getString("run_id"))
                    }
                    text("仅核验动作，任务尚未完成。")
                }
            } } catch (error: Throwable) {
                providerFailure = error
                text("受控验收断言失败，交回测试线程记录。")
            } }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "核验一个未知动作，保留等待或用户决定状态", wait = true, timeoutMs = 25_000).completed)
            providerFailure?.let { throw it }
            val after = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            assertEquals(expectedStatus, after.status); assertNull(after.completionEvidence)
            assertEquals(task.autoRunsUsed, after.autoRunsUsed); assertEquals(task.stopGeneration, after.stopGeneration)
            return proof
        }
        try {
            for (status in listOf("waiting", "needs_user")) {
                val sid = session(status)
                val body = "$marker $status\n" + "完整长写入🙂".repeat(1000)
                WorkspaceFileClient.writeBytes(sid, "/workspace/proof.txt", body.toByteArray())
                val exact = intent(sid, body, "original-run")
                val other = intent(sid, "different body", "second-run")
                val originalSidecar = File(app.filesDir, "checkpoints/$sid.jsonl").readLines()
                    .single { JSONObject(it).getString("intentId") == exact }
                val pending = ToolCheckpointStore.drainPending(app, sid).single { it.intentId == exact }
                assertTrue(pending.argsJson.contains("file_write_summary_version")); assertFalse(pending.argsJson.contains(body))
                val expected = status.uppercase()
                val proof = checkNotNull(verify(sid, exact, "original-run", expected))
                assertEquals(listOf(other), ToolCheckpointStore.drainPending(app, sid).map { it.intentId })
                assertEquals(body, WorkspaceFileClient.readAll(sid, "/workspace/proof.txt").decodeToString())
                val receiptsBefore = app.chatRepository.loadMessages(sid).count { it.partsJson.contains("outcomeVerification") }
                assertEquals(1, receiptsBefore)
                passed("$status: exact long write verified independently, duplicate call ID kept and task control preserved")

                // Crash between Room commit and ACK: restore only the same sidecar identity.
                File(app.filesDir, "checkpoints/$sid.jsonl").appendText(originalSidecar + "\n")
                WorkspaceFileClient.writeBytes(sid, "/workspace/proof.txt", "$marker changed after verified receipt".toByteArray())
                AgentSessionStore.closeAndAwait(sid)
                withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
                val retried = checkNotNull(verify(sid, exact, "original-run", expected))
                assertEquals(proof.toString(), retried.toString())
                assertEquals(receiptsBefore, app.chatRepository.loadMessages(sid).count { it.partsJson.contains("outcomeVerification") })
                assertEquals(listOf(other), ToolCheckpointStore.drainPending(app, sid).map { it.intentId })
                assertTrue(WorkspaceFileClient.readAll(sid, "/workspace/proof.txt").decodeToString().contains("changed after verified receipt"))
                passed("$status: owner rebuild consumes committed proof before ACK without replay or duplicate receipt")
                verify(sid, other, "second-run", expected, "unknown_write_does_not_match_verified_file")
                assertEquals(listOf(other), ToolCheckpointStore.drainPending(app, sid).map { it.intentId })
                passed("$status: mismatched actual file keeps the exact unknown execution pending")
                File(app.filesDir, "agent-outcome-verification-delivery.json").writeText(JSONObject()
                    .put("marker", marker).put("verified", proof).put("currentFile", WorkspaceFileClient.fingerprint(sid, "/workspace/proof.txt"))
                    .put("retainedExecution", other).put("taskStatus", expected).put("replayedWrites", 0).toString(2))
            }
            val sid = session()
            WorkspaceFileClient.writeBytes(sid, "/workspace/proof.txt", "real file".toByteArray())
            val exact = intent(sid, "real file", "bound-run")
            verify(sid, exact, "foreign-run", "WAITING", "unknown_execution_identity_mismatch")
            verify(sid, exact, "bound-run", "WAITING", "task_changed_or_missing_identity") { it.put("revision", -1) }
            verify(sid, exact, "bound-run", "WAITING", "verify_outcome_is_independent_of_goal_settlement") { it.put("status", "completed") }
            assertTrue(ToolCheckpointStore.drainPending(app, sid).any { it.intentId == exact })
            passed("wrong run, stale task revision and coupled completion rejected without ACK")
            val shell = intent(sid, "", "shell-run", "shell_execute", JSONObject().put("command", "echo unknown"))
            verify(sid, shell, "shell-run", "WAITING", "unknown_job_needs_user_observation")
            val append = intent(sid, "real file", "append-run", args = JSONObject().put("path", "/workspace/proof.txt")
                .put("content", "real file").put("append", true))
            verify(sid, append, "append-run", "WAITING", "file_write_intent_is_incomplete")
            passed("unknown shell and append write require domain verification and retain their checkpoints")
            val foreign = session()
            verify(foreign, exact, "bound-run", "WAITING", "unknown_execution_not_found")
            assertTrue(ToolCheckpointStore.drainPending(app, sid).any { it.intentId == exact })
            val task = checkNotNull(app.botTaskRepository.sessionGoal(sid))
            val unowned = ToolExecutor.execute("verify_outcome", JSONObject().put("tool_title", "拒绝伪造核验")
                .put("task_id", task.id).put("revision", task.revision).put("stop_generation", task.stopGeneration)
                .put("outcome", JSONObject().put("execution_id", exact).put("run_id", "bound-run")).toString(), sid, app)
            assertFalse(unowned.success); assertTrue(unowned.output.contains("verification_requires_actual_execution"))
            passed("another session and a direct unowned tool call cannot settle the original action")
            val paused = session("paused")
            WorkspaceFileClient.writeBytes(paused, "/workspace/proof.txt", "real file".toByteArray())
            val pausedIntent = intent(paused, "real file", "paused-run")
            verify(paused, pausedIntent, "paused-run", "PAUSED", "task_changed_or_stopped")
            assertTrue(ToolCheckpointStore.drainPending(app, paused).any { it.intentId == pausedIntent })
            passed("paused owner request cannot verify or resume a stopped task")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString()); throw error
        } finally {
            sessions.forEach { sid ->
                AgentSessionStore.closeAndAwait(sid)
                withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
                ToolCheckpointStore.clearSession(app, sid); SessionEventHub.clear(sid); app.chatRepository.deleteSession(sid)
            }
            File(app.filesDir, "agent-outcome-verification-e2e.json").writeText(report.toString(2))
        }
    }
}
