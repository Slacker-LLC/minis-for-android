package com.openminis.app.ui.chat

import android.Manifest
import android.content.*
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.runtime.guest.*
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolSensitivePolicy
import com.openminis.app.tools.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.UUID

/** Actual model/Room/transports and platform writes; model and post-write observations controlled. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AgentAndroidOutcomeFactsE2ETest {
    @Test
    fun preserveDomainRejectionsAndExecutedOutcomes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val marker = "DomainFactsE2E-${UUID.randomUUID()}"
        val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val oldPermissions = JSONObject().also { out -> permissions.forEach {
            out.put(it, app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
        } }
        val oldLevels = listOf("calendar", "contacts", "location", "clipboard").associateWith(OffloadPermissionManager::getLevel)
        val oldHost = OffloadPermissionManager.permissionHostAttached
        val originalContacts = ToolRegistry.handler("android.contacts.manage")!!
        val originalCalendar = listOf("android.calendar.create", "android.calendar.update").associateWith { ToolRegistry.handler(it)!! }
        val sessions = mutableListOf<String>()
        val createdRawIds = mutableListOf<Long>()
        val createdCalendars = mutableListOf<Long>()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("marker", marker)
            .put("originalPermissions", oldPermissions).put("scenarios", JSONArray()).put("receipts", JSONArray())
        val reportFile = File(app.filesDir, "agent-android-outcome-facts-e2e.json")
        reportFile.writeText(report.toString(2))
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        data class ModelOutcome(val sid: String, val receipt: AgentContentPart.ToolResult)
        suspend fun model(name: String, args: JSONObject, goal: Boolean = false): ModelOutcome {
            val sid = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false).id.also(sessions::add)
            if (goal) AgentStateStore.goalSet(sid, "核对联系人副作用，禁止盲目重放", app, 3)
            var receipt: AgentContentPart.ToolResult? = null
            val provider = ScriptedProvider { call, history, _ -> if (call == 1)
                toolCall("domain-facts-invalid", name, args) else {
                receipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "domain-facts-invalid" }
                text("参数拒绝，核对真实执行事实。")
            } }
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "domain-facts-scripted" }
            val run = AgentRunner.prompt(app, sid, "执行测试记录并核对错误回执", wait = true, timeoutMs = 20_000)
            assertEquals(run.toString(), !goal, run.completed)
            val result = receipt ?: error("no paired model receipt")
            val stored = app.chatRepository.loadMessages(sid).map(AgentSessionStore.existing(sid)!!.messageMapper::toLLMMessage)
                .flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "domain-facts-invalid" }
            report.getJSONArray("receipts").put(JSONObject().put("name", result.name).put("failureKind", result.failureKind?.name)
                .put("executionStarted", result.executionStarted).put("executionId", result.executionIntentId)
                .put("storedFailureKind", stored.failureKind?.name).put("storedExecutionStarted", stored.executionStarted))
            assertEquals(ToolSensitivePolicy.RESULT_PLACEHOLDER, stored.content)
            assertEquals(result.failureKind, stored.failureKind); assertEquals(result.executionStarted, stored.executionStarted)
            assertEquals(result.executionIntentId, stored.executionIntentId)
            assertEquals(2, provider.calls)
            return ModelOutcome(sid, result)
        }
        fun rejected(outcome: ModelOutcome, kind: ToolFailureKind) {
            assertTrue(outcome.receipt.isError); assertEquals(kind, outcome.receipt.failureKind)
            assertEquals(false, outcome.receipt.executionStarted)
            assertTrue(ToolCheckpointStore.drainPending(app, outcome.sid).isEmpty())
        }
        suspend fun cli(command: String, argv: List<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
            val endpoint = GuestCommandBridge.start(app)
            fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it) }
            Socket("127.0.0.1", endpoint.port).use { socket ->
                socket.soTimeout = 10_000
                val frame = listOf("MINISCFG3", endpoint.token, command, argv.size.toString()) +
                    argv.map(::hex) + listOf(hex(sessions.first()), hex("/workspace"), "0", hex(""))
                socket.getOutputStream().write(frame.joinToString("\n", postfix = "\n").toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().use { reader -> reader.readLine().toInt() to reader.readText() }
            }
        }
        fun facts(body: String, kind: ToolFailureKind?, started: Boolean, unknown: Boolean = false): JSONObject {
            val envelope = JSONObject(body); val execution = envelope.getJSONObject("execution")
            assertEquals(kind?.name, execution.optString("failure_kind").takeUnless { execution.isNull("failure_kind") })
            assertEquals(started, execution.getBoolean("execution_started")); assertEquals(unknown, execution.getBoolean("outcome_unknown"))
            assertEquals(setOf("success", "execution_started", "failure_kind", "outcome_unknown", "timed_out", "cleanup_failed"), execution.keys().asSequence().toSet())
            return envelope
        }
        try {
            oldLevels.keys.forEach { OffloadPermissionManager.setLevel(it, OffloadPermissionManager.PermissionLevel.BYPASS) }
            OffloadPermissionManager.setPermissionHostAttached(false)
            // Host removes these grants before instrumentation; do not revoke and kill this live process.
            assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.READ_CALENDAR))
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.WRITE_CALENDAR)
            rejected(model("android.calendar.create", JSONObject().put("title", marker).put("start_time", "2030-01-02T10:00:00Z")), ToolFailureKind.PERMISSION_DENIED)
            passed("missing READ_CALENDAR with WRITE granted refuses before calendar selection or insertion, without a permission UI")
            rejected(model("android.contacts.search", JSONObject().put("query", marker)), ToolFailureKind.PERMISSION_DENIED)
            passed("actual missing contacts runtime permission remains a no-start model and Room receipt")
            rejected(model("android.location.get", JSONObject()), ToolFailureKind.PERMISSION_DENIED)
            passed("actual missing fine and coarse location permission never requests a location fix")
            for (command in listOf("android-calendar", "android-contacts", "android-location")) {
                val args = when (command) { "android-calendar" -> listOf("list"); "android-contacts" -> listOf("search", marker); else -> listOf("current") }
                val (exit, output) = cli(command, listOf("--execution-json") + args)
                assertEquals(77, exit); assertEquals(77, facts(output, ToolFailureKind.PERMISSION_DENIED, false).getInt("exit_code"))
                assertTrue(JSONObject(output).getString("output").contains("permission_required"))
            }
            passed("authenticated bridge envelopes preserve the native exit 77 and the same permission/no-start facts")
            permissions.filterNot { it.contains("LOCATION") }.forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
            val invalidArgs = JSONObject().put("title", marker).put("start_time", "invalid-date").put("calendar_id", 1)
            val invalid = model("android.calendar.create", invalidArgs)
            rejected(invalid, ToolFailureKind.INVALID_ARGUMENTS)
            app.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.TITLE}=?", arrayOf(marker), null)!!.use { assertEquals(0, it.count) }
            passed("real domain date rejection and Room receipt prove no Android action started and no event inserted")
            rejected(model("android.calendar.delete", JSONObject().put("event_id", 0)), ToolFailureKind.INVALID_ARGUMENTS)
            rejected(model("android.contacts.manage", JSONObject().put("action", "update").put("contact_id", 1)), ToolFailureKind.INVALID_ARGUMENTS)
            rejected(model("android.clipboard", JSONObject().put("action", "write").put("content", "")), ToolFailureKind.INVALID_ARGUMENTS)
            passed("model adapter preflight validation of IDs, update fields and empty clipboard content carries no-start facts")
            val nativeArgs = listOf("create", "--title", marker, "--start", "invalid-date")
            val raw = cli("android-calendar", nativeArgs)
            for (flags in listOf(emptyList(), listOf("--compact", "--quiet"))) {
                val framed = cli("android-calendar", listOf("--execution-json") + nativeArgs + flags)
                assertEquals(2, framed.first)
                assertEquals(raw.second, facts(framed.second, ToolFailureKind.INVALID_ARGUMENTS, false).getString("output"))
            }
            passed("authenticated invalid-date CLI preserves exact raw UTF-8/newlines and exit 2, including compact/quiet framing")
            val help = cli("android-clipboard", listOf("--help"))
            val helpFramed = cli("android-clipboard", listOf("--execution-json", "--help"))
            assertEquals(help.second, facts(helpFramed.second, null, false).getString("output")); assertEquals(0, helpFramed.first)
            passed("existing plaintext clipboard help is preserved exactly by opt-in framing without reading personal clipboard data")

            val real = app.contentResolver
            val calendarUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, marker)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            val calendarId = ContentUris.parseId(real.insert(calendarUri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, marker)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, marker); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, marker)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, marker)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            }) ?: error("calendar fixture insert failed")).also(createdCalendars::add)
            var reminderFault = "security"
            var reminderCalls = 0
            val calendarProvider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = real.getType(uri)
                override fun insert(uri: Uri, values: ContentValues?): Uri? {
                    val inserted = real.insert(uri, values)
                    if (uri == CalendarContract.Reminders.CONTENT_URI) {
                        reminderCalls++
                        if (reminderFault == "security") throw SecurityException("controlled reply failure after a real reminder insert")
                        return null
                    }
                    return inserted
                }
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = real.delete(uri, selection, selectionArgs)
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = real.update(uri, values, selection, selectionArgs)
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
                    real.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            calendarProvider.attachInfo(app, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
            val calendarResolver = ContentResolver.wrap(calendarProvider)
            val calendarContext = object : ContextWrapper(app) { override fun getContentResolver() = calendarResolver }
            originalCalendar.values.forEach { original -> ToolRegistry.register(object : ToolHandler {
                override val definition get() = original.definition
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String) =
                    original.execute(argsJson, sessionId, calendarContext, toolId)
            }) }
            for (mode in listOf("security", "null")) {
                reminderFault = mode
                var eventId: Long? = null
                for (action in listOf("create", "update")) {
                    val before = reminderCalls
                    val args = JSONObject().put("reminder_minutes", 9)
                    if (action == "create") args.put("title", "$marker-$mode").put("start_time", "2030-01-02T10:00:00Z").put("calendar_id", calendarId)
                    else args.put("event_id", eventId).put("title", "$marker-$mode-updated")
                    val outcome = model("android.calendar.$action", args, goal = true)
                    val body = JSONObject(outcome.receipt.content)
                    eventId = body.getLong("id")
                    assertTrue(body.has("reminder_error")); assertTrue(outcome.receipt.isError)
                    assertEquals(ToolFailureKind.OUTCOME_UNKNOWN, outcome.receipt.failureKind)
                    assertEquals(true, outcome.receipt.executionStarted); assertEquals(before + 1, reminderCalls)
                    if (action == "update") { assertTrue(body.getBoolean("updated")); assertEquals(1, body.getInt("rows")) }
                    val pending = ToolCheckpointStore.drainPending(app, outcome.sid).single()
                    assertEquals(outcome.receipt.executionIntentId, pending.intentId)
                    val goal = app.agentTaskRepository.sessionGoal(outcome.sid)!!
                    assertEquals("WAITING", goal.status); assertEquals(0, goal.autoRunsUsed)
                    real.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
                        "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use {
                        assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(9, it.getInt(0))
                    }
                    report.getJSONArray("receipts").put(JSONObject().put("calendarAction", action).put("fault", mode)
                        .put("eventId", eventId).put("actualReminderCalls", reminderCalls).put("taskStatus", goal.status)
                        .put("autoRunsUsed", goal.autoRunsUsed).put("retainedExecutionId", pending.intentId))
                    passed("actual calendar $action and reminder insert followed by controlled $mode reply failure retain partial result, exact intent and WAITING without replay")
                }
            }
            originalCalendar.values.forEach(ToolRegistry::register)
            var fault: String? = null
            var batchCalls = 0
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = real.getType(uri)
                override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = real.delete(uri, selection, selectionArgs)
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = real.update(uri, values, selection, selectionArgs)
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                    if (uri.path?.startsWith("/raw_contacts/") == true && uri.lastPathSegment?.toLongOrNull() == createdRawIds.lastOrNull()) {
                        if (fault == "security") throw SecurityException("controlled aggregated-id observation failure after a real contact batch")
                        if (fault == "null") return null
                    }
                    return real.query(uri, projection, selection, selectionArgs, sortOrder)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    val results = real.applyBatch(ContactsContract.AUTHORITY, operations)
                    results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()?.let(createdRawIds::add)
                    batchCalls++
                    return results
                }
            }
            provider.attachInfo(app, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
            val wrapped = ContentResolver.wrap(provider)
            val faultContext = object : ContextWrapper(app) { override fun getContentResolver() = wrapped }
            ToolRegistry.register(object : ToolHandler {
                override val definition get() = originalContacts.definition
                override suspend fun execute(argsJson: String, sessionId: String, context: android.content.Context, toolId: String) =
                    originalContacts.execute(argsJson, sessionId, faultContext, toolId)
            })
            for ((mode, kind) in listOf("security" to ToolFailureKind.OUTCOME_UNKNOWN, "null" to ToolFailureKind.OBSERVATION_FAILED)) {
                fault = mode
                val before = batchCalls
                val outcome = model("android.contacts.manage", JSONObject().put("action", "create").put("name", "$marker-$mode"), goal = true)
                assertEquals(before + 1, batchCalls); assertEquals(kind, outcome.receipt.failureKind); assertEquals(true, outcome.receipt.executionStarted)
                assertEquals(mode == "security", outcome.receipt.isError)
                val pending = ToolCheckpointStore.drainPending(app, outcome.sid).single()
                assertEquals(outcome.receipt.executionIntentId, pending.intentId); assertFalse(pending.argsJson.contains(marker))
                val goal = app.agentTaskRepository.sessionGoal(outcome.sid)!!
                assertEquals("WAITING", goal.status); assertEquals(0, goal.autoRunsUsed)
                val rawId = createdRawIds.last()
                real.query(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId), arrayOf(ContactsContract.RawContacts._ID), null, null, null)!!.use { assertTrue(it.moveToFirst()) }
                report.getJSONArray("receipts").put(JSONObject().put("fault", mode).put("rawContactId", rawId).put("actualBatchCalls", batchCalls)
                    .put("taskStatus", goal.status).put("autoRunsUsed", goal.autoRunsUsed).put("retainedExecutionId", pending.intentId))
                passed("actual contact batch followed by controlled $mode observation failure retains its exact intent and waits without replay or budget use")
            }
            fault = null
            val success = model("android.contacts.manage", JSONObject().put("action", "create").put("name", "$marker --execution-json"))
            assertFalse(success.receipt.isError); assertEquals(true, success.receipt.executionStarted)
            assertTrue(ToolCheckpointStore.drainPending(app, success.sid).isEmpty())
            // The existing contacts parser treats a bare leading '--' query as a flag.
            // Verify its rejection stays plaintext, and use a supported literal query below.
            val bareFlag = cli("android-contacts", listOf("search", "--execution-json"))
            assertEquals(2, bareFlag.first); assertTrue(bareFlag.second.startsWith("android-contacts search:"))
            val found = cli("android-contacts", listOf("search", "$marker --execution-json"))
            assertEquals(0, found.first)
            val rows = JSONObject(found.second).getJSONArray("contacts")
            assertTrue((0 until rows.length()).any { rows.getJSONObject(it).toString().contains(marker) })
            passed("actual successful contact settles after Room; only a leading transport flag selects framing, while query text and the native contacts object remain intact")
            val framedRows = cli("android-contacts", listOf("--execution-json", "search", "$marker --execution-json"))
            assertEquals(found.second, facts(framedRows.second, null, true).getString("output"))
            passed("authenticated successful native contacts object and its execution facts agree without changing the original body")

            val server = MCPServer(app, 24678)
            val token = TokenStore.Token("domain-facts-${UUID.randomUUID()}", UUID.randomUUID().toString(), scope = setOf("android.calendar.read"))
            TokenStore.upsert(token)
            try {
                server.start(); assertTrue(server.isRunning)
                fun rpc(params: JSONObject): JSONObject {
                    val connection = URL("http://127.0.0.1:24678/mcp").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 10_000; connection.connectTimeout = 5_000
                        connection.setRequestProperty("Authorization", "Bearer ${token.token}"); connection.setRequestProperty("Content-Type", "application/json")
                        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", params)
                        connection.outputStream.use { it.write(request.toString().toByteArray()) }
                        assertEquals(200, connection.responseCode)
                        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                val params = JSONObject().put("name", "android_calendar_read").put("arguments",
                    JSONObject().put("start_date", "invalid-date").put("end_date", "invalid-date"))
                val pending = rpc(params).getJSONObject("error")
                assertEquals("confirm_required", pending.getString("message"))
                val confirm = pending.getJSONObject("data").getString("confirm_id")
                server.approveConfirm(confirm, "android.calendar.read")
                val result = rpc(params.put("confirm_id", confirm)).getJSONObject("result")
                assertTrue(result.getBoolean("isError"))
                val execution = result.getJSONObject("structuredContent")
                assertEquals("INVALID_ARGUMENTS", execution.getString("failure_kind")); assertFalse(execution.getBoolean("execution_started")); assertFalse(execution.getBoolean("outcome_unknown"))
                assertFalse(execution.has("executionIntentId")); assertFalse(execution.has("executionRunId")); assertFalse(execution.has("executionTaskId"))
            } finally { server.stop(); TokenStore.remove(token.id) }
            passed("authenticated MCP domain rejection shares no-start classification without local model execution identities")
            report.put("actualBatchCalls", batchCalls).put("actualReminderCalls", reminderCalls).put("createdRawIds", JSONArray(createdRawIds))
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            ToolRegistry.register(originalContacts)
            originalCalendar.values.forEach(ToolRegistry::register)
            createdCalendars.forEach { app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, it), null, null) }
            createdRawIds.forEach { app.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it), null, null) }
            val contactsRemoved = createdRawIds.all { id -> app.contentResolver.query(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.DELETED}=0", null, null)!!.use { it.count == 0 } }
            val eventsRemoved = createdCalendars.all { id -> app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0", arrayOf(id.toString()), null)!!.use { it.count == 0 } }
            report.put("fixtureRecordsRemoved", contactsRemoved && eventsRemoved)
            oldLevels.forEach { (tool, level) -> OffloadPermissionManager.setLevel(tool, level) }
            OffloadPermissionManager.setPermissionHostAttached(oldHost)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            // Host runner restores original Android permissions even if this process is terminated.
            reportFile.writeText(report.toString(2))
            assertTrue("fixture provider records remain", contactsRemoved && eventsRemoved)
        }
    }
}
