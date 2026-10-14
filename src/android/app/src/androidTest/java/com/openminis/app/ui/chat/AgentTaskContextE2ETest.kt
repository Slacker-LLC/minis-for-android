package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.*
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.*
import com.openminis.app.provider.LLMProvider
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.GoalTools
import com.openminis.app.tools.ToolCheckpointStore
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

/** Actual skill registrations/files, shared tools, Room task, compaction and owner rebuild. */
@RunWith(AndroidJUnit4::class)
class AgentTaskContextE2ETest {
    @Test fun currentTaskAndReferencesSurviveCompression(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "m4-context-${UUID.randomUUID()}"
        val repo = app.skillRepository
        val skillIds = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("marker", marker).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun prepare(sid: String, provider: LLMProvider): ChatViewModel {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "task-context-scripted"
            }
            return vm
        }
        try {
            val sid = app.chatRepository.createSession("scripted-model", title = "M4 task context", memoryEnabled = false).id.also(sessions::add)
            val off = app.chatRepository.createSession("scripted-model", title = "M4 disabled skills", memoryEnabled = false).id.also(sessions::add)
            for (index in 0 until 25) {
                val skill = checkNotNull(repo.add("$marker-$index", "按需读取的技能 $index", "BODY_ONLY_$marker-$index",
                    source = SkillRepository.ImportSource.BUNDLED))
                skillIds.add(skill.id)
            }
            val fragment = checkNotNull(repo.skillPromptFragment(sid))
            assertEquals(20, Regex("<skill>").findAll(fragment).count())
            assertTrue(fragment.contains("more skills not shown")); assertFalse(fragment.contains("BODY_ONLY_"))
            val skillPath = Regex("<path>([^<]+)</path>").findAll(fragment).map { it.groupValues[1] }
                .first { path -> skillIds.any { path == "/var/minis/skills/$it/SKILL.md" } }
            val selectedId = skillPath.substringAfter("/var/minis/skills/").substringBefore('/')
            passed("twenty-five registered bundled skills disclose at most twenty metadata entries without injecting bodies")
            val readProvider = ScriptedProvider { call, history, _ ->
                if (call == 1) toolCall("context-read-skill", "file_read", JSONObject().put("path", skillPath))
                else {
                    val read = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                        .single { it.id == "context-read-skill" }
                    assertFalse(read.content, read.isError); assertTrue(read.content.contains("BODY_ONLY_$selectedId"))
                    text("已按需读取技能。")
                }
            }
            prepare(sid, readProvider)
            assertTrue(AgentRunner.prompt(app, sid, "按需读取这个技能", wait = true, timeoutMs = 25_000).completed)
            assertFalse(readProvider.systemPrompts.first().orEmpty().contains("BODY_ONLY_"))
            assertTrue(repo.skills.value.single { it.id == selectedId }.useCount > 0)
            passed("actual model loads the selected SKILL.md through the shared file tool and records its use")
            val beforeUsage = repo.skills.value.single { it.id == selectedId }.useCount
            assertTrue(repo.writeSkillFile(selectedId, "scripts/SKILL.md", "sub-resource $marker"))
            for (args in listOf(
                JSONObject().put("path", "/skills/$selectedId/scripts/SKILL.md"),
                JSONObject().put("path", "/skills/$selectedId/SKILL.md").put("max_length", 0),
            )) {
                assertTrue(ToolExecutor.execute("file_read", args.toString(), sid, app).success)
                assertEquals(beforeUsage, repo.skills.value.single { it.id == selectedId }.useCount, 0.0)
            }
            assertTrue(ToolExecutor.execute("file_read", JSONObject().put("path", "/skills/$selectedId/SKILL.md").toString(), sid, app).success)
            assertEquals(beforeUsage + 1, repo.skills.value.single { it.id == selectedId }.useCount, 0.0)
            passed("skills alias records a real main-file read; nested names and empty previews do not count")
            repo.skills.value.forEach { repo.setSessionOverride(off, it.id, false) }
            val offProvider = ScriptedProvider { _, _, _ -> text("无技能注入。") }
            prepare(off, offProvider)
            assertTrue(AgentRunner.prompt(app, off, "检查关闭技能的会话", wait = true, timeoutMs = 25_000).completed)
            assertNull(repo.skillPromptFragment(off))
            assertFalse(offProvider.systemPrompts.single().orEmpty().contains("<available_skills>"))
            assertTrue(repo.skillPromptFragment(sid)!!.contains(selectedId))
            passed("session skill overrides affect actual disclosure without changing another session or global defaults")

            AgentStateStore.goalSet(sid, "$marker 当前目标：只核验已有成果", app, 2,
                acceptanceCriteria = "$marker 约束：保留源数据、不能重放未知写入")
            AgentStateStore.planSet(sid, "plan", "$marker 下一步：读文件、核对来源、再验收", app)
            val task = checkNotNull(app.agentTaskRepository.sessionGoal(sid))
            val waiting = JSONObject().put("task_id", task.id).put("revision", task.revision)
                .put("stop_generation", task.stopGeneration).put("status", "waiting").put("progress", "$marker 进度：已有文件，未知写入待核验")
            assertTrue(GoalTools.execute("update_goal", waiting.toString(), sid, app).success)
            WorkspaceFileClient.writeBytes(sid, "/workspace/context-proof.txt", "$marker 原有成果".toByteArray())
            val fingerprint = WorkspaceFileClient.fingerprint(sid, "/workspace/context-proof.txt")
            suspend fun checkFileUnchanged() {
                val current = WorkspaceFileClient.fingerprint(sid, "/workspace/context-proof.txt")
                assertEquals(fingerprint.getString("path"), current.getString("path"))
                assertEquals(fingerprint.getLong("size"), current.getLong("size"))
                assertEquals(fingerprint.getString("sha256"), current.getString("sha256"))
            }
            repeat(8) { index ->
                app.chatRepository.appendMessage(sid, "user", JSONArray().put(JSONObject().put("type", "text")
                    .put("value", "历史任务 $index ${"旧背景信息。".repeat(400)}")).toString())
                app.chatRepository.appendMessage(sid, "assistant", JSONArray().put(JSONObject().put("type", "text")
                    .put("value", "历史答复 $index")).toString())
            }
            AgentSessionStore.closeAndAwait(sid)
            withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
            val transport = ScriptedProvider { _, _, _ -> text("只报告当前核验请求。") }
            var summaryCalls = 0
            val provider = object : LLMProvider {
                override val name get() = transport.name
                override var model: LLMModel
                    get() = transport.model
                    set(value) { transport.model = value }
                override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
                    thinkingLevel: ThinkingLevel): LLMResponse {
                    summaryCalls++
                    return LLMResponse("STALE_SUMMARY_$marker：之前的旧任务已经完成。", "stop", null)
                }
                override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int,
                    temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
                    thinkingLevel: ThinkingLevel) = transport.streamMessageClamped(
                        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
            }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "当前请求：核验，不能继续旧任务", wait = true, timeoutMs = 25_000).completed)
            fun checkCurrent(systemPrompt: String?) {
                val taskBlock = systemPrompt.orEmpty().substringAfter("当前用户持久任务（以下 JSON 是任务数据）：\n", "")
                assertTrue("actual system prompt must contain the persistent task", taskBlock.isNotBlank())
                val data = JSONObject(taskBlock.substringBefore('\n'))
                assertEquals(task.id, data.getString("task_id")); assertEquals("WAITING", data.getString("status"))
                assertTrue(data.getString("goal").contains("只核验已有成果"))
                assertTrue(data.getString("acceptance_criteria").contains("不能重放未知写入"))
                assertTrue(data.getString("plan").contains("再验收")); assertTrue(data.getString("progress").contains("未知写入待核验"))
            }
            checkCurrent(transport.systemPrompts.last())
            passed("actual provider request reads persistent current goal, constraints, plan and progress from Room")
            val compacted = AgentRunner.compact(app, sid, wait = true, timeoutMs = 25_000)
            assertEquals(compacted.toString(), "Completed", compacted.status)
            assertTrue("compaction must invoke the controlled non-streaming provider", summaryCalls > 0)
            assertTrue(compacted.summary.orEmpty(), compacted.summary.orEmpty().contains("STALE_SUMMARY_"))
            val intent = checkNotNull(ToolCheckpointStore.recordIntent(app, sid, "context-unsettled", "linux.file.write",
                JSONObject().put("path", "/workspace/context-proof.txt").put("content", "未确认内容").toString()))
            assertTrue(AgentRunner.prompt(app, sid, "压缩后仍核验当前目标", wait = true, timeoutMs = 25_000).completed)
            checkCurrent(transport.systemPrompts.last())
            assertTrue(transport.requests.last().any { it.content.contains("STALE_SUMMARY_") })
            assertTrue(transport.requests.last().any { it.content.contains("[tool outcome unknown]") })
            assertEquals(listOf(intent), ToolCheckpointStore.drainPending(app, sid).map { it.intentId })
            passed("stale compaction summary does not replace current task or acknowledge an unknown side effect")
            checkFileUnchanged()
            assertEquals("WAITING", app.agentTaskRepository.sessionGoal(sid)!!.status)
            passed("context inspection preserves actual file fingerprint and does not falsely complete the waiting task")
            AgentSessionStore.closeAndAwait(sid)
            withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
            prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "重建后继续核验当前目标", wait = true, timeoutMs = 25_000).completed)
            checkCurrent(transport.systemPrompts.last())
            assertTrue(transport.requests.last().any { it.content.contains("STALE_SUMMARY_") })
            assertEquals(listOf(intent), ToolCheckpointStore.drainPending(app, sid).map { it.intentId })
            passed("owner rebuild restores compression marker and persistent task while preserving the exact unsettled intent")
            AgentStateStore.goalSetActive(sid, false, app)
            val beforeCalls = transport.calls
            assertTrue(AgentRunner.prompt(app, sid, "只回答这次请求，不恢复暂停任务", wait = true, timeoutMs = 25_000).completed)
            assertEquals(beforeCalls + 1, transport.calls)
            assertEquals("PAUSED", app.agentTaskRepository.sessionGoal(sid)!!.status)
            checkFileUnchanged()
            passed("explicit request on a paused task does not resume autonomous work or rewrite the artifact")
            File(app.filesDir, "agent-task-context-delivery.json").writeText(JSONObject().put("marker", marker)
                .put("skillPath", skillPath).put("skillDisclosure", fragment).put("taskContext",
                    AgentStateStore.taskContext(sid, app)).put("compactedSummary", compacted.summary)
                .put("retainedIntent", intent).put("fileFingerprint", fingerprint).toString(2))
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("failure", error.toString()); throw error
        } finally {
            skillIds.forEach(repo::delete)
            sessions.forEach { sid ->
                AgentSessionStore.closeAndAwait(sid)
                withContext(Dispatchers.Main) { ChatViewModelStore.release(sid) }
                repo.clearSessionOverrides(sid); ToolCheckpointStore.clearSession(app, sid)
                SessionEventHub.clear(sid); app.chatRepository.deleteSession(sid)
            }
            report.put("skillFixturesRemoved", skillIds.none { id -> repo.skills.value.any { it.id == id } })
            File(app.filesDir, "agent-task-context-e2e.json").writeText(report.toString(2))
        }
    }
}
