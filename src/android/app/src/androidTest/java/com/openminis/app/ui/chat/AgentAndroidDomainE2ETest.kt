package com.openminis.app.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.guest.NativeOffloadResult
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.tools.ToolFailureKind
import com.openminis.app.tools.ToolSensitivePolicy
import com.openminis.app.tools.runtime.ToolRegistry
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

/** Real model loop/Room → existing Android handlers → platform providers; only model transport is scripted. */
@RunWith(AndroidJUnit4::class)
class AgentAndroidDomainE2ETest {
    @Test
    fun preserveCalendarAndContactArgumentsAcrossEntrypoints(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val resolver = app.contentResolver
        val marker = "MinisDomainE2E-${UUID.randomUUID()}"
        val sessions = mutableListOf<String>()
        val calendars = mutableListOf<Long>()
        val rawContacts = mutableListOf<Long>()
        val levels = listOf("calendar", "contacts").associateWith(OffloadPermissionManager::getLevel)
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val originalPermissions = JSONObject()
        permissions.forEach { originalPermissions.put(it, app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) }
        report.put("originalPermissions", originalPermissions)
        // Persist before granting: the host runner restores original permissions even if instrumentation dies.
        val reportFile = File(app.filesDir, "agent-android-domain-e2e.json")
        reportFile.writeText(report.toString(2))
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(): String = app.chatRepository.createSession("scripted-model", title = marker, memoryEnabled = false).id.also(sessions::add)
        suspend fun model(name: String, arguments: JSONObject): AgentContentPart.ToolResult {
            val sid = session()
            var receipt: AgentContentPart.ToolResult? = null
            val provider = ScriptedProvider { call, history, _ ->
                if (call == 1) toolCall("domain-call", name, arguments) else {
                    receipt = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().firstOrNull { it.id == "domain-call" }
                    text("已收到实际系统接口回执。")
                }
            }
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "domain-scripted" }
            assertTrue(AgentRunner.prompt(app, sid, "执行指定的本机测试记录操作", wait = true, timeoutMs = 20_000).completed)
            val result = receipt ?: error("model received no domain receipt")
            val stored = app.chatRepository.loadMessages(sid).map(AgentSessionStore.existing(sid)!!.messageMapper::toLLMMessage)
                .flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "domain-call" }
            assertEquals(ToolSensitivePolicy.RESULT_PLACEHOLDER, stored.content)
            assertEquals(result.executionIntentId, stored.executionIntentId)
            assertEquals(result.executionStarted, stored.executionStarted)
            assertTrue(ToolCheckpointStore.drainPending(app, sid).isEmpty())
            return result
        }
        fun successful(result: AgentContentPart.ToolResult): JSONObject {
            assertFalse(result.content, result.isError); assertEquals(true, result.executionStarted)
            return JSONObject(result.content)
        }
        suspend fun cli(command: String, args: List<String>): NativeOffloadResult = withContext(Dispatchers.IO) {
            GuestCommandBridge.dispatch(command, args, sessions.first(), "/workspace", "")
        }
        fun calendar(label: String): Long {
            val account = "$marker-$label"
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, account)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, account); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, account)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff0099ff.toInt())
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            }
            return ContentUris.parseId(resolver.insert(uri, values) ?: error("fixture calendar insert failed")).also(calendars::add)
        }
        fun event(id: Long): JSONObject {
            val columns = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART)
            return resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), columns, null, null, null)!!.use { cursor ->
                assertTrue("fixture event missing", cursor.moveToFirst())
                JSONObject().also { out -> columns.forEachIndexed { i, column -> out.put(column, if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i)) } }
            }
        }
        fun eventCount(): Int = resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} IN (${calendars.joinToString(",")}) AND ${CalendarContract.Events.DELETED}=0", null, null)!!.use { it.count }
        fun contactField(rawId: Long, mime: String): String? = resolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawId.toString(), mime), null)!!.use { if (it.moveToFirst()) it.getString(0) else null }
        try {
            permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
            levels.keys.forEach { OffloadPermissionManager.setLevel(it, OffloadPermissionManager.PermissionLevel.BYPASS) }
            val first = calendar("first"); val second = calendar("second")
            val createDefinition = ToolRegistry.handler("android.calendar.create")!!.definition
            assertTrue(createDefinition.parameters.keys.containsAll(listOf("calendar_id", "calendar_name", "reminder_minutes")))
            assertTrue(ToolRegistry.handler("android.calendar.update")!!.definition.parameters.containsKey("calendar_id"))
            passed("registered model schemas expose the existing CLI calendar target and creation reminder")
            val created = successful(model("android.calendar.create", JSONObject().put("title", marker).put("start_time", "2030-01-02T10:00:00Z")
                .put("end_time", "2030-01-02T11:00:00Z").put("description", "fixture notes").put("location", "fixture place")
                .put("calendar_id", first).put("reminder_minutes", 7)))
            val id = created.getLong("id")
            assertEquals(first, event(id).getLong(CalendarContract.Events.CALENDAR_ID))
            resolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()), null)!!.use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)) }
            passed("model creates an actual event and reminder in the explicitly selected local provider calendar")
            successful(model("android.calendar.update", JSONObject().put("event_id", id).put("title", "$marker renamed")))
            assertEquals("fixture notes", event(id).getString(CalendarContract.Events.DESCRIPTION))
            assertEquals("fixture place", event(id).getString(CalendarContract.Events.EVENT_LOCATION))
            passed("omitted update fields preserve provider values")
            successful(model("android.calendar.update", JSONObject().put("event_id", id).put("description", "").put("location", "")))
            assertEquals("", event(id).getString(CalendarContract.Events.DESCRIPTION)); assertEquals("", event(id).getString(CalendarContract.Events.EVENT_LOCATION))
            val read = cli("android-calendar", listOf("list", "--start", "2030-01-02", "--end", "2030-01-03", "--calendar", "$marker-first", "--title-filter", marker))
            assertEquals(read.output, 0, read.exitCode); assertEquals(id, JSONObject(read.output).getJSONArray("events").getJSONObject(0).getLong("id"))
            passed("explicit empty model notes and location clear actual provider fields and CLI reads the same event")
            successful(model("android.calendar.update", JSONObject().put("event_id", id).put("calendar_name", "$marker-second")))
            assertEquals(second, event(id).getLong(CalendarContract.Events.CALENDAR_ID))
            successful(model("android.calendar.update", JSONObject().put("event_id", id).put("calendar_id", first).put("calendar_name", "$marker-missing")))
            assertEquals(first, event(id).getLong(CalendarContract.Events.CALENDAR_ID))
            passed("model update forwards name and ID targets with the native CLI ID precedence")
            val before = eventCount()
            val invalid = model("android.calendar.create", JSONObject().put("title", marker).put("start_time", "2030-01-02T10:00:00Z").put("end_time", "").put("calendar_id", first))
            assertTrue(invalid.isError); assertEquals(before, eventCount())
            val rejectedTarget = model("android.calendar.update", JSONObject().put("event_id", id).put("calendar_name", "$marker-missing").put("title", "must not change"))
            assertTrue(rejectedTarget.isError); assertEquals("$marker renamed", event(id).getString(CalendarContract.Events.TITLE))
            val invalidTime = model("android.calendar.update", JSONObject().put("event_id", id).put("start_time", ""))
            assertTrue(invalidTime.isError); assertEquals("1893578400000", event(id).getString(CalendarContract.Events.DTSTART))
            passed("supplied empty dates and missing target calendars reject without fallback writes or partial updates")
            OffloadPermissionManager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
            try {
                val denied = model("android.calendar.update", JSONObject().put("event_id", id).put("title", "denied"))
                assertEquals(ToolFailureKind.PERMISSION_DENIED, denied.failureKind); assertEquals(false, denied.executionStarted)
                assertEquals(126, cli("android-calendar", listOf("update", "--id", id.toString(), "--title", "denied")).exitCode)
                assertEquals("$marker renamed", event(id).getString(CalendarContract.Events.TITLE))
            } finally { OffloadPermissionManager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.BYPASS) }
            passed("model and registered CLI permission rejection leave the actual calendar unchanged")
            val contact = successful(model("android.contacts.manage", JSONObject().put("action", "create").put("name", marker)
                .put("phone", "2025550101").put("email", "fixture@example.invalid")))
            val rawId = contact.getLong("raw_contact_id"); rawContacts.add(rawId)
            val contactId = contact.getLong("contact_id")
            val get = cli("android-contacts", listOf("get", contactId.toString()))
            assertEquals(get.output, 0, get.exitCode); assertEquals(contactId, JSONObject(get.output).getLong("id"))
            passed("model creates a real local contact and CLI reads its aggregated contact ID")
            successful(model("android.contacts.manage", JSONObject().put("action", "update").put("contact_id", contactId).put("phone", "").put("email", "")))
            assertEquals("", contactField(rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
            assertEquals("", contactField(rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE))
            assertEquals(marker, contactField(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            passed("empty phone and email clear the existing provider rows while the omitted name remains unchanged")
            val update = cli("android-contacts", listOf("update", "--id", contactId.toString(), "--name", "$marker CLI", "--phone", "2025550102"))
            assertEquals(update.output, 0, update.exitCode)
            val searched = successful(model("android.contacts.search", JSONObject().put("query", marker)))
            assertTrue(searched.toString(), searched.toString().contains("2025550102")); assertEquals("$marker CLI", contactField(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            passed("registered CLI updates are visible through the real model contacts search and provider readback")
            OffloadPermissionManager.setLevel("contacts", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
            try {
                val denied = model("android.contacts.manage", JSONObject().put("action", "update").put("contact_id", contactId).put("name", "denied"))
                assertEquals(ToolFailureKind.PERMISSION_DENIED, denied.failureKind); assertEquals(false, denied.executionStarted)
                assertEquals(126, cli("android-contacts", listOf("update", "--id", contactId.toString(), "--name", "denied")).exitCode)
                assertEquals("$marker CLI", contactField(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            } finally { OffloadPermissionManager.setLevel("contacts", OffloadPermissionManager.PermissionLevel.BYPASS) }
            passed("both permission gates reject contact mutations before changing the actual row")
            assertEquals(0, cli("android-calendar", listOf("delete", "--id", id.toString())).exitCode)
            assertEquals(0, eventCount())
            successful(model("android.contacts.manage", JSONObject().put("action", "delete").put("contact_id", contactId)))
            assertNull(contactField(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            passed("cross-entrypoint deletion removes only the dedicated fixture event and contact")
            val delivery = JSONObject().put("marker", marker).put("calendar_id", first).put("event_id", id)
                .put("contact_id", contactId).put("raw_contact_id", rawId).put("provider_records_deleted", true)
            val bytes = delivery.toString(2).toByteArray()
            File(app.filesDir, "agent-android-domain-delivery.json").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            // These IDs belong solely to this test's unique local fixtures.
            rawContacts.forEach { resolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it), null, null) }
            calendars.forEach { id -> resolver.delete(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id), null, null) }
            levels.forEach { (tool, level) -> OffloadPermissionManager.setLevel(tool, level) }
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            reportFile.writeText(report.toString(2))
        }
    }
}
