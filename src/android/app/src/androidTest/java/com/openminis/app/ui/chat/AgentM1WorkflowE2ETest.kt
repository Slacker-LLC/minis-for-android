package com.openminis.app.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.tools.SessionPermissionStore
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
import java.security.MessageDigest

/** Real Activity lifecycle, Room, file tool, compaction and cancellation; only model transport is controlled. */
@RunWith(AndroidJUnit4::class)
class AgentM1WorkflowE2ETest {
    @Test
    fun pagesMutationsCompactionAndDeletionShareOneExecutionOwner(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        UbuntuPaths.initialize(app)
        val ids = mutableListOf<String>()
        val providers = mutableListOf<GateProvider>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val artifact = File(app.filesDir, "agent-m1-e2e.json")
        var activity: ActivityScenario<MainActivity>? = null
        suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
        suspend fun until(check: suspend () -> Boolean) = withTimeout(30_000L) { while (!check()) delay(20) }
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
            artifact.writeText(report.toString(2))
        }
        suspend fun session(provider: GateProvider): AgentSession {
            providers += provider
            val row = app.chatRepository.createSession("m1-scripted", title = "M1 full workflow")
            ids += row.id
            val core = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, row.id) }
            withTimeout(15_000L) { core.sessionLoaded.first { it } }
            main { core.currentProvider = provider; core.currentModel = provider.model; core._activeEntryId.value = "m1-entry" }
            return core
        }
        try {
            val idleCore = session(GateProvider())
            activity = ActivityScenario.launch(Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("minis://session/${idleCore.currentSessionId}")))
            until { ChatViewModelStore.activeSessionId == idleCore.currentSessionId }
            activity!!.recreate()
            until { ChatViewModelStore.activeSessionId == idleCore.currentSessionId }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse("outgoing empty projection cannot close its mounted successor", idleCore.execution.isClosed)
            assertNotNull(app.chatRepository.getSession(idleCore.currentSessionId))
            val firstTurn = withContext(Dispatchers.Main) { AgentRunner.submit(idleCore, "First task after empty page replacement") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { firstTurn.result.await() })
            activity!!.close(); activity = null
            passed("idle same-session page replacement preserves the canonical owner before the first turn")

            val provider = GateProvider().apply { mode = "file" }
            val core = session(provider)
            assertFalse("headless execution allocates no page store", ChatViewModelStore.hasStore(core.currentSessionId))
            SessionPermissionStore.setPreset(app, core.currentSessionId, SessionPermissionStore.WORKSPACE_WRITE)
            val turn = withContext(Dispatchers.Main) { AgentRunner.submit(core, "Build /workspace/m1-result.txt and retain its evidence") }
            until { provider.waiting }
            assertEquals(2, provider.calls)
            assertEquals("M1 single execution\n", WorkspaceFileClient.readAll(core.currentSessionId, "/workspace/m1-result.txt").decodeToString())
            passed("headless turn creates one core without a ViewModel and executes the real workspace file tool")

            activity = ActivityScenario.launch(Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("minis://session/${core.currentSessionId}")))
            until { ChatViewModelStore.activeSessionId == core.currentSessionId }
            assertSame(core, withContext(Dispatchers.Main) { chatProjection(app, core.currentSessionId).agentSession })
            activity!!.moveToState(Lifecycle.State.CREATED)
            assertTrue(core.execution.currentJob!!.isActive)
            activity!!.moveToState(Lifecycle.State.RESUMED)
            activity!!.recreate()
            until { ChatViewModelStore.activeSessionId == core.currentSessionId }
            assertSame(core, withContext(Dispatchers.Main) { chatProjection(app, core.currentSessionId).agentSession })
            activity!!.close(); activity = null
            assertTrue(core.execution.currentJob!!.isActive)
            val oldPage = withContext(Dispatchers.Main) { chatProjection(app, core.currentSessionId) }
            main { ChatViewModelStore.ownerFor(core.currentSessionId).viewModelStore.clear() }
            val newPage = withContext(Dispatchers.Main) { chatProjection(app, core.currentSessionId) }
            assertNotSame(oldPage, newPage)
            assertSame(core, newPage.agentSession)
            provider.gate.complete(Unit)
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { turn.result.await() })
            assertEquals(2, provider.calls)
            assertEquals(1, core.agentHistory.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolUse>().count { it.id == "m1-write" })
            assertEquals(1, app.chatRepository.dao.loadMessages(core.currentSessionId).count { it.role == "assistant" && it.partsJson.contains("M1 finished") })
            val file = WorkspaceFileClient.readAll(core.currentSessionId, "/workspace/m1-result.txt")
            File(app.filesDir, "m1-result-e2e.txt").writeBytes(file)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) })
            passed("real page open, background, recreate, close and projection destruction retain one loop, tool execution and terminal reply")

            val clearing = GateProvider().apply { mode = "hold"; delayCleanup = true }
            val clearCore = session(clearing)
            val beforeClear = withContext(Dispatchers.Main) { AgentRunner.submit(clearCore, "Old partial turn") }
            until { clearing.waiting }
            main { clearCore.clearChat() }
            assertTrue(clearCore.isMutating.value)
            val duringClear = withContext(Dispatchers.Main) { AgentRunner.submit(clearCore, "Do not cross the clear transaction") }
            assertEquals(AgentTurnOutcome.Rejected("session_mutating"), duringClear.result.await())
            assertTrue("clear waits for actual old cleanup", app.chatRepository.dao.loadMessages(clearCore.currentSessionId).isNotEmpty())
            clearing.cleanup.complete(Unit)
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000L) { beforeClear.result.await() })
            until { !clearCore.isMutating.value }
            assertTrue(app.chatRepository.dao.loadMessages(clearCore.currentSessionId).isEmpty())
            assertTrue(clearCore.agentHistory.isEmpty())
            clearing.mode = "done"
            val fresh = withContext(Dispatchers.Main) { AgentRunner.submit(clearCore, "Fresh task after clear") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { fresh.result.await() })
            assertEquals(2, app.chatRepository.dao.loadMessages(clearCore.currentSessionId).size)
            passed("clear fences late cancelled cleanup, rejects a crossing submit and commits a fresh two-row conversation")

            val deleting = GateProvider().apply { mode = "hold"; delayCleanup = true }
            val deleteCore = session(deleting)
            val deleteTurn = withContext(Dispatchers.Main) { AgentRunner.submit(deleteCore, "Delete during delayed cleanup") }
            until { deleting.waiting }
            val deletion = async(Dispatchers.Default) { app.chatRepository.deleteSession(deleteCore.currentSessionId) }
            until { deleteCore.execution.isClosed }
            assertFalse(deletion.isCompleted)
            assertNotNull(app.chatRepository.getSession(deleteCore.currentSessionId))
            val stale = withContext(Dispatchers.Main) { AgentRunner.submit(deleteCore, "Late submission") }
            assertEquals(AgentTurnOutcome.Rejected("session_closed"), stale.result.await())
            deleting.cleanup.complete(Unit)
            withTimeout(15_000L) { deletion.await() }
            assertEquals(AgentTurnOutcome.Cancelled, deleteTurn.result.await())
            assertNull(app.chatRepository.getSession(deleteCore.currentSessionId))
            assertTrue(app.chatRepository.dao.loadMessages(deleteCore.currentSessionId).isEmpty())
            passed("delete closes the owner, awaits late cleanup before Room cascade and refuses stale submissions")

            val compacting = GateProvider().apply { holdSummary = true }
            val compactCore = session(compacting)
            for (index in 1..8) for ((role, text) in listOf("user" to "Earlier task $index ${"已完成的背景信息。".repeat(450)}", "assistant" to "Earlier answer $index")) {
                app.chatRepository.appendMessage(compactCore.currentSessionId, role,
                    JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
            withContext(Dispatchers.Main) { compactCore.loadSession() }?.join()
            val compactPage = withContext(Dispatchers.Main) { chatProjection(app, compactCore.currentSessionId) }
            val compaction = async { AgentRunner.compact(app, compactCore.currentSessionId, wait = true, timeoutMs = 20_000L,
                messageId = compactCore.agentHistory.first().dbMessageId, includesBoundary = true) }
            until { compacting.summaryWaiting }
            main { ChatViewModelStore.ownerFor(compactCore.currentSessionId).viewModelStore.clear() }
            assertFalse(compactCore.execution.isClosed)
            val compactPage2 = withContext(Dispatchers.Main) { chatProjection(app, compactCore.currentSessionId) }
            assertNotSame(compactPage, compactPage2)
            assertSame(compactCore, compactPage2.agentSession)
            compacting.summaryGate.complete(Unit)
            assertEquals("Completed", compaction.await().status)
            assertNotNull(app.chatRepository.dao.latestCompactMarker(compactCore.currentSessionId))
            assertTrue(compactCore.compactSummary.value.orEmpty().contains("M1 compact summary"))
            report.put("allHistoryBoundaryIgnored", true)
            passed("manual summary, budget and marker commit survive page projection destruction with one canonical history")

            for (index in 9..16) for ((role, text) in listOf("user" to "Additional task $index ${"已完成的背景信息。".repeat(450)}", "assistant" to "Additional answer $index")) {
                app.chatRepository.appendMessage(compactCore.currentSessionId, role,
                    JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
            withContext(Dispatchers.Main) { compactCore.loadSession() }?.join()
            compacting.summaryWaiting = false
            compacting.holdSummary = false
            val beforeAsync = app.chatRepository.dao.listCompactMarkers(compactCore.currentSessionId).size
            val accepted = com.openminis.app.remote.AgentCommandRegistry.execute(app, compactCore.currentSessionId, "compact", "")
            assertTrue("a running remote command is accepted, not rendered as failure", accepted.ok)
            assertEquals("正在压缩会话", accepted.text)
            compactCore.compactJob!!.join()
            assertEquals(beforeAsync + 1, app.chatRepository.dao.listCompactMarkers(compactCore.currentSessionId).size)
            report.put("asyncCommandAccepted", true)
            for (index in 17..24) for ((role, text) in listOf("user" to "Failure task $index ${"已完成的背景信息。".repeat(450)}", "assistant" to "Failure answer $index")) {
                app.chatRepository.appendMessage(compactCore.currentSessionId, role,
                    JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
            withContext(Dispatchers.Main) { compactCore.loadSession() }?.join()
            compacting.failSummary = true
            val failedCompact = AgentRunner.compact(app, compactCore.currentSessionId, wait = true, timeoutMs = 15_000L)
            assertEquals("Error", failedCompact.status)
            assertNull(failedCompact.summary)
            passed("failed summary reports this request's failure instead of treating an old summary or idle UI as completion")

            val pressured = GateProvider().apply { mode = "file"; holdSummary = true; reportPressure = true }
            val pressureCore = session(pressured)
            SessionPermissionStore.setPreset(app, pressureCore.currentSessionId, SessionPermissionStore.WORKSPACE_WRITE)
            for (index in 1..8) for ((role, text) in listOf("user" to "Pressure task $index ${"已完成的背景信息。".repeat(450)}", "assistant" to "Pressure answer $index")) {
                app.chatRepository.appendMessage(pressureCore.currentSessionId, role,
                    JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
            withContext(Dispatchers.Main) { pressureCore.loadSession() }?.join()
            val pressureTurn = withContext(Dispatchers.Main) { AgentRunner.submit(pressureCore, "Continue until the real usage guard compacts") }
            until { pressured.summaryWaiting }
            main { pressureTurn.cancel() }
            assertEquals(AgentTurnOutcome.Cancelled, withTimeout(15_000L) { pressureTurn.result.await() })
            assertFalse(pressureCore.isCompacting.value)
            assertFalse(pressureCore.compactJob!!.isActive)
            pressured.summaryGate.complete(Unit)
            assertNull(app.chatRepository.dao.latestCompactMarker(pressureCore.currentSessionId))
            assertEquals(1, pressured.calls)
            passed("actual model usage triggers in-loop compaction and parent cancellation stops the summary before any late marker or second model call")

            val draftId = "__new__m1-${java.util.UUID.randomUUID()}"
            val draft = withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, draftId) }
            withTimeout(15_000L) { draft.sessionLoaded.first { it } }
            providers += GateProvider().also { p -> main { draft.currentProvider = p; draft.currentModel = p.model; draft._activeEntryId.value = "m1-entry" } }
            val draftTurn = withContext(Dispatchers.Main) { AgentRunner.submit(draft, "Persist draft exactly once") }
            assertEquals(AgentTurnOutcome.Completed, withTimeout(15_000L) { draftTurn.result.await() })
            ids += draft.currentSessionId
            assertSame(draft, withContext(Dispatchers.Main) { AgentRunner.sessionForCommand(app, draft.currentSessionId) })
            assertSame(draft, withContext(Dispatchers.Main) { chatProjection(app, draftId).agentSession })
            assertSame(draft, withContext(Dispatchers.Main) { chatProjection(app, draft.currentSessionId).agentSession })
            passed("draft rename and both page routes resolve the same persisted execution owner")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            providers.forEach { it.gate.complete(Unit); it.cleanup.complete(Unit); it.summaryGate.complete(Unit) }
            activity?.close()
            for (id in ids) {
                SessionPermissionStore.setPreset(app, id, null)
                app.chatRepository.deleteSession(id)
                main { ChatViewModelStore.release(id) }
                SessionEventHub.clear(id)
            }
            artifact.writeText(report.toString(2))
        }
    }

    private class GateProvider : LLMProvider {
        override val name = "M1 controlled transport"
        override var model = LLMModel("m1-scripted", "M1 controlled model", "m1", contextWindow = 100_000)
        @Volatile var mode = "done"
        @Volatile var calls = 0
        @Volatile var waiting = false
        @Volatile var delayCleanup = false
        @Volatile var holdSummary = false
        @Volatile var failSummary = false
        @Volatile var reportPressure = false
        @Volatile var summaryWaiting = false
        val gate = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val summaryGate = CompletableDeferred<Unit>()
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): LLMResponse {
            if (failSummary) error("m1_summary_failure")
            if (holdSummary) { summaryWaiting = true; summaryGate.await() }
            return LLMResponse("M1 compact summary: earlier tasks and file evidence are retained.", "stop", null)
        }
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) = flow {
            calls++
            if (mode == "file" && calls == 1) {
                val args = JSONObject().put("path", "/workspace/m1-result.txt").put("content", "M1 single execution\n")
                emit(LLMStreamChunk.ToolUseStart("m1-write", "file_write"))
                emit(LLMStreamChunk.ToolInputDelta("m1-write", args.toString()))
                emit(LLMStreamChunk.ToolCallComplete("m1-write", "file_write", args))
                if (reportPressure) emit(LLMStreamChunk.Usage(LLMUsage(inputTokens = 95_000, outputTokens = 10)))
                emit(LLMStreamChunk.Finished("tool_calls"))
            } else {
                if (mode != "done") {
                    emit(LLMStreamChunk.Text("M1 partial"))
                    waiting = true
                    try { gate.await() }
                    finally { if (delayCleanup) withContext(NonCancellable) { cleanup.await() } }
                }
                emit(LLMStreamChunk.Text("M1 finished"))
                emit(LLMStreamChunk.Finished("stop"))
            }
        }
    }
}
