package com.openminis.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.SessionPermissionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Ordinary AgentRunner entry, real App Room/store/files/tools; only the model transport is scripted. */
@RunWith(AndroidJUnit4::class)
class AgentCoreWorkflowE2ETest {
    @Test
    fun ordinaryAgentOwnsItsRequestsAndDeliversVerifiableArtifacts(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded()
        UbuntuPaths.initialize(app)
        val session = app.chatRepository.createSession("core-scripted", title = "Agent core E2E")
        val report = JSONObject().put("sessionId", session.id).put("device", android.os.Build.FINGERPRINT)
            .put("model", "controlled transport; real AgentRunner, Room, workspace and Android tools")
            .put("scenarios", JSONArray()).put("unverified", JSONArray(listOf("real model quality", "Ubuntu builds", "browser work", "Root/OEM device")))
        val artifact = File(app.filesDir, "agent-core-e2e.json")
        fun passed(name: String) {
            report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true))
            artifact.writeText(report.toString(2))
        }
        suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
        suspend fun until(predicate: () -> Boolean) = withTimeout(15_000L) { while (!predicate()) delay(20) }
        val provider = ControlledProvider()
        provider.deniedPath = "/var/minis/shared/${session.id}-denied.txt"
        val vm = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
        suspend fun prepare(model: ChatViewModel) {
            withTimeout(10_000L) { model.sessionLoaded.first { it } }
            ui {
                model.currentProvider = provider
                model.currentModel = provider.model
                model._activeEntryId.value = "core-scripted-entry"
            }
        }
        suspend fun prompt(text: String, timeout: Long = 15_000L) = AgentRunner.prompt(
            app, session.id, text, wait = true, timeoutMs = timeout,
        )
        try {
            prepare(vm)
            for (source in listOf(null, "debug", "subagent", "sub_agent", "ralph")) {
                val created = AgentRunner.ensureSession(app, "core-scripted", source)
                try { assertEquals(source, app.chatRepository.getSession(created)?.source) }
                finally { app.chatRepository.deleteSession(created) }
            }
            passed("session creation stores the actual caller source atomically; ordinary work is not labelled debug")
            SessionPermissionStore.setPreset(app, session.id, SessionPermissionStore.WORKSPACE_WRITE)
            assertSame(vm, withContext(Dispatchers.Main) {
                ViewModelProvider(ChatViewModelStore.ownerFor(session.id))[ChatViewModel::class.java]
            })
            provider.mode = "files"
            val work = prompt("Write and read back /workspace/core-result.txt")
            assertTrue(work.toString(), work.completed)
            val bytes = WorkspaceFileClient.readAll(session.id, "/workspace/core-result.txt")
            assertEquals("Minis core artifact\n", bytes.decodeToString())
            val read = provider.requests.flatMap { it }.flatMap { it.contentParts }
                .filterIsInstance<AgentContentPart.ToolResult>().first { it.id == "core-read" }
            assertFalse(read.isError)
            assertTrue(read.content.contains("Minis core artifact"))
            File(app.filesDir, "core-result-e2e.txt").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            passed("ordinary Agent writes, reads, verifies and persists artifact; UI/backend share one VM")

            provider.mode = "deny"
            assertTrue(prompt("Try an external write from workspace-write").completed)
            assertFalse(WorkspaceFileClient.info(session.id, "/var/minis/shared/${session.id}-denied.txt").optBoolean("exists"))
            assertTrue(provider.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "core-denied" }.isError)
            passed("denied external write is reported to model and creates no file")

            provider.mode = "time"
            assertTrue(prompt("Check the phone time and use previous work").completed)
            assertTrue(provider.requests.last().joinToString { it.content }.contains("Write and read back"))
            assertFalse(provider.requests.last().flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                .single { it.id == "core-time" }.isError)
            passed("Android tool dispatch and subsequent turns retain the same history")

            val draft = InputAttachment(fileName = "unsent.txt", uri = Uri.parse("content://core-e2e/unsent"),
                mimeType = "text/plain", kind = InputAttachment.Kind.DOCUMENT)
            provider.mode = "wait"
            provider.gate = CompletableDeferred()
            provider.waiting = false
            ui { vm.addAttachment(draft); vm.setInputText("keep this draft") }
            val owner = async { prompt("Hold this request") }
            until { provider.waiting }
            val calls = provider.calls
            val busy = prompt("Second request must not claim the first reply")
            assertEquals("Busy", busy.status)
            assertFalse(busy.completed)
            assertEquals(calls, provider.calls)
            assertEquals(listOf(draft), vm.attachments.value)
            assertEquals("keep this draft", vm.inputText.value)
            assertSame(vm, withContext(Dispatchers.Main) { chatProjection(app, session.id) })
            provider.gate.complete(Unit)
            assertTrue(owner.await().completed)
            passed("busy request cannot start another loop or take ownership; programmatic input preserves composer")

            provider.waiting = false
            provider.gate = CompletableDeferred()
            val callsBeforeTimeout = provider.calls
            val timed = prompt("Wait until timeout", timeout = 100)
            assertEquals("Timeout", timed.status)
            assertTrue(timed.timedOut && timed.streamExited)
            assertFalse(vm.isStreaming.value)
            assertFalse(session.id in com.openminis.app.service.SessionConcurrencyManager.runningSessions.value)
            report.put("timeoutModelStarted", provider.calls > callsBeforeTimeout)
            passed("timeout cancels only the owned request and waits for real coroutine exit")

            provider.waiting = false
            provider.gate = CompletableDeferred()
            val cancelled = async { prompt("Caller cancels its request") }
            until { provider.waiting }
            cancelled.cancelAndJoin()
            assertTrue(vm.awaitStreamExit(5_000L))
            assertFalse(vm.isStreaming.value)
            passed("caller cancellation propagates to its own request and settles execution")

            provider.waiting = false
            provider.gate = CompletableDeferred()
            provider.holdCleanup = true
            provider.cleanupStarted = CompletableDeferred()
            provider.releaseCleanup = CompletableDeferred()
            val predecessor = withContext(Dispatchers.Main) { AgentRunner.submit(vm.agentSession, "Cancel with delayed cleanup") }
            until { provider.waiting }
            ui { vm.cancelStream() }
            withTimeout(5_000L) { provider.cleanupStarted.await() }
            provider.holdCleanup = false
            provider.waiting = false
            provider.gate = CompletableDeferred()
            val callsDuringCleanup = provider.calls
            val successor = withContext(Dispatchers.Main) { AgentRunner.submit(vm.agentSession, "Wait for prior cleanup") }
            delay(200)
            assertEquals(callsDuringCleanup, provider.calls)
            assertTrue(vm.isStreaming.value)
            assertFalse(vm.awaitStreamExit(50))
            provider.releaseCleanup.complete(Unit)
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, predecessor.result.await())
            until { provider.waiting }
            assertTrue(vm.isStreaming.value)
            provider.gate.complete(Unit)
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Completed, successor.result.await())
            passed("cancel then send fences prior cleanup; old completion cannot clear the successor busy state")

            provider.waiting = false
            provider.gate = CompletableDeferred()
            provider.holdCleanup = true
            provider.cleanupStarted = CompletableDeferred()
            provider.releaseCleanup = CompletableDeferred()
            val beforeRetry = withContext(Dispatchers.Main) { AgentRunner.submit(vm.agentSession, "Cancel before retry preparation") }
            until { provider.waiting }
            ui { vm.cancelStream() }
            withTimeout(5_000L) { provider.cleanupStarted.await() }
            val retryId = beforeRetry.userMessageId!!
            val callsBeforeRetry = provider.calls
            val retryHandle = com.openminis.app.agent.AgentTurnHandle()
            ui { assertTrue(vm.retryFromMessage(retryId, retryHandle)); retryHandle.cancel() }
            assertFalse(retryHandle.result.isCompleted)
            assertFalse(vm.awaitStreamExit(50))
            provider.holdCleanup = false
            provider.releaseCleanup.complete(Unit)
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, beforeRetry.result.await())
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, retryHandle.result.await())
            assertEquals(callsBeforeRetry, provider.calls)
            passed("retry handle owns preparation and cancellation; exit includes predecessor cleanup without another model call")

            for ((mode, status) in listOf("failure" to "Error", "partial" to "Error", "length" to "NeedsAttention")) {
                provider.mode = mode
                val failed = prompt("Outcome $mode")
                assertEquals(failed.toString(), status, failed.status)
                assertFalse(failed.completed)
                assertNotEquals("Held answer", failed.responseText)
            }
            val targetId = app.chatRepository.dao.loadMessages(session.id).last { it.role == "user" }.id
            provider.mode = "failure"
            val retry = AgentRunner.retry(app, session.id, targetId, wait = true, timeoutMs = 15_000L)
            assertEquals("Error", retry.status)
            assertEquals(targetId, retry.retriedMessageId)
            passed("failure, partial stream, output limit and retry preserve their own terminal outcomes")

            provider.mode = "time"
            val beforeRetryLast = provider.calls
            ui { vm.retryLast() }
            assertTrue(vm.awaitStreamExit(15_000L))
            assertTrue(provider.calls > beforeRetryLast)
            assertNull(vm.error.value)
            assertTrue(vm.messages.value.any { it.content.contains("Phone time checked") })
            passed("UI retryLast runs through owned preparation and settles successfully")

            provider.mode = "partial-wait"
            provider.gate = CompletableDeferred()
            provider.waiting = false
            val partial = withContext(Dispatchers.Main) { AgentRunner.submit(vm.agentSession, "Stop and continue partial response") }
            until { provider.waiting }
            ui { vm.cancelStream() }
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, partial.result.await())
            assertTrue(vm.canResume.value)
            provider.mode = "done"
            val beforeResume = provider.calls
            ui { vm.resume() }
            assertTrue(vm.awaitStreamExit(15_000L))
            assertTrue(provider.calls > beforeResume)
            assertFalse(vm.canResume.value)
            assertFalse(vm.isStreaming.value)
            passed("partial response resumes through the same owned lifetime and clears resumable state")

            provider.mode = "wait"
            provider.gate = CompletableDeferred()
            provider.waiting = false
            val queueOwner = withContext(Dispatchers.Main) { AgentRunner.submit(vm.agentSession, "Stop with queued follow-up") }
            until { provider.waiting }
            ui { vm.clearAttachments(); vm.enqueuePrompt("Queued follow-up", PendingDelivery.QUEUE) }
            until { vm.promptQueue.value.isNotEmpty() }
            val beforeDrain = provider.calls
            provider.mode = "done"
            ui { vm.cancelStream() }
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, queueOwner.result.await())
            until { provider.calls > beforeDrain && vm.promptQueue.value.isEmpty() && !vm.isStreaming.value }
            assertTrue(vm.awaitStreamExit(15_000L))
            passed("cancelled turn hands queued follow-up to one new owned turn after real cleanup")

            val toolMessage = vm.messages.value.first { message -> message.toolBlocks.any { it.id == "core-read" } }
            provider.mode = "done"
            val rerun = AgentRunner.rerunFromToolBlock(app, session.id, toolMessage.id, "core-read", wait = true, timeoutMs = 15_000L)
            assertTrue(rerun.toString(), rerun.completed)
            assertFalse(vm.isStreaming.value)
            assertTrue(vm.messages.value.any { message -> message.toolBlocks.any { it.id == "core-write" } })
            passed("tool block rerun preserves preceding tools and settles its own complete turn")

            val snapshot = AgentRunner.sessionSnapshotWithWatermark(app, session.id)
            val rows = snapshot.value.getJSONArray("messages")
            assertTrue(rows.length() > 0)
            assertTrue((0 until rows.length()).any { rows.getJSONObject(it).optJSONArray("toolCalls")?.length()?.let { count -> count > 0 } == true })
            assertTrue(snapshot.lastSeq > 0)
            ui { AgentRunner.forget(session.id) }
            assertFalse(ChatViewModelStore.hasStore(session.id))
            val restored = withContext(Dispatchers.Main) { chatProjection(app, session.id) }
            prepare(restored)
            assertNotSame(vm, restored)
            assertTrue(restored.messages.value.any { it.content.contains("Write and read back") })
            passed("event snapshot retains tool history and watermark; released VM restores one new instance from Room")

            provider.mode = "wait"
            provider.gate = CompletableDeferred()
            provider.waiting = false
            val independent = withContext(Dispatchers.Main) { AgentRunner.submit(restored.agentSession, "Execution survives UI scope cancellation") }
            until { provider.waiting }
            ui { restored.viewModelScope.cancel() }
            assertTrue(restored.execution.currentJob!!.isActive)
            provider.gate.complete(Unit)
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Completed, independent.result.await())
            assertTrue(app.chatRepository.dao.loadMessages(session.id).last { it.role == "assistant" }.partsJson.contains("Held answer"))
            passed("execution scope completes and persists a turn independently of the ViewModel scope")

            provider.gate = CompletableDeferred()
            provider.waiting = false
            val released = withContext(Dispatchers.Main) { AgentRunner.submit(restored.agentSession, "Release running session owner") }
            until { provider.waiting }
            ui { AgentRunner.forget(session.id) }
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Cancelled, released.result.await())
            assertTrue(restored.awaitStreamExit(5_000L))
            assertTrue(restored.execution.isClosed)
            val stale = withContext(Dispatchers.Main) { AgentRunner.submit(restored.agentSession, "Do not resurrect released owner") }
            assertEquals(com.openminis.app.agent.AgentTurnOutcome.Rejected("session_closed"), stale.result.await())
            report.put("snapshotWatermark", snapshot.lastSeq).put("modelCalls", provider.calls)
            passed("session release closes execution and rejects stale-owner submissions")
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString())
            throw error
        } finally {
            provider.releaseCleanup.complete(Unit)
            provider.gate.complete(Unit)
            withContext(Dispatchers.Main) { AgentRunner.forget(session.id) }
            SessionEventHub.clear(session.id)
            SessionPermissionStore.setPreset(app, session.id, null)
            app.chatRepository.deleteSession(session.id)
            artifact.writeText(report.toString(2))
        }
    }

    private class ControlledProvider : LLMProvider {
        override val name = "Agent core controlled provider"
        override var model = LLMModel("core-scripted", "Core scripted", "scripted", contextWindow = 32768)
        var mode = "files"
        var deniedPath = ""
        var gate = CompletableDeferred<Unit>()
        @Volatile var holdCleanup = false
        var cleanupStarted = CompletableDeferred<Unit>()
        var releaseCleanup = CompletableDeferred<Unit>()
        @Volatile var waiting = false
        @Volatile var calls = 0
        @Volatile var requests = listOf<List<LLMMessage>>()
        private var fileStep = 0
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel) =
            LLMResponse("Core test title", "stop", null)
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
            temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>, thinkingLevel: ThinkingLevel): Flow<LLMStreamChunk> = flow {
            calls++
            requests = requests + listOf(messages.toList())
            val last = messages.last().contentParts.filterIsInstance<AgentContentPart.ToolResult>()
            val chunks = when (mode) {
                "files" -> when (++fileStep) {
                    1 -> toolCall("core-write", toolName(tools, "linux.file.write", "file_write", "write"),
                        JSONObject().put("path", "/workspace/core-result.txt").put("content", "Minis core artifact\n").put("tool_title", "Write artifact"))
                    2 -> toolCall("core-read", toolName(tools, "linux.file.read", "file_read", "read"),
                        JSONObject().put("path", "/workspace/core-result.txt").put("tool_title", "Read artifact"))
                    else -> text("Artifact verified")
                }
                "deny" -> if (last.any { it.id == "core-denied" }) text("External write refused") else toolCall("core-denied",
                    toolName(tools, "linux.file.write", "file_write", "write"), JSONObject().put("path", deniedPath)
                        .put("content", "must not write").put("tool_title", "Denied write"))
                "time" -> if (last.any { it.id == "core-time" }) text("Phone time checked") else toolCall("core-time", "android.time")
                "wait", "partial-wait" -> {
                    val ownedGate = gate
                    val hold = holdCleanup
                    if (mode == "partial-wait") emit(LLMStreamChunk.Text("Partial before stop"))
                    waiting = true
                    try { ownedGate.await(); text("Held answer") }
                    finally {
                        if (hold) withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                "failure" -> throw IllegalStateException("controlled_core_failure")
                "partial" -> listOf(LLMStreamChunk.Text("Unfinished output"))
                "length" -> listOf(LLMStreamChunk.Text("Limited output"), LLMStreamChunk.Finished("length"))
                else -> text("Done")
            }
            chunks.forEach { emit(it) }
        }
        private fun toolName(tools: List<AgentToolDefinition>, vararg names: String) = tools.first { it.name in names }.name
    }
}
