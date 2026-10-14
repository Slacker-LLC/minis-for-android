package com.openminis.app.ui.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.agent.subagents.*
import com.openminis.app.data.AssistantHomePrefs
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.TaskArtifacts
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.files.DocumentEvidence
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** App-owned file delivery, real parent/child requests and persisted voice routing. */
@RunWith(AndroidJUnit4::class)
class AgentIntegratedDeliveryE2ETest {
    @Test fun sharedArtifactsChildOwnershipAndVoiceRouting(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val ids = mutableListOf<String>(); val tasks = mutableListOf<String>()
        val sharedPaths = mutableListOf<String>()
        val prefs = app.getSharedPreferences("minis_assistant_home_prefs", Context.MODE_PRIVATE)
        val savedPrefs = prefs.all.toMap()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun until(check: suspend () -> Boolean) = withTimeout(20_000) { while (!check()) delay(20) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", memoryEnabled = false).id.also(ids::add)
        suspend fun prepare(sid: String, provider: LLMProvider): AgentSession {
            val core = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, sid) }
            withTimeout(20_000) { core.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { core.currentProvider = provider; core.currentModel = provider.model; core._activeEntryId.value = "integrated-scripted" }
            return core
        }
        try {
            val source = session(); val parent = session(); val child = session()
            val secret = "private-goal-${UUID.randomUUID()}"
            var identity: JSONObject? = null
            val writer = ScriptedProvider { call, history, _ ->
                when (call) {
                    1 -> toolCall("integrated-goal", "create_goal", JSONObject().put("goal", secret).put("max_goal_rounds", 3))
                    2 -> {
                        identity = JSONObject(history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "integrated-goal" }.content)
                        toolCall("integrated-write", "file_write", JSONObject().put("path", "/workspace/delivery.txt").put("content", "integrated delivery\n"))
                    }
                    3 -> toolCall("integrated-complete", "update_goal", JSONObject().put("status", "completed")
                        .put("task_id", identity!!.getString("task_id")).put("revision", identity!!.getInt("revision"))
                        .put("stop_generation", identity!!.getInt("stop_generation")).put("progress", "Verified actual delivery file")
                        .put("evidence", JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/delivery.txt"))))
                    else -> text("Completed the verified file")
                }
            }
            prepare(source, writer)
            assertTrue(AgentRunner.prompt(app, source, "write and complete", wait = true, timeoutMs = 20_000).completed)
            val taskId = identity!!.getString("task_id"); tasks += taskId
            val ref = TaskArtifacts.page(app, source).references.single { it.taskId == taskId }
            TaskArtifacts.share(app, ref)
            val shared = TaskArtifacts.page(app, source).references.single { it.taskId == taskId && it.path.startsWith("/shared/") }
            sharedPaths += shared.path
            assertEquals(TaskArtifacts.State.VERIFIED, TaskArtifacts.verify(app, shared).state)
            assertEquals("integrated delivery\n", WorkspaceFileClient.readAll(null, shared.path).decodeToString())
            passed("actual model/file/Goal -> explicit shared copy -> durable task delivery stays verifiable")
            WorkspaceFileClient.writeBytes(source, ref.path, "changed\n".toByteArray())
            val proofBefore = app.agentTaskRepository.get(taskId)!!.completionEvidence
            assertTrue(runCatching { TaskArtifacts.share(app, ref) }.isFailure)
            assertEquals(proofBefore, app.agentTaskRepository.get(taskId)!!.completionEvidence)
            passed("changed source cannot create a new shared delivery or rewrite historical evidence")
            app.chatRepository.deleteSession(source)
            assertEquals(TaskArtifacts.State.VERIFIED, TaskArtifacts.verify(app, shared).state)
            assertEquals(TaskArtifacts.State.UNAVAILABLE, TaskArtifacts.verify(app, ref).state)
            File(app.filesDir, "agent-integrated-delivery.txt").writeBytes(WorkspaceFileClient.readAll(null, shared.path))
            passed("recorded global copy survives original session deletion and remains independently readable")
            assertTrue(runCatching { DocumentEvidence.fingerprint(app, "file:///etc/passwd") }.isFailure)
            assertTrue(runCatching { DocumentEvidence.fingerprint(app, "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fminis-ungranted.txt") }.isFailure)
            passed("non-SAF host URI and ungranted document fail closed without publishing evidence")

            AgentStateStore.goalSet(parent, "parent child delivery", app, 4)
            val root = app.agentTaskRepository.sessionGoal(parent)!!; tasks += root.id
            val job = SubAgentJob(UUID.randomUUID().toString(), parent, "worker", "child delivery", false, 1,
                SubAgentJobState.RUNNING, childSessionId = child, createdAtMs = System.currentTimeMillis(),
                parentTaskId = root.id, parentTaskRevision = root.revision, parentStopGeneration = root.stopGeneration,
                parentRunId = "original-parent-run", parentExecutionId = "original-execution")
            val port = AppSubAgentPort(app)
            val childProvider = ScriptedProvider { _, _, _ -> text("exact child result") }
            val childCore = prepare(child, childProvider)
            val first = withContext(SubAgentRuntime.WorkContext(job)) { port.runChild(child, "child brief", null, 15_000) }
            assertTrue(first.completed); assertFalse(first.outcomeUnknown)
            val retry = withContext(SubAgentRuntime.WorkContext(job)) { port.runChild(child, "child brief", null, 15_000) }
            assertTrue(retry.completed); assertEquals(1, childProvider.calls)
            val childRequest = app.chatRepository.dao.loadMessages(child).single { it.requestState == "COMPLETED" }
            report.put("childRequestId", childRequest.id).put("childRunId", childRequest.requestRunId)
            passed("AppSubAgentPort persists stable child identity and retry reads its exact result without a new run")
            val gate = Gate(childProvider)
            prepare(child, gate)
            val foreign = withContext(Dispatchers.Main) { AgentRunner.submit(childCore, "later human request") }
            until { gate.waiting }
            assertFalse(withContext(SubAgentRuntime.WorkContext(job)) { port.cancelChild(child) })
            assertFalse(foreign.result.isCompleted)
            gate.release.complete(Unit)
            assertEquals(AgentTurnOutcome.Completed, foreign.result.await())
            passed("old child cancellation cannot cancel a later human request in the same child session")
            val parentProvider = ScriptedProvider { call, _, _ ->
                if (call == 1) toolCall("parent-review-wait", "update_goal", JSONObject().put("status", "waiting")
                    .put("task_id", root.id).put("revision", root.revision).put("stop_generation", root.stopGeneration)
                    .put("progress", "Reviewed this child result; waiting for the user's next decision"))
                else text("reviewed child result")
            }
            prepare(parent, parentProvider)
            withContext(SubAgentRuntime.WorkContext(job)) { port.deliverToParent(parent, "terminal child result") }
            until { app.chatRepository.dao.loadMessages(parent).any { it.requestState == "COMPLETED" } }
            withContext(SubAgentRuntime.WorkContext(job)) { port.deliverToParent(parent, "terminal child result") }
            assertEquals(2, parentProvider.calls)
            withContext(SubAgentRuntime.WorkContext(job, progress = true)) { port.deliverToParent(parent, "progress only") }
            assertEquals(2, parentProvider.calls)
            passed("parent terminal delivery is idempotent and progress creates only a persisted card")
            AgentStateStore.goalSetActive(parent, false, app)
            val callsBeforeStop = parentProvider.calls
            withContext(SubAgentRuntime.WorkContext(job)) { port.deliverToParent(parent, "late after stop") }
            assertEquals(callsBeforeStop, parentProvider.calls)
            assertTrue(app.chatRepository.dao.loadMessages(parent).any { it.role == "assistant" && it.partsJson.contains("late after stop") })
            assertNull(withContext(SubAgentRuntime.WorkContext(job)) { port.wrapUpChild(child, 1000) })
            assertFalse(withContext(SubAgentRuntime.WorkContext(job)) { port.runChild(child, "stale work", null, 1000) }.completed)
            passed("paused or changed parent receives a retained late card and permits no new child or wrap-up model request")

            prefs.edit().remove("voice_session").commit()
            assertEquals(null, AssistantHomePrefs.resolveVoiceSession(app))
            AssistantHomePrefs.rememberVoiceSession(app, parent)
            assertEquals(parent, AssistantHomePrefs.resolveVoiceSession(app))
            passed("voice entry restores the last valid session and ignores deleted sessions")
            report.put("sharedSha256", shared.expectedSha256).put("taskId", taskId).put("parentTaskId", root.id).put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.stackTraceToString()); throw error }
        finally {
            val editor = prefs.edit().clear()
            savedPrefs.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value) } }
            editor.commit(); AssistantHomePrefs.prime(app)
            File(app.filesDir, "agent-integrated-delivery-e2e.json").writeText(report.toString(2))
            ids.forEach { withContext(Dispatchers.Main) { AgentSessionStore.closeAndAwait(it); AgentSessionStore.release(it) }; app.chatRepository.deleteSession(it) }
            sharedPaths.forEach { runCatching { WorkspaceFileClient.delete(null, it) } }
            tasks.forEach { app.database.openHelper.writableDatabase.execSQL("DELETE FROM agent_tasks WHERE id = ?", arrayOf(it)) }
        }
    }

    private class Gate(private val delegate: ScriptedProvider) : LLMProvider {
        override val name get() = delegate.name
        override var model: LLMModel
            get() = delegate.model
            set(value) { delegate.model = value }
        @Volatile var waiting = false
        val release = CompletableDeferred<Unit>()
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = delegate.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            waiting = true; release.await(); emitAll(delegate.streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel))
        }
    }
}
