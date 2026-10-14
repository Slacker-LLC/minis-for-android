package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.backup.*
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real backup archive/integrity/import, restored checkpoints, human decisions and task budgets. */
@RunWith(AndroidJUnit4::class)
class AgentRecoveryDeliveryE2ETest {
    @Test fun restorationAndHumanVerificationPreserveWork(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sid = app.chatRepository.createSession("scripted-model", title = "M2 M4 restoration", memoryEnabled = false).id
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val files = mutableListOf<File>()
        var taskId: String? = null
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun answer(value: String) {
            val pending = withTimeout(10_000) { while (QuestionCenter.pendingFor(sid).isEmpty()) delay(20); QuestionCenter.pendingFor(sid).single() }
            QuestionCenter.answer(pending.id, QuestionAnswer(selected = listOf(value)))
        }
        try {
            val task = app.botTaskRepository.setSessionGoal(sid, "核实备份现场，不能重复写入", maxRuns = 5)!!
            taskId = task.id
            app.database.botTaskDao().replace(task.copy(autoRunsUsed = 2, delegationsUsed = 1))
            WorkspaceFileClient.writeBytes(sid, "/workspace/recovery.txt", "one retained effect\n".toByteArray())
            val intent = ToolCheckpointStore.recordIntent(app, sid, "recover-call", "file_write",
                JSONObject().put("path", "/workspace/recovery.txt").put("content", "one retained effect\n").put("append", true).toString(),
                "original-run", task.id, task.revision, task.stopGeneration)!!
            val queued = app.chatRepository.appendMessage(sid, "user", JSONArray().put(JSONObject().put("type", "text").put("value", "Imported input requires review")).toString(),
                requestState = "PENDING", requestDelivery = "QUEUE", requestUserInput = true)
            val archive = BackupExporter(app, app.database).export(BackupExporter.Options(categories = setOf(BackupCategory.CHATS), includeCredentials = false))
            files += archive.packageFile
            val unpacked = File(app.cacheDir, "m4-restore-$sid").apply { mkdirs() }; files += unpacked
            BackupZip.extract(archive.packageFile, unpacked)
            val root = BackupZip.packageRoot(unpacked)
            assertTrue(File(root, "data/agent_tasks.jsonl").readText().contains(task.id))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM bot_tasks WHERE id = ?", arrayOf(task.id))
            app.chatRepository.dao.deleteMessage(queued.id)
            File(app.filesDir, "checkpoints/$sid.jsonl").delete()
            val imported = BackupImporter(app, app.database).import(root, BackupImporter.Options(categories = setOf(BackupCategory.CHATS)))
            assertTrue(imported.integrityFailed.isEmpty())
            assertNull(imported.categories.single { it.category == BackupCategory.CHATS.key }.failed)
            val restored = app.botTaskRepository.get(task.id)!!
            assertEquals(BotTaskEntity.STATUS_PAUSED, restored.status)
            assertEquals(2, restored.autoRunsUsed); assertEquals(1, restored.delegationsUsed)
            assertEquals(task.stopGeneration + 1, restored.stopGeneration)
            assertTrue(JSONObject(restored.completionEvidence!!).getJSONObject("restoration").getBoolean("requires_review"))
            assertEquals("NEEDS_REVIEW", app.chatRepository.dao.getMessageById(queued.id)?.requestState)
            assertEquals(false, app.chatRepository.dao.getMessageById(queued.id)?.requestUserInput)
            assertEquals(intent, ToolOutcomeVerification.pending(app, sid).single().intentId)
            assertNull(AgentSessionStore.activeRun(sid))
            passed("verified real archive restores paused task budgets, exact unknown intent and unclaimed input requiring authorization")
            assertTrue(runCatching { app.database.botTaskDao().resumeReviewedTask(restored) }.isFailure)
            val review = async { runCatching { ToolOutcomeVerification.reviewRestoreFromUi(app, sid, restored) } }
            answer("reviewed")
            assertTrue(review.await().isSuccess)
            assertEquals(BotTaskEntity.STATUS_PAUSED, app.botTaskRepository.get(task.id)?.status)
            assertEquals(intent, ToolOutcomeVerification.pending(app, sid).single().intentId)
            passed("native restoration review never starts a run, clears unknown actions or resets the task budget")
            val pending = ToolOutcomeVerification.pending(app, sid).single()
            val unsure = async { runCatching { ToolOutcomeVerification.observeFromUi(app, sid, pending) } }
            answer("unknown"); assertTrue(unsure.await().isFailure)
            assertEquals(intent, ToolOutcomeVerification.pending(app, sid).single().intentId)
            val checked = async { ToolOutcomeVerification.observeFromUi(app, sid, pending) }
            answer("completed"); checked.await()
            assertTrue(ToolOutcomeVerification.pending(app, sid).isEmpty())
            assertEquals(BotTaskEntity.STATUS_PAUSED, app.botTaskRepository.get(task.id)?.status)
            assertEquals("one retained effect\n", WorkspaceFileClient.readAll(sid, "/workspace/recovery.txt").decodeToString())
            assertTrue(app.chatRepository.dao.loadMessages(sid).any { it.partsJson.contains("native_user") && it.partsJson.contains(intent) })
            passed("human uncertainty retains UNKNOWN; exact confirmed observation commits evidence before ACK without replay or task resume")
            BackupImporter(app, app.database).import(root, BackupImporter.Options(categories = setOf(BackupCategory.CHATS)))
            assertTrue(ToolOutcomeVerification.pending(app, sid).isEmpty())
            assertEquals(BotTaskEntity.STATUS_PAUSED, app.botTaskRepository.get(task.id)?.status)
            passed("reimport cannot replace an existing reviewed task or revive its settled unknown action")
            report.put("archiveSha256", com.openminis.app.util.Sha256.hex(archive.packageFile))
                .put("taskId", task.id).put("executionId", intent).put("replayedWrites", 0).put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.stackTraceToString()); throw error }
        finally {
            File(app.filesDir, "agent-recovery-delivery-e2e.json").writeText(report.toString(2))
            withContext(Dispatchers.Main) { AgentSessionStore.closeAndAwait(sid); AgentSessionStore.release(sid) }
            app.chatRepository.deleteSession(sid)
            taskId?.let { app.database.openHelper.writableDatabase.execSQL("DELETE FROM bot_tasks WHERE id = ?", arrayOf(it)) }
            files.forEach { it.deleteRecursively() }
        }
    }
}
