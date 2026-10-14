package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.buildRequestBody
import com.openminis.app.provider.openai.buildResponsesAPIBody
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.ToolCheckpointStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Room, session loading, compaction/revert, checkpoint recovery and AgentRunner requests. */
@RunWith(AndroidJUnit4::class)
class AgentContextWorkflowE2ETest {
    @Test
    fun interruptedPartialBatchKeepsItsUnknownCheckpoint(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        val session = app.chatRepository.createSession("context-scripted", title = "Context recovery E2E")
        val artifact = File(app.filesDir, "agent-context-recovery-e2e.json")
        val report = JSONObject().put("sessionId", session.id).put("device", android.os.Build.FINGERPRINT)
        try {
            app.chatRepository.appendMessage(session.id, "user", """[{"type":"text","value":"Check state before continuing"}]""")
            val calls = JSONArray()
            val executionIds = mutableMapOf<String, String>()
            for (id in listOf("context-recorded", "context-interrupted")) {
                calls.put(JSONObject().put("type", "toolUse").put("value", JSONObject()
                    .put("toolUseId", id).put("name", "android.time").put("input", "{}")))
                executionIds[id] = checkNotNull(ToolCheckpointStore.recordIntent(app, session.id, id, "android.time", "{}"))
            }
            app.chatRepository.appendMessage(session.id, "assistant", calls.toString())
            app.chatRepository.appendMessage(session.id, "user", JSONArray().put(JSONObject()
                .put("type", "toolResult").put("value", JSONObject().put("toolUseId", "context-recorded")
                    .put("executionIntentId", executionIds.getValue("context-recorded"))
                    .put("output", "Persisted actual result").put("success", true))).toString())
            val vm = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
            withTimeout(20_000L) { vm.sessionLoaded.first { it } }
            val provider = ContextProvider()
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider
                vm.currentModel = provider.model
                vm._activeEntryId.value = "context-scripted-entry"
            }
            val result = AgentRunner.prompt(app, session.id, "VERIFY_INTERRUPTED_BATCH", wait = true, timeoutMs = 20_000L)
            assertTrue(result.toString(), result.completed)
            val pending = ToolCheckpointStore.drainPending(app, session.id).map { it.callId }
            val placeholders = provider.requests.last().flatMap { it.contentParts }
                .filterIsInstance<AgentContentPart.ToolResult>().filter { it.id == "context-interrupted" }
            val warningDelivered = provider.requests.last().any { it.content.contains("[tool outcome unknown]") }
            report.put("pendingCallIds", JSONArray(pending)).put("placeholderCount", placeholders.size)
                .put("warningDelivered", warningDelivered)
            assertEquals(1, placeholders.size)
            assertTrue(placeholders.single().content.contains("TOOL_INTERRUPTED"))
            assertEquals("a placeholder merged into a persisted result row is not an executed result", listOf("context-interrupted"), pending)
            assertTrue(warningDelivered)
            repeat(2) {
                vm.effectiveAgentHistory()
                assertEquals(listOf("context-interrupted"), ToolCheckpointStore.drainPending(app, session.id).map { rec -> rec.callId })
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            withContext(Dispatchers.Main) { AgentRunner.forget(session.id) }
            ToolCheckpointStore.clearSession(app, session.id)
            SessionEventHub.clear(session.id)
            app.chatRepository.deleteSession(session.id)
            artifact.writeText(report.toString(2))
        }
    }

    @Test
    fun compactionRestoreAndClearUseOneConversation(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        UbuntuPaths.initialize(app)
        val session = app.chatRepository.createSession("context-scripted", title = "Agent context E2E")
        val artifact = File(app.filesDir, "agent-context-e2e.json")
        val report = JSONObject().put("sessionId", session.id).put("device", android.os.Build.FINGERPRINT)
            .put("scenarios", JSONArray()).put("model", "controlled transport; real App/Room/AgentRunner/compaction/checkpoints")
            .put("unverified", JSONArray(listOf("real model quality", "process kill", "Root/OEM device")))
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
            artifact.writeText(report.toString(2))
        }
        suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
        suspend fun until(predicate: suspend () -> Boolean) = withTimeout(20_000L) { while (!predicate()) delay(20) }
        val provider = ContextProvider()
        suspend fun prepare(): ChatViewModel {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
            withTimeout(20_000L) { vm.sessionLoaded.first { it } }
            ui {
                vm.currentProvider = provider
                vm.currentModel = provider.model
                vm._activeEntryId.value = "context-scripted-entry"
            }
            return vm
        }
        suspend fun prompt(text: String) {
            val result = AgentRunner.prompt(app, session.id, text, wait = true, timeoutMs = 20_000L)
            assertTrue(result.toString(), result.completed)
        }
        suspend fun compact(vm: ChatViewModel) {
            ui { vm.compactAll() }
            until { !vm.isCompacting.value }
            assertNotNull(app.chatRepository.dao.latestCompactMarker(session.id))
            assertTrue(vm.compactSummary.value.orEmpty().contains("CONTEXT_E2E_SUMMARY"))
        }
        try {
            for (index in 1..8) {
                for ((role, text) in listOf("user" to "Earlier task $index\n${"已完成的背景信息。".repeat(450)}", "assistant" to "Earlier answer $index")) {
                    app.chatRepository.appendMessage(session.id, role,
                        JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
                }
            }
            var vm = prepare()
            assertEquals(16, vm.agentHistory.size)
            assertSame(vm.execution.conversation.history, vm.agentHistory)
            assertSame(vm.execution.conversation.toolLoopDetector, vm.toolLoopDetector)
            passed("session restores exactly the persisted history into one conversation")
            val unknownExecutionId = checkNotNull(ToolCheckpointStore.recordIntent(app, session.id, "context-unknown", "linux.file.write", "{}"))
            repeat(2) {
                assertTrue(vm.effectiveAgentHistory().any { message -> message.content.contains("[tool outcome unknown]") })
                assertEquals(1, ToolCheckpointStore.drainPending(app, session.id).size)
            }
            ToolCheckpointStore.acknowledge(app, session.id, setOf(unknownExecutionId))
            passed("reading interrupted tool warnings twice never acknowledges an unknown outcome")

            prompt("MOST_RECENT_INSTRUCTION: continue this task")
            val before = vm.agentHistory.map { it.copy() }
            compact(vm)
            assertEquals(before, vm.agentHistory)
            prompt("FOLLOW_UP_AFTER_COMPACT")
            assertEquals(before, vm.agentHistory.take(before.size))
            assertTrue(provider.requests.last().any { it.content.contains("<context-summary>") && it.content.contains("CONTEXT_E2E_SUMMARY") })
            assertTrue(provider.requests.last().any { it.content.contains("MOST_RECENT_INSTRUCTION") })
            assertFalse(vm.agentHistory.any { it.content.contains("<context-summary>") })
            val (chatBody, responsesBody) = provider.wireBodies.last()
            File(app.filesDir, "agent-context-chat-request.json").writeText(chatBody.toString(2))
            File(app.filesDir, "agent-context-responses-request.json").writeText(responsesBody.toString(2))
            val chatWire = chatBody.getJSONArray("messages").toString()
            val responsesWire = responsesBody.getJSONArray("input").toString()
            report.put("chatWireHasSummary", chatWire.contains("CONTEXT_E2E_SUMMARY"))
                .put("responsesWireHasSummary", responsesWire.contains("CONTEXT_E2E_SUMMARY"))
            for (wire in listOf(chatWire, responsesWire)) {
                assertTrue("structured provider request must carry summary", wire.contains("CONTEXT_E2E_SUMMARY"))
                assertTrue(wire.indexOf("<context-summary>") < wire.indexOf("MOST_RECENT_INSTRUCTION"))
                assertEquals(1, Regex("CONTEXT_E2E_SUMMARY").findAll(wire).count())
            }
            passed("real compaction preserves canonical audit history and injects summary only into outgoing model requests")

            val previousConversation = vm.execution.conversation
            ui { AgentRunner.forget(session.id) }
            vm = prepare()
            assertNotSame(previousConversation, vm.execution.conversation)
            assertEquals(app.chatRepository.dao.loadMessages(session.id).size, vm.agentHistory.size)
            assertTrue(vm.compactSummary.value.orEmpty().contains("CONTEXT_E2E_SUMMARY"))
            prompt("FOLLOW_UP_AFTER_REOPEN")
            assertTrue(provider.requests.last().any { it.content.contains("<context-summary>") })
            assertTrue(provider.wireBodies.last().first.getJSONArray("messages").toString().contains("CONTEXT_E2E_SUMMARY"))
            assertTrue(provider.wireBodies.last().second.getJSONArray("input").toString().contains("CONTEXT_E2E_SUMMARY"))
            passed("reopened session restores marker and summary before its first model request")

            ui { vm.sessionLoaded.value = false; vm.revertCompact() }
            withTimeout(20_000L) { vm.sessionLoaded.first { it } }
            assertNull(app.chatRepository.dao.latestCompactMarker(session.id))
            assertNull(vm.compactSummary.value)
            val expected = app.chatRepository.dao.loadMessages(session.id).map { it.id }
            assertEquals("revert must replace history rather than append it twice", expected, vm.agentHistory.map { it.dbMessageId })
            for (index in 1..2) {
                ui { vm.sessionLoaded.value = false; vm.reloadSessionFromDb() }
                withTimeout(20_000L) { vm.sessionLoaded.first { it } }
                assertEquals(expected, vm.agentHistory.map { it.dbMessageId })
            }
            ui { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "context-scripted-entry" }
            prompt("AFTER_REVERT_FULL_HISTORY")
            assertFalse(provider.requests.last().any { it.content.contains("<context-summary>") })
            assertEquals(expected.size + 1, provider.requests.last().size)
            passed("revert and repeated reloads replace history once; next request has full history without stale summary")

            provider.toolMode = true
            prompt("Dispatch a real Android time tool")
            provider.toolMode = false
            val actual = vm.agentHistory.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "context-time" }
            assertNotNull(actual.executionIntentId)
            // Restore the exact journal entry to simulate Room commit followed by death before acknowledgement.
            File(app.filesDir, "checkpoints/${session.id}.jsonl").appendText(JSONObject()
                .put("callId", actual.id).put("tool", actual.name).put("args", "{}").put("state", "pending")
                .put("at", System.currentTimeMillis()).put("intentId", actual.executionIntentId)
                .put("runId", actual.executionRunId ?: JSONObject.NULL).put("taskId", actual.executionTaskId ?: JSONObject.NULL)
                .toString() + "\n")
            vm.effectiveAgentHistory()
            assertTrue(ToolCheckpointStore.drainPending(app, session.id).isEmpty())
            val toolParts = provider.requests.last().flatMap { it.contentParts }
            assertEquals(1, toolParts.filterIsInstance<AgentContentPart.ToolUse>().count { it.id == "context-time" })
            assertEquals(1, toolParts.filterIsInstance<AgentContentPart.ToolResult>().count { it.id == "context-time" })
            passed("persisted real tool result reconciles checkpoint without duplicating or losing tool pairs")

            compact(vm)
            withContext(Dispatchers.Main) { vm.clearChat() }?.join()
            assertNull("clear must reset the UI summary projection too", vm.compactSummary.value)
            assertTrue(vm.agentHistory.isEmpty())
            until { app.chatRepository.dao.loadMessages(session.id).isEmpty() && app.chatRepository.dao.latestCompactMarker(session.id) == null }
            prompt("NEW_CONTEXT_AFTER_CLEAR")
            assertEquals(1, provider.requests.last().size)
            assertFalse(provider.requests.last().any { it.content.contains("CONTEXT_E2E_SUMMARY") })
            passed("clear drops history and compact projection; new turn starts a fresh context")
            report.put("passed", true).put("modelCalls", provider.requests.size).put("summaryCalls", provider.summaryCalls)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            ui { AgentRunner.forget(session.id) }
            ToolCheckpointStore.clearSession(app, session.id)
            SessionEventHub.clear(session.id)
            app.chatRepository.deleteSession(session.id)
            artifact.writeText(report.toString(2))
        }
    }

    private class ContextProvider : LLMProvider {
        override val name = "Context controlled provider"
        override var model = LLMModel("context-scripted", "Context scripted", "scripted", contextWindow = 128000)
        var toolMode = false
        var summaryCalls = 0
        var requests = listOf<List<LLMMessage>>()
        var wireBodies = listOf<Pair<JSONObject, JSONObject>>()
        private val wireEncoder = OpenAIProvider(apiKey = "context-e2e-placeholder", model = model)
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse {
            summaryCalls++
            return LLMResponse("CONTEXT_E2E_SUMMARY: Earlier tasks were completed; the latest instruction stayed authoritative.", "stop", null)
        }
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            requests = requests + listOf(messages.toList())
            wireBodies = wireBodies + (wireEncoder.buildRequestBody(messages, systemPrompt, maxTokens, true,
                temperature, imageParts, tools, thinkingLevel) to wireEncoder.buildResponsesAPIBody(
                messages, systemPrompt, maxTokens, true, temperature, imageParts, tools, thinkingLevel))
            if (toolMode && messages.last().contentParts.none { it is AgentContentPart.ToolResult && it.id == "context-time" }) {
                emit(LLMStreamChunk.ToolUseStart("context-time", "android.time"))
                emit(LLMStreamChunk.ToolInputDelta("context-time", "{}"))
                emit(LLMStreamChunk.ToolCallComplete("context-time", "android.time", JSONObject()))
                emit(LLMStreamChunk.Finished("tool_calls"))
            } else {
                emit(LLMStreamChunk.Text("Context answer"))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
    }
}
