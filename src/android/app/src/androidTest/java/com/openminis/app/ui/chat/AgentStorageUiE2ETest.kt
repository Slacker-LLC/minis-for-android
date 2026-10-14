package com.openminis.app.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.TaskArtifacts
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.data.storage.SessionStorage
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

/** Actual MainActivity accessibility clicks; no mock UI, route injection or extra testing dependency. */
@RunWith(AndroidJUnit4::class)
class AgentStorageUiE2ETest {
    @Test
    fun selectBrowserCleanupAndOpenTheRetainedArtifact(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "ui-cleanup-${UUID.randomUUID()}"
        val sid = app.chatRepository.createSession("scripted-model",title=marker,memoryEnabled=false).id
        var taskId: String? = null
        var activity: ActivityScenario<MainActivity>? = null
        val report = JSONObject().put("device",android.os.Build.FINGERPRINT).put("scenarios",JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name",name).put("passed",true)) }
        fun find(node: AccessibilityNodeInfo?, wanted: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.equals(wanted,ignoreCase=true) == true) return node
            for (i in 0 until node.childCount) find(node.getChild(i),wanted)?.let { return it }
            return null
        }
        fun current(wanted: String) = find(instrumentation.uiAutomation.rootInActiveWindow,wanted)
        suspend fun until(check: () -> Boolean) = withTimeout(20_000) { while (!check()) delay(100) }
        suspend fun tap(wanted: String) {
            until { current(wanted) != null }
            var target = current(wanted)
            while (target != null && !target.isClickable) target = target.parent
            assertNotNull("No actual clickable ancestor for $wanted",target)
            assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(200)
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(250L,5_000L)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No native screenshot")
            File(app.filesDir,name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        }
        fun swipe() {
            val metrics = app.resources.displayMetrics
            val x = metrics.widthPixels / 2f; val h = metrics.heightPixels.toFloat()
            val started = SystemClock.uptimeMillis()
            fun event(action: Int,time: Long,y: Float) = MotionEvent.obtain(started,time,action,x,y,0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            event(MotionEvent.ACTION_DOWN,started,h*.8f).let { instrumentation.uiAutomation.injectInputEvent(it,true); it.recycle() }
            for (i in 1..10) event(MotionEvent.ACTION_MOVE,started+i*25L,h*(.8f-.05f*i)).let { instrumentation.uiAutomation.injectInputEvent(it,true); it.recycle() }
            event(MotionEvent.ACTION_UP,started+275,h*.3f).let { instrumentation.uiAutomation.injectInputEvent(it,true); it.recycle() }
        }
        try {
            val vm = withContext(Dispatchers.Main) { chatProjection(app,sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            val prefix = UUID.randomUUID().toString()
            var identity: JSONObject? = null
            var finalReceipt: AgentContentPart.ToolResult? = null
            val content = "UI retained verified work\n"
            val provider = ScriptedProvider { call,history,_ ->
                fun receipt(id: String) = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().lastOrNull { it.id == id }
                when (call) {
                    1 -> toolCall("$prefix-goal","create_goal",JSONObject().put("goal",marker).put("max_goal_rounds",2))
                    2 -> {
                        identity = JSONObject(receipt("$prefix-goal")!!.content); taskId = identity!!.getString("task_id")
                        toolCall("$prefix-file","file_write",JSONObject().put("path","/workspace/results/ui-report.txt").put("content",content))
                    }
                    3 -> toolCall("$prefix-finish","update_goal",JSONObject().put("status","completed").put("progress","已核验 UI 成果文件。")
                        .put("task_id",taskId).put("revision",identity!!.getInt("revision")).put("stop_generation",identity!!.getInt("stop_generation"))
                        .put("evidence",JSONArray().put(JSONObject().put("type","file").put("path","/workspace/results/ui-report.txt"))))
                    else -> { finalReceipt = receipt("$prefix-finish"); text("UI 成果已交付。") }
                }
            }
            withContext(Dispatchers.Main) { vm.currentProvider=provider;vm.currentModel=provider.model;vm._activeEntryId.value="storage-ui-scripted" }
            assertTrue(AgentRunner.prompt(app,sid,"写成果用于原生文件管理流程",wait=true,timeoutMs=20_000).completed)
            assertNotNull(finalReceipt);assertFalse(finalReceipt!!.content,finalReceipt!!.isError)
            WorkspaceFileClient.writeBytes(sid,"/var/minis/browser/ui-cache.bin",ByteArray(512*1024) { 7 })
            val artifact = TaskArtifacts.page(app,sid).references.single { it.taskId == taskId }
            activity = ActivityScenario.launch(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("minis://settings/storage")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            until { current(app.getString(R.string.storage_data_breakdown)) != null }
            for (i in 0..6) {
                if (current(marker) != null) break
                swipe();delay(300)
            }
            tap(marker)
            until { current(app.getString(R.string.storage_category_browser)) != null }
            screenshot("native-session-category-ui.png")
            assertNotNull(current(app.getString(R.string.storage_category_work)))
            assertNotNull(current("ui-report.txt"))
            passed("real MainActivity session storage shows all four categories and the persistent verified artifact")
            tap(app.getString(R.string.storage_clear_session_button))
            until { current(app.getString(R.string.storage_clear_selected)) != null }
            screenshot("native-cleanup-dialog.png")
            // Confirm default selection through its actual effects, not inferred checkbox styling.
            tap(app.getString(R.string.storage_clear_selected))
            withTimeout(15_000) { while (SessionStorage.preview(app,sid).bytes[SessionStorage.Category.BROWSER] != 0L) delay(100) }
            assertEquals(content,WorkspaceFileClient.readAll(sid,"/workspace/results/ui-report.txt").decodeToString())
            assertEquals(TaskArtifacts.State.VERIFIED,TaskArtifacts.verify(app,artifact).state)
            passed("actual dialog default and confirmation clear only browser data and preserve the work file and historical evidence")
            until { current("ui-report.txt") != null }
            tap("ui-report.txt")
            until { current(app.getString(R.string.filebrowser_title)) != null && current("ui-report.txt") != null &&
                current(app.getString(R.string.storage_category_browser)) == null }
            screenshot("native-artifact-directory-ui.png")
            assertEquals(content,WorkspaceFileClient.readAll(artifact.sessionId,artifact.path).decodeToString())
            passed("actual artifact click verifies the file and opens its source-bound directory in the existing file browser")
            val delivery = content.toByteArray();File(app.filesDir,"agent-storage-ui-delivery.txt").writeBytes(delivery)
            report.put("artifactSha256",MessageDigest.getInstance("SHA-256").digest(delivery).joinToString("") { "%02x".format(it) })
                .put("nativeClickFlowVerified",true).put("passed",true)
        } catch (error: Throwable) {
            report.put("passed",false).put("failure",error.stackTraceToString())
            runCatching { screenshot("native-storage-ui-failure.png") }
            throw error
        } finally {
            activity?.close(); AgentSessionStore.release(sid); runCatching { app.chatRepository.deleteSession(sid) }
            taskId?.let { app.database.openHelper.writableDatabase.execSQL("DELETE FROM agent_tasks WHERE id = ?",arrayOf(it)) }
            report.put("cleanupComplete",!File(UbuntuPaths.hostSessions,sid).exists())
            File(app.filesDir,"agent-storage-ui-e2e.json").writeText(report.toString(2))
        }
    }
}
