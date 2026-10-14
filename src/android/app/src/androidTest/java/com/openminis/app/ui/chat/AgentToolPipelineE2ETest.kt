package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.subagents.SubAgentStore
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.remote.AgentPresetRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import com.openminis.app.tools.*
import com.openminis.app.tools.android.AndroidCapabilityResolver
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
import java.util.concurrent.atomic.AtomicBoolean

/** Real Agent/Room/file/permission workflows. Only model transport and the deadline body are controlled. */
@RunWith(AndroidJUnit4::class)
class AgentToolPipelineE2ETest {
    @Test
    fun discoverValidateExecuteAndStop(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val wasEnabled = SubAgentStore.enabled.value
        val roster = SubAgentStore.currentRoster()
        val originalTodo = checkNotNull(ToolRegistry.handler("agent.todo"))
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(source: String? = null): String = app.chatRepository.createSession("scripted-model",
            title = "M3 pipeline E2E", source = source).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "pipeline-scripted-entry"
            }
        }
        try {
            val sid = session()
            SubAgentStore.setEnabled(false)
            val withheld = AgentTools.makeAgentTools(memoryEnabled = false, supportsImageInput = false)
            assertFalse(withheld.any { ToolRegistry.canonicalName(it.name) == "agent.subagent" || it.name in setOf("memory_write", "memory_get", "read_image") })
            assertFalse(ToolRegistry.definitionsForCaller(ToolPermissionManager.CALLER_LOCAL).any { it.name == "agent.subagent" })
            val beforeSessions = app.chatRepository.dao.listSessions().size
            val disabled = ToolExecutor.execute("subagent", "{\"task\":\"must not start\"}", sid, app)
            assertFalse(disabled.success); assertTrue(disabled.output, disabled.output.contains("subagents_disabled"))
            assertEquals(beforeSessions, app.chatRepository.dao.listSessions().size)
            passed("disabled delegation is absent from model/local catalog and cannot create a child through a stale tool name")

            SubAgentStore.setEnabled(true)
            val added = SubAgentDefinition(name = "Pipeline Reviewer", description = "Review a bounded result")
            SubAgentStore.save(roster + added)
            val definition = checkNotNull(ToolRegistry.definition("agent.subagent"))
            assertTrue(definition.parameters.containsKey("task")); assertFalse(definition.parameters.containsKey("prompt"))
            assertTrue(definition.parameters.getValue("agent").enumValues.orEmpty().contains(added.name))
            passed("canonical delegation schema follows the live roster and uses the same task/action contract as model tools")
            for (source in listOf(ChatSessionEntity.SOURCE_SUB_AGENT, ChatSessionEntity.LEGACY_SOURCE_SUB_AGENT)) {
                val child = session(source)
                val denied = ToolExecutor.execute("subagent", "{\"task\":\"must not nest\"}", child, app)
                assertFalse(denied.success); assertTrue(denied.output.contains("depth_limit"))
                val probe = ScriptedProvider { _, _, tools ->
                    assertFalse(tools.any { ToolRegistry.canonicalName(it.name) == "agent.subagent" }); text("不能再次委派。")
                }
                prepare(child, probe)
                assertTrue(AgentRunner.prompt(app, child, "检查可用工具", wait = true, timeoutMs = 15_000).completed)
            }
            passed("both legacy and current child sources withhold delegation and reject a direct nested call")

            AgentPresetRegistry.applyToSession(app, sid, "minimal")
            AgentStateStore.goalSet(sid, "极简模式也能核验并交付真实文件", app, 3)
            val provider = ScriptedProvider { call, history, tools ->
                val names = tools.map { it.name }.toSet()
                assertTrue(names.containsAll(setOf("get_goal", "update_goal", "ask_user_question", "job_list", "job_output", "job_kill", "job_input")))
                assertFalse(names.contains("browser_use")); assertFalse(names.contains("subagent"))
                val identity = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                    .firstOrNull { it.id == "pipeline-goal" }?.content?.let(::JSONObject)
                when (call) {
                    1 -> toolCall("pipeline-write", "file_write", JSONObject().put("path", "/workspace/pipeline.txt").put("content", "remove:verified delivery\n"))
                    2 -> toolCall("pipeline-read", "file_read", JSONObject().put("path", "/workspace/pipeline.txt"))
                    3 -> toolCall("pipeline-edit", "file_edit", JSONObject().put("path", "/workspace/pipeline.txt")
                        .put("edits", JSONArray().put(JSONObject().put("old_text", "remove:").put("new_text", ""))))
                    4 -> toolCall("pipeline-goal", "get_goal")
                    5 -> toolCall("pipeline-complete", "update_goal", JSONObject().put("status", "completed").put("task_id", identity!!.getString("task_id"))
                        .put("revision", identity.getInt("revision")).put("stop_generation", identity.getInt("stop_generation"))
                        .put("progress", "已核验文件内容。")
                        .put("evidence", JSONArray().put(JSONObject().put("type", "file").put("path", "/workspace/pipeline.txt"))))
                    else -> text("真实文件已交付。")
                }
            }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "写入、编辑并核验交付", wait = true, timeoutMs = 20_000).completed)
            assertEquals("COMPLETED", app.botTaskRepository.sessionGoal(sid)!!.status)
            val bytes = WorkspaceFileClient.readAll(sid, "/workspace/pipeline.txt")
            assertEquals("verified delivery\n", bytes.decodeToString())
            File(app.filesDir, "agent-tool-pipeline-delivery.txt").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            passed("minimal mode reaches goal/job/ask controls and completes real file editing and verified task delivery")

            val target = session()
            AgentStateStore.goalSet(target, "动作身份不能被别名参数替换", app)
            val task = app.botTaskRepository.sessionGoal(target)!!
            val mismatch = ToolExecutor.execute("get_goal", "{\"action\":\"update_goal\",\"goal\":\"unauthorized replacement\"}", target, app)
            assertFalse(mismatch.success); assertTrue(mismatch.output.contains("action_alias_mismatch"))
            assertEquals(task, app.botTaskRepository.sessionGoal(target))
            assertFalse(ToolExecutor.execute("get_goal", "not JSON", target, app).success)
            assertEquals(task, app.botTaskRepository.sessionGoal(target))
            passed("contradictory action aliases and malformed JSON are rejected without changing the durable task")
            val invalid = ToolExecutor.execute("file_write", "{\"path\":\"/workspace/invalid.txt\",\"content\":{}}", target, app)
            assertFalse(invalid.success); assertTrue(invalid.output.contains("invalid_arguments"))
            assertFalse(WorkspaceFileClient.info(target, "/workspace/invalid.txt").optBoolean("exists"))
            passed("the shared executor checks the published parameter schema before touching the filesystem")
            WorkspaceFileClient.writeBytes(target, "/workspace/legacy-edit.txt", "prefix:legacy delivery\n".toByteArray())
            val legacyEdit = ToolExecutor.execute("file_edit", "{\"path\":\"/workspace/legacy-edit.txt\",\"old_string\":\"prefix:\",\"new_string\":\"\"}", target, app)
            assertTrue(legacyEdit.output, legacyEdit.success)
            assertEquals("legacy delivery\n", WorkspaceFileClient.readAll(target, "/workspace/legacy-edit.txt").decodeToString())
            passed("shared validation preserves the documented legacy single-edit contract including deletion with an empty replacement")
            val readonly = OffloadPermissionManager.withUnattendedSession(target, ScheduledTaskPermissionTier.READ_ONLY) {
                ToolExecutor.execute("file_write", "{\"path\":\"/workspace/readonly.txt\",\"content\":\"denied\"}", target, app)
            }
            assertFalse(readonly.success); assertFalse(WorkspaceFileClient.info(target, "/workspace/readonly.txt").optBoolean("exists"))
            passed("READ_ONLY scope rejects a workspace mutation before any bytes are written")
            assertFalse(ToolRegistry.definitionsForCaller("mcp:e2e").any { it.name == "root.shell" })
            val remoteRoot = ToolExecutor.execute("root.shell", "{\"tool\":\"getprop\",\"args\":[]}", target, app, caller = "mcp:e2e")
            assertFalse(remoteRoot.success); assertTrue(remoteRoot.output.contains("permission_denied"))
            passed("remote MCP callers cannot discover or execute local structured Root actions")
            val token = com.openminis.app.mcp.server.TokenStore.Token("pipeline-e2e-${java.util.UUID.randomUUID()}", java.util.UUID.randomUUID().toString())
            val server = com.openminis.app.mcp.server.MCPServer(app, 24671)
            com.openminis.app.mcp.server.TokenStore.upsert(token)
            try {
                server.start(); assertTrue(server.isRunning)
                fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
                    val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
                    val connection = java.net.URL("http://127.0.0.1:24671/mcp").openConnection() as java.net.HttpURLConnection
                    try {
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                        connection.setRequestProperty("Authorization", "Bearer ${token.token}")
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(request.toString().toByteArray()) }
                        assertEquals(200, connection.responseCode)
                        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                val catalog = rpc("tools/list").getJSONObject("result").getJSONArray("tools")
                assertFalse((0 until catalog.length()).any { ToolRegistry.canonicalName(catalog.getJSONObject(it).getString("name")) == "root.shell" })
                val rejected = rpc("tools/call", JSONObject().put("name", "root_shell")
                    .put("arguments", JSONObject().put("tool", "getprop").put("args", JSONArray())))
                assertTrue(rejected.toString(), rejected.has("error") || rejected.optJSONObject("result")?.optBoolean("isError") == true)
                report.put("mcpRootRejection", rejected)
                passed("authenticated MCP HTTP discovery hides Root and an actual tools/call request is rejected before execution")
            } finally { server.stop(); com.openminis.app.mcp.server.TokenStore.remove(token.id) }


            var entered = CompletableDeferred<Unit>()
            val cleaned = AtomicBoolean(false)
            fun deadlineHandler(timeout: Long?) = object : ToolHandler {
                override val definition = originalTodo.definition.copy(timeoutMs = timeout)
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String): ToolExecutionResult {
                    try { entered.complete(Unit); awaitCancellation() } finally { cleaned.set(true) }
                }
            }
            ToolRegistry.register(deadlineHandler(50))
            val timed = ToolExecutor.execute("todo_write", "{\"todos\":[]}", target, app)
            assertFalse(timed.success); assertTrue(timed.timedOut); assertEquals(ToolFailureKind.TOOL_TIMEOUT, timed.failureKind); assertTrue(cleaned.get())
            passed("declared deadlines return TOOL_TIMEOUT and await cancellation cleanup through the shared executor")
            cleaned.set(false)
            val timeoutSid = session()
            val timedModel = ScriptedProvider { call, history, _ -> if (call == 1) toolCall("pipeline-timeout", "todo_write", JSONObject().put("todos", JSONArray())) else {
                val receipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "pipeline-timeout" }
                assertTrue(receipt.isError); assertTrue(receipt.content.contains("timed out")); assertNotNull(receipt.executionIntentId)
                assertEquals(ToolFailureKind.TOOL_TIMEOUT, receipt.failureKind)
                assertEquals(true, receipt.executionStarted)
                assertEquals(receipt.executionIntentId, ToolCheckpointStore.drainPending(app, timeoutSid).single().intentId)
                text("工具已超时且回执已保存。")
            } }
            prepare(timeoutSid, timedModel)
            assertTrue(AgentRunner.prompt(app, timeoutSid, "验证工具执行时限", wait = true, timeoutMs = 15_000).completed)
            assertTrue(cleaned.get())
            assertEquals("pipeline-timeout", ToolCheckpointStore.drainPending(app, timeoutSid).single().callId)
            passed("a model uses the same deadline, awaits cleanup and preserves the exact unresolved execution intent with its timeout receipt")
            entered = CompletableDeferred(); ToolRegistry.register(deadlineHandler(null)); cleaned.set(false)
            val cancelled = async { ToolExecutor.execute("todo_write", "{\"todos\":[]}", target, app) }
            withTimeout(5_000) { entered.await() }; cancelled.cancelAndJoin(); assertTrue(cleaned.get())
            passed("external cancellation propagates and completes tool cleanup instead of fabricating a timeout")
            val facts = AndroidCapabilityResolver.resolve(app)
            val debug = facts.getJSONObject("debug")
            for (name in listOf("logcat", "dumpsys", "meminfo")) assertTrue(debug.getJSONObject(name).getString("backend") in setOf("app", "root"))
            report.put("capabilities", facts)
            passed("diagnostic capability facts name their real Root or app backend independently of Shizuku status")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            ToolRegistry.register(originalTodo); SubAgentStore.save(roster); SubAgentStore.setEnabled(wasEnabled)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            File(app.filesDir, "agent-tool-pipeline-e2e.json").writeText(report.toString(2))
        }
    }
}
