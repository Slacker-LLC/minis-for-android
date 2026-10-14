package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.files.WorkspaceFileClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real request submission, owner teardown/recreation, Room, file actions and queue delivery. */
@RunWith(AndroidJUnit4::class)
class AgentDurableRequestsE2ETest {
    @Test fun durableAcceptanceDeliveryAndRecovery(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val ids = mutableListOf<String>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun until(check: suspend () -> Boolean) = withTimeout(20_000) { while (!check()) delay(20) }
        suspend fun session(provider: LLMProvider): AgentSession {
            val sid = app.chatRepository.createSession("scripted-model", title = "M5 durable requests", memoryEnabled = false).id
            ids += sid
            val core = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, sid) }
            core.sessionLoaded.first { it }
            withContext(Dispatchers.Main) { core.currentProvider = provider; core.currentModel = provider.model; core._activeEntryId.value = "durable" }
            return core
        }
        try {
            val gate = Gate(ScriptedProvider { call, _, _ -> when (call) {
                1 -> toolCall("durable-write", "file_write", JSONObject().put("path", "/workspace/durable.txt").put("content", "one write\n"))
                2 -> text("Original request completed")
                else -> throw IllegalStateException("queued-request-failed")
            } })
            val core = session(gate)
            val turn = withContext(Dispatchers.Main) { AgentRunner.submit(core, "Original request", requestId = "original-stable") }
            report.put("step", "original provider gate")
            until { gate.waiting }
            assertNotNull(turn.accepted.await())
            val initial = app.chatRepository.dao.getMessageById(turn.userMessageId!!)!!
            assertEquals("RUNNING", initial.requestState)
            withContext(Dispatchers.Main) { core.enqueuePrompt("Independent next request", PendingDelivery.QUEUE) }
            until { app.chatRepository.dao.pendingRequests(core.currentSessionId).size == 1 }
            val queued = app.chatRepository.dao.pendingRequests(core.currentSessionId).single()
            assertEquals("PENDING", queued.requestState)
            assertEquals("QUEUE", queued.requestDelivery)
            assertFalse(core.agentHistory.any { it.dbMessageId == queued.id })
            assertEquals(queued.id, core.promptQueue.value.single().id)
            gate.release.complete(Unit)
            assertEquals(AgentTurnOutcome.Completed, withTimeout(20_000) { turn.result.await() })
            report.put("step", "queued request terminal state")
            until { app.chatRepository.dao.getMessageById(queued.id)?.requestState == "FAILED" }
            assertEquals("COMPLETED", app.chatRepository.dao.getMessageById(initial.id)?.requestState)
            assertNotEquals(app.chatRepository.dao.getMessageById(initial.id)?.requestRunId, app.chatRepository.dao.getMessageById(queued.id)?.requestRunId)
            assertEquals("one write\n", WorkspaceFileClient.readAll(core.currentSessionId, "/workspace/durable.txt").decodeToString())
            passed("durable pending row stays outside model history, claims once and queued failure preserves original completion")
            val calls = gate.delegate.calls
            val repeated = AgentRunner.prompt(app, core.currentSessionId, "Original request", requestId = "original-stable", wait = true, timeoutMs = 15_000)
            assertTrue(repeated.completed); assertEquals(initial.id, repeated.requestMessageId)
            assertEquals(calls, gate.delegate.calls)
            assertEquals("Original request completed", repeated.responseText)
            passed("same stable request returns its exact run reply without another provider request or file action")

            val hold = Gate(ScriptedProvider { _, _, _ -> text("finished") })
            val recover = session(hold)
            val running = withContext(Dispatchers.Main) { AgentRunner.submit(recover, "Interrupted request") }
            report.put("step", "second provider gate")
            until { hold.waiting }
            withContext(Dispatchers.Main) {
                recover.enqueuePrompt("Persist queue before owner release", PendingDelivery.QUEUE)
                recover.enqueuePrompt("Old steer must be reviewed", PendingDelivery.STEER)
            }
            until { app.chatRepository.dao.pendingRequests(recover.currentSessionId).size == 2 }
            val pendingIds = app.chatRepository.dao.pendingRequests(recover.currentSessionId).map { it.id }.toSet()
            withContext(Dispatchers.Main) { AgentSessionStore.closeAndAwait(recover.currentSessionId); AgentSessionStore.release(recover.currentSessionId) }
            assertEquals(AgentTurnOutcome.Cancelled, running.result.await())
            val restored = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, recover.currentSessionId) }
            restored.sessionLoaded.first { it }
            assertEquals("INTERRUPTED", app.chatRepository.dao.getMessageById(running.userMessageId!!)?.requestState)
            assertEquals(pendingIds, restored.promptQueue.value.map { it.id }.toSet())
            assertEquals("NEEDS_REVIEW", app.chatRepository.dao.pendingRequests(recover.currentSessionId).single { it.requestDelivery == "STEER" }.requestState)
            assertFalse(restored.agentHistory.any { it.dbMessageId in pendingIds })
            assertFalse(restored.isStreaming.value)
            passed("owner reconstruction retains original pending identities and marks started input interrupted without replay")
            app.chatRepository.deleteMessagesAfter(recover.currentSessionId, 0)
            assertEquals(pendingIds, app.chatRepository.dao.pendingRequests(recover.currentSessionId).map { it.id }.toSet())
            val queuedId = restored.promptQueue.value.first { it.delivery == PendingDelivery.QUEUE }.id
            withContext(Dispatchers.Main) { restored.withdrawQueuedMessage(queuedId) }
            until { app.chatRepository.dao.getMessageById(queuedId)?.requestState == "WITHDRAWN" }
            assertNotNull(app.chatRepository.dao.getMessageById(queuedId))
            passed("history truncation retains unclaimed inputs and withdrawal persists original text without claiming")
            report.put("requestIds", JSONArray(listOf(initial.id, queued.id) + pendingIds))
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.stackTraceToString()); throw error }
        finally {
            File(app.filesDir, "agent-durable-requests-e2e.json").writeText(report.toString(2))
            for (id in ids) { withContext(Dispatchers.Main) { AgentSessionStore.closeAndAwait(id); AgentSessionStore.release(id) }; app.chatRepository.deleteSession(id) }
        }
    }

    private class Gate(val delegate: ScriptedProvider) : LLMProvider {
        override val name: String get() = delegate.name
        override var model: LLMModel
            get() = delegate.model
            set(value) { delegate.model = value }
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse =
            delegate.sendMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
        val release = CompletableDeferred<Unit>()
        @Volatile var waiting = false
        private var first = true
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            if (first) { first = false; waiting = true; release.await() }
            emitAll(delegate.streamMessageClamped(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel))
        }
    }
}
