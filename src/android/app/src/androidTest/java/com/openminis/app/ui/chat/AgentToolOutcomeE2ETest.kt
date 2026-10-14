package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Model → real file effect → failure → Room/reload/nonce; faults and model transport controlled. */
@RunWith(AndroidJUnit4::class)
class AgentToolOutcomeE2ETest {
    @Test
    fun preserveUnknownEffectsAcrossPersistedReplies(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val original = ToolRegistry.handler("todo_write")!!
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", title = "M3 outcome E2E", memoryEnabled = false).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "outcome-scripted-entry" }
        }
        suspend fun model(sid: String, args: JSONObject = JSONObject().put("todos", JSONArray()), name: String = "todo_write"): AgentContentPart.ToolResult {
            var captured: AgentContentPart.ToolResult? = null
            prepare(sid, ScriptedProvider { call, history, _ -> if (call == 1) toolCall("outcome-call", name, args) else {
                captured = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().firstOrNull { it.id == "outcome-call" }
                text("保留实际回执，继续核实执行结果。")
            } })
            assertTrue(AgentRunner.prompt(app, sid, "执行并核实本次回执", wait = true, timeoutMs = 20_000).completed)
            assertNotNull(captured)
            return captured!!
        }
        fun replace(timeout: Long? = original.definition.timeoutMs, body: suspend (String) -> ToolExecutionResult) {
            ToolRegistry.register(object : ToolHandler {
                override val definition = original.definition.copy(timeoutMs = timeout)
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String) = body(sessionId)
            })
        }
        suspend fun loaded(sid: String): AgentContentPart.ToolResult = app.chatRepository.loadMessages(sid)
            .map { AgentSessionStore.existing(sid)!!.messageMapper.toLLMMessage(it) }.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "outcome-call" }
        suspend fun unknown(sid: String, live: AgentContentPart.ToolResult, kind: ToolFailureKind) {
            assertEquals(kind, live.failureKind); assertEquals(true, live.executionStarted)
            assertNotNull(live.executionIntentId)
            val restored = loaded(sid)
            assertEquals(live.failureKind, restored.failureKind); assertEquals(live.executionStarted, restored.executionStarted)
            assertEquals(live.executionIntentId, restored.executionIntentId)
            val before = ToolCheckpointStore.drainPending(app, sid).single()
            assertEquals(live.executionIntentId, before.intentId)
            val owner = AgentSessionStore.existing(sid)!!
            val persisted = app.chatRepository.loadMessages(sid).map(owner.messageMapper::toLLMMessage)
            owner.conversation.replaceHistory(persisted)
            val outgoing = owner.conversation.outgoingHistory(app, sid)
            assertTrue(outgoing.any { it.content.contains("[tool outcome unknown]") })
            assertFalse(outgoing.any { it.content.contains("died mid-execution") })
            assertEquals(before, ToolCheckpointStore.drainPending(app, sid).single())
            assertEquals("one actual effect\n", WorkspaceFileClient.readAll(sid, "/workspace/outcome.txt").decodeToString())
        }
        try {
            var bodyCalls = 0
            replace { bodyCalls++; ToolExecutionResult("not reached", true) }
            val invalidSid = session()
            val invalid = model(invalidSid, JSONObject().put("todos", "invalid array"))
            assertTrue(invalid.isError); assertEquals(ToolFailureKind.INVALID_ARGUMENTS, invalid.failureKind)
            assertEquals(false, invalid.executionStarted); assertEquals(0, bodyCalls)
            assertTrue(ToolCheckpointStore.drainPending(app, invalidSid).isEmpty())
            passed("model schema rejection has typed no-start receipt and does not enter the execution body")
            val deniedSid = session()
            val denied = OffloadPermissionManager.withUnattendedSession(deniedSid, ScheduledTaskPermissionTier.READ_ONLY) {
                model(deniedSid, JSONObject().put("path", "/workspace/forbidden.txt").put("content", "must not write"), "file_write")
            }
            assertEquals(ToolFailureKind.PERMISSION_DENIED, denied.failureKind); assertEquals(false, denied.executionStarted)
            assertFalse(WorkspaceFileClient.info(deniedSid, "/workspace/forbidden.txt").optBoolean("exists"))
            assertTrue(ToolCheckpointStore.drainPending(app, deniedSid).isEmpty())
            passed("READ_ONLY denial survives model/Room with no checkpoint or file side effect")

            val cleaned = CompletableDeferred<Unit>()
            replace(150L) { sid ->
                WorkspaceFileClient.writeBytes(sid, "/workspace/outcome.txt", "one actual effect\n".toByteArray())
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }
            val timeoutSid = session()
            val timeoutReceipt = model(timeoutSid)
            assertTrue(cleaned.isCompleted); assertTrue(timeoutReceipt.isError)
            unknown(timeoutSid, timeoutReceipt, ToolFailureKind.TOOL_TIMEOUT)
            passed("actual file effect followed by deadline and cleanup remains unknown through saved Room receipt and history inspection")

            replace { sid ->
                WorkspaceFileClient.writeBytes(sid, "/workspace/outcome.txt", "one actual effect\n".toByteArray())
                throw IOException("injected return transport failure")
            }
            val exceptionSid = session(); val exceptionReceipt = model(exceptionSid)
            unknown(exceptionSid, exceptionReceipt, ToolFailureKind.OUTCOME_UNKNOWN)
            passed("an exception after actual file write yields a paired unknown receipt and retains its exact intent instead of aborting the Agent")

            var actualGoalEffects = 0
            replace { sid ->
                actualGoalEffects++
                WorkspaceFileClient.writeBytes(sid, "/workspace/outcome.txt", "one actual effect\n".toByteArray())
                throw IOException("return failed after task effect")
            }
            val goalSid = session()
            AgentStateStore.goalSet(goalSid, "验证副作用后异常不得自动重放或完成", app, 3)
            val goalProvider = ScriptedProvider { call, _, _ -> if (call == 1)
                toolCall("goal-unknown", "todo_write", JSONObject().put("todos", JSONArray())) else text("先核实实际文件，不能重放。") }
            prepare(goalSid, goalProvider)
            assertFalse(AgentRunner.prompt(app, goalSid, "执行并核验任务", wait = true, timeoutMs = 20_000).completed)
            val stoppedGoal = app.agentTaskRepository.sessionGoal(goalSid)!!
            assertEquals("WAITING", stoppedGoal.status); assertEquals(0, stoppedGoal.autoRunsUsed)
            assertEquals(2, goalProvider.calls); assertEquals(1, actualGoalEffects)
            assertEquals("goal-unknown", ToolCheckpointStore.drainPending(app, goalSid).single().callId)
            assertEquals("one actual effect\n", WorkspaceFileClient.readAll(goalSid, "/workspace/outcome.txt").decodeToString())
            passed("an actual durable goal waits after a paired unknown result, with zero automatic continuations and only one actual side effect")

            replace { sid ->
                WorkspaceFileClient.writeBytes(sid, "/workspace/outcome.txt", "one actual effect\n".toByteArray())
                ToolExecutionResult("accepted without effect observation", true, failureKind = ToolFailureKind.OBSERVATION_FAILED)
            }
            val observationSid = session(); val observationReceipt = model(observationSid)
            assertFalse(observationReceipt.isError)
            unknown(observationSid, observationReceipt, ToolFailureKind.OBSERVATION_FAILED)
            passed("a typed observation failure retains the unknown effect even when the legacy action-accepted success flag is true")

            replace { ToolExecutionResult("timeout is mentioned in a definitive domain rejection", false) }
            val failedSid = session(); val failure = model(failedSid)
            assertEquals(ToolFailureKind.EXECUTION_FAILED, failure.failureKind); assertEquals(true, failure.executionStarted)
            assertFalse(failure.failureKind!!.outcomeUnknown); assertTrue(ToolCheckpointStore.drainPending(app, failedSid).isEmpty())
            assertEquals(failure.failureKind, loaded(failedSid).failureKind)
            passed("a definitive returned failure is settled without guessing timeout classification from its text")

            ToolRegistry.register(original)
            val successSid = session()
            val success = model(successSid, JSONObject().put("path", "/workspace/success.txt").put("content", "outcome receipt verified\n"), "file_write")
            assertFalse(success.isError); assertNull(success.failureKind); assertEquals(true, success.executionStarted)
            assertTrue(ToolCheckpointStore.drainPending(app, successSid).isEmpty())
            assertEquals(success.executionStarted, loaded(successSid).executionStarted)
            val bytes = WorkspaceFileClient.readAll(successSid, "/workspace/success.txt")
            File(app.filesDir, "agent-tool-outcome-delivery.txt").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            passed("successful output remains unchanged and its exact nonce settles after the real Room receipt")

            data class CliCase(val permission: String, val tool: String, val args: JSONObject, val command: String, val argv: List<String>)
            val pairs = listOf(
                CliCase("clipboard", "android.clipboard", JSONObject().put("action", "read"), "android-clipboard", listOf("get", "--json")),
                CliCase("calendar", "android.calendar.read", JSONObject(), "android-calendar", listOf("list")),
                CliCase("contacts", "android.contacts.search", JSONObject().put("query", "outcome-fixture"), "android-contacts", listOf("search", "outcome-fixture")),
                CliCase("location", "android.location.get", JSONObject(), "android-location", listOf("get")),
            )
            for (pair in pairs) {
                val oldLevel = OffloadPermissionManager.getLevel(pair.permission)
                try {
                    OffloadPermissionManager.setLevel(pair.permission, OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
                    val deniedCliSid = session()
                    val deniedModel = model(deniedCliSid, pair.args, pair.tool)
                    assertEquals(ToolFailureKind.PERMISSION_DENIED, deniedModel.failureKind); assertEquals(false, deniedModel.executionStarted)
                    val cli = withContext(Dispatchers.IO) { GuestCommandBridge.dispatch(pair.command, pair.argv, deniedCliSid, "/workspace", "") }
                    assertEquals(126, cli.exitCode); assertEquals("permission_denied", JSONObject(cli.output).getString("error"))
                    assertTrue(ToolCheckpointStore.drainPending(app, deniedCliSid).isEmpty())
                    passed("${pair.permission} revocation is enforced in the actual model and guest CLI entry points without fetching personal data")
                } finally { OffloadPermissionManager.setLevel(pair.permission, oldLevel) }
            }
            val invalidBrowserSid = session()
            val invalidBrowser = model(invalidBrowserSid, JSONObject().put("action", "invalid-action"), "browser_use")
            assertEquals(ToolFailureKind.INVALID_ARGUMENTS, invalidBrowser.failureKind); assertEquals(false, invalidBrowser.executionStarted)
            for (quiet in listOf(false, true)) {
                val argv = mutableListOf("--json", "{\"action\":\"invalid-action\"}")
                if (quiet) argv.add("--quiet")
                val cli = withContext(Dispatchers.IO) { GuestCommandBridge.dispatch("minis-browser-use", argv, invalidBrowserSid, "/workspace", "") }
                assertEquals(2, cli.exitCode)
                val envelope = JSONObject(cli.output)
                val error = if (quiet) envelope else envelope.getJSONObject("error")
                val facts = error.getJSONObject("execution")
                assertEquals("INVALID_ARGUMENTS", facts.getString("failure_kind")); assertFalse(facts.getBoolean("execution_started"))
                assertFalse(facts.getBoolean("outcome_unknown"))
            }
            passed("model and real browser CLI invalid input share no-start classification, including the quiet error envelope")

            val originalClipboard = ToolRegistry.handler("android.clipboard")!!
            val oldClipboardLevel = OffloadPermissionManager.getLevel("clipboard")
            try {
                OffloadPermissionManager.setLevel("clipboard", OffloadPermissionManager.PermissionLevel.BYPASS)
                ToolRegistry.register(object : ToolHandler {
                    override val definition get() = originalClipboard.definition
                    override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String): ToolExecutionResult {
                        OffloadPermissionManager.setLevel("clipboard", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
                        return originalClipboard.execute(argsJson, sessionId, context, toolId)
                    }
                })
                val revokedSid = session()
                val revoked = model(revokedSid, JSONObject().put("action", "write").put("content", "must never reach clipboard"), "android.clipboard")
                assertEquals(ToolFailureKind.PERMISSION_DENIED, revoked.failureKind); assertEquals(false, revoked.executionStarted)
                assertTrue(ToolCheckpointStore.drainPending(app, revokedSid).isEmpty())
                passed("revocation between shared permission check and actual clipboard handler is rechecked and settled as no dispatch")
            } finally { ToolRegistry.register(originalClipboard); OffloadPermissionManager.setLevel("clipboard", oldClipboardLevel) }

            val entered = CompletableDeferred<Unit>(); val cancellationCleaned = CompletableDeferred<Unit>()
            replace { sid ->
                WorkspaceFileClient.writeBytes(sid, "/workspace/outcome.txt", "one actual effect\n".toByteArray()); entered.complete(Unit)
                try { awaitCancellation() } finally { cancellationCleaned.complete(Unit) }
            }
            val cancelledSid = session()
            prepare(cancelledSid, ScriptedProvider { _, _, _ -> toolCall("cancelled-outcome", "todo_write", JSONObject().put("todos", JSONArray())) })
            val running = async { AgentRunner.prompt(app, cancelledSid, "写入后等待取消", wait = true, timeoutMs = 20_000) }
            withTimeout(10_000) { entered.await() }; assertTrue(AgentRunner.cancel(app, cancelledSid)); running.await()
            assertTrue(cancellationCleaned.isCompleted); assertTrue(AgentRunner.waitForSettle(app, cancelledSid, 5_000))
            assertEquals("cancelled-outcome", ToolCheckpointStore.drainPending(app, cancelledSid).single().callId)
            assertEquals("one actual effect\n", WorkspaceFileClient.readAll(cancelledSid, "/workspace/outcome.txt").decodeToString())
            passed("external cancellation remains cancellation, awaits cleanup and preserves the started action without inventing a timeout receipt")
            ToolRegistry.register(original)

            val server = MCPServer(app, 24674)
            val token = TokenStore.Token("outcome-${UUID.randomUUID()}", UUID.randomUUID().toString(), scope = setOf("linux.file.read"))
            TokenStore.upsert(token)
            try {
                server.start(); assertTrue(server.isRunning)
                val connection = URL("http://127.0.0.1:24674/mcp").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 5_000; connection.connectTimeout = 5_000
                    connection.setRequestProperty("Authorization", "Bearer ${token.token}"); connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", JSONObject()
                        .put("name", "linux_file_read").put("arguments", JSONObject().put("path", "/workspace/nonexistent-${UUID.randomUUID()}.txt"))).toString().toByteArray()) }
                    assertEquals(200, connection.responseCode)
                    val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
                    assertTrue(result.getBoolean("isError")); assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").isNotEmpty())
                    val facts = result.getJSONObject("structuredContent")
                    assertEquals("EXECUTION_FAILED", facts.getString("failure_kind")); assertTrue(facts.getBoolean("execution_started")); assertFalse(facts.getBoolean("outcome_unknown"))
                    assertFalse(facts.has("executionIntentId")); assertFalse(facts.has("executionRunId")); assertFalse(facts.has("executionTaskId"))
                } finally { connection.disconnect() }
            } finally { server.stop(); TokenStore.remove(token.id) }
            passed("authenticated MCP returns the same typed result facts alongside unchanged text without publishing local model execution identities")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            ToolRegistry.register(original)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            File(app.filesDir, "agent-tool-outcome-e2e.json").writeText(report.toString(2))
        }
    }
}
