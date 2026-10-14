package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.TaskArtifacts
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.SafeFileTree
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual model → file/verified Goal → Room history → source-bound projection/current hash check. */
@RunWith(AndroidJUnit4::class)
class AgentArtifactsE2ETest {
    @Test
    fun keepHistoricalArtifactsBoundToTheirRealSource(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "artifact-e2e-${UUID.randomUUID()}"
        val sessions = mutableListOf<String>()
        val tasks = mutableListOf<String>()
        val dao = app.database.botTaskDao()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "artifacts-scripted" }
        }
        suspend fun complete(sid: String, path: String, content: String): BotTaskEntity {
            val prefix = "artifact-${UUID.randomUUID()}"
            var identity: JSONObject? = null
            var finalReceipt: AgentContentPart.ToolResult? = null
            prepare(sid, ScriptedProvider { call, history, _ ->
                fun receipt(id: String) = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().lastOrNull { it.id == id }
                when (call) {
                    1 -> toolCall("$prefix-create", "create_goal", JSONObject().put("goal", "$marker $path").put("max_goal_rounds", 2))
                    2 -> {
                        val created = receipt("$prefix-create")
                        if (created == null || created.isError) text("无法建立任务。") else {
                            identity = JSONObject(created.content)
                            toolCall("$prefix-write", "file_write", JSONObject().put("path", path).put("content", content))
                        }
                    }
                    3 -> toolCall("$prefix-finish", "update_goal", JSONObject().put("status", "completed")
                        .put("task_id", identity!!.getString("task_id")).put("revision", identity!!.getInt("revision"))
                        .put("stop_generation", identity!!.getInt("stop_generation")).put("progress", "已核验实际文件。")
                        .put("evidence", JSONArray().put(JSONObject().put("type", "file").put("path", path))))
                    else -> { finalReceipt = receipt("$prefix-finish"); text("任务已交付。") }
                }
            })
            assertTrue(AgentRunner.prompt(app, sid, "写文件并核验完成目标", wait = true, timeoutMs = 20_000).completed)
            assertNotNull(finalReceipt); assertFalse(finalReceipt!!.content, finalReceipt!!.isError)
            val task = dao.get(identity!!.getString("task_id"))!!
            tasks.add(task.id)
            assertEquals(BotTaskEntity.STATUS_COMPLETED, task.status)
            return task
        }
        try {
            val source = session(); val other = session()
            val path = "/workspace/same-name.txt"
            val originalContent = "verified original source\n"
            val first = complete(source, path, originalContent)
            val proof = JSONObject(first.completionEvidence!!)
            assertEquals(source, proof.getString("session_id"))
            assertEquals(first.id, proof.getString("task_id"))
            assertTrue(proof.getLong("verified_at") > 0)
            val reference = TaskArtifacts.page(app, source).references.single { it.taskId == first.id }
            assertEquals(source, reference.sessionId); assertFalse(reference.inferredSource)
            assertEquals(TaskArtifacts.State.VERIFIED, TaskArtifacts.verify(app, reference).state)
            passed("actual model completion persists the server-owned source session and real file SHA in existing Room evidence")
            prepare(other, ScriptedProvider { call, _, _ -> if (call == 1) toolCall("other-write", "file_write", JSONObject()
                .put("path", path).put("content", "different session with same filename\n")) else text("已写入独立会话。") })
            assertTrue(AgentRunner.prompt(app, other, "写独立测试文件", wait = true, timeoutMs = 15_000).completed)
            assertEquals("different session with same filename\n", WorkspaceFileClient.readAll(other, path).decodeToString())
            val bound = TaskArtifacts.verify(app, reference)
            assertEquals(TaskArtifacts.State.VERIFIED, bound.state); assertEquals(reference.expectedSha256, bound.sha256)
            assertEquals(originalContent, WorkspaceFileClient.readAll(reference.sessionId, reference.path).decodeToString())
            assertTrue(TaskArtifacts.page(app, other).references.none { it.taskId == first.id })
            passed("same-name files in another actual session cannot replace the recorded source or enter its session-scoped artifact list")
            app.botTaskRepository.clearSessionGoal(source)
            assertNull(app.botTaskRepository.sessionGoal(source))
            val afterClear = dao.get(first.id)!!
            assertEquals(BotTaskEntity.STATUS_COMPLETED, afterClear.status)
            assertEquals(first.completionEvidence, afterClear.completionEvidence)
            assertEquals(reference, TaskArtifacts.page(app, source).references.single { it.taskId == first.id })
            passed("clearing the active Goal retains the historical reference with its old proof generation and exact source")
            val second = complete(source, "/workspace/second.txt", "second verified artifact\n")
            app.botTaskRepository.clearSessionGoal(source)
            val pageOne = dao.completedEvidence(source, 1, 0); val pageTwo = dao.completedEvidence(source, 1, 1)
            assertEquals(setOf(first.id, second.id), (pageOne + pageTwo).map { it.id }.toSet())
            assertEquals(2, TaskArtifacts.page(app, source).references.size)
            passed("actual Room pagination includes separate completed goals after their active bindings have been cleared")
            WorkspaceFileClient.writeBytes(source, path, "changed after delivery\n".toByteArray())
            val changed = TaskArtifacts.verify(app, reference)
            assertEquals(TaskArtifacts.State.CHANGED, changed.state); assertNotEquals(reference.expectedSha256, changed.sha256)
            assertEquals(first.completionEvidence, dao.get(first.id)!!.completionEvidence)
            assertEquals(BotTaskEntity.STATUS_COMPLETED, dao.get(first.id)!!.status)
            passed("a real post-delivery edit changes current availability without rewriting historical completion or evidence")
            WorkspaceFileClient.delete(source, path)
            assertEquals(TaskArtifacts.State.UNAVAILABLE, TaskArtifacts.verify(app, reference).state)
            assertEquals(BotTaskEntity.STATUS_COMPLETED, dao.get(first.id)!!.status)
            WorkspaceFileClient.writeBytes(source, path, originalContent.toByteArray())
            assertEquals(TaskArtifacts.State.VERIFIED, TaskArtifacts.verify(app, reference).state)
            passed("actual deletion is unavailable and restoring the original bytes validates again against the same historical proof")
            val legacy = JSONObject(first.completionEvidence!!).apply { remove("session_id") }.toString()
            val preserved = dao.get(first.id)!!
            dao.replace(preserved.copy(completionEvidence = legacy))
            val legacyRef = TaskArtifacts.page(app, source).references.single { it.taskId == first.id }
            assertTrue(legacyRef.inferredSource); assertEquals(source, legacyRef.sessionId)
            assertEquals(TaskArtifacts.State.VERIFIED, TaskArtifacts.verify(app, legacyRef).state)
            assertTrue(runCatching { dao.bindRunOwner(first.id, preserved.revision, preserved.stopGeneration, other) }.isFailure)
            passed("legacy evidence uses its preserved completed owner, and the real DAO refuses to rebind that terminal task")
            dao.replace(preserved.copy(completionEvidence = legacy, currentOwnerSessionId = null))
            val missingSource = TaskArtifacts.page(app, source).references.single { it.taskId == first.id }
            assertNull(missingSource.sessionId)
            assertEquals(TaskArtifacts.State.UNAVAILABLE, TaskArtifacts.verify(app, missingSource).state)
            assertEquals(originalContent, WorkspaceFileClient.readAll(source, path).decodeToString())
            passed("legacy proof with no source owner does not guess the origin or current page workspace")
            val corrupt = JSONObject(first.completionEvidence!!)
            corrupt.getJSONArray("references").getJSONObject(0).put("sha256", "not-a-verified-hash")
            dao.replace(preserved.copy(completionEvidence = corrupt.toString()))
            assertTrue(TaskArtifacts.page(app, source).references.none { it.taskId == first.id })
            dao.replace(preserved)
            passed("malformed imported hash evidence is not published as a verified file reference")
            val deliveryBytes = WorkspaceFileClient.readAll(source, path)
            File(app.filesDir, "agent-artifacts-delivery.txt").writeBytes(deliveryBytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(deliveryBytes).joinToString("") { "%02x".format(it) })
            app.chatRepository.deleteSession(source)
            assertEquals(TaskArtifacts.State.UNAVAILABLE, TaskArtifacts.verify(app, reference).state)
            assertEquals(BotTaskEntity.STATUS_COMPLETED, dao.get(first.id)!!.status)
            assertNull(AgentSessionStore.existing(source))
            assertFalse(SafeFileTree.existsNoFollow(File(UbuntuPaths.hostSessions, source)))
            passed("deleted original Room session leaves historical completion intact and verification creates no owner or replacement directory")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            tasks.forEach { id -> app.database.openHelper.writableDatabase.execSQL("DELETE FROM bot_tasks WHERE id = ?", arrayOf(id)) }
            File(app.filesDir, "agent-artifacts-e2e.json").writeText(report.toString(2))
        }
    }
}
