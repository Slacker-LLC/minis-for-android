package com.openminis.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.debug.SafeRemoteImporter
import com.openminis.app.runtime.guest.NativeOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.CalendarOffloadHandler
import com.openminis.app.runtime.guest.ClipboardOffloadHandler
import com.openminis.app.runtime.guest.ContactsOffloadHandler
import com.openminis.app.runtime.guest.LocationOffloadHandler
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P10 Android system tools.
 *
 * Calendar, contacts, location and clipboard deliberately delegate to their
 * existing android-* offload handlers. That keeps one ContentProvider/
 * permission implementation per capability; these are only structured
 * ToolRegistry adapters. Web, time and generic Intent dispatch have no
 * existing equivalent and live here.
 */
object AndroidSystemOps {
    private const val MAX_FETCH_BYTES = 100_000

    internal suspend fun offload(
        context: Context,
        sessionId: String,
        handler: NativeOffloadHandler,
        argv: List<String>,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = handler.handle(
            NativeOffloadRequest(
                pid = Process.myPid(),
                argv = argv,
                env = emptyMap(),
                cwd = "/workspace",
                sessionId = sessionId,
            ),
        )
        ToolExecutionResult(result.output.trimEnd(), result.exitCode == 0)
    }

    private fun MutableList<String>.option(name: String, value: String?) {
        if (!value.isNullOrBlank()) {
            add("--$name")
            add(value)
        }
    }

    // ── Web ─────────────────────────────────────────────────────────────────

    suspend fun webSearch(query: String, count: Int, freshness: String?): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult("Error: query is required", false)
        return try {
            val freshnessParam = when (freshness?.lowercase()) {
                "day", "d" -> "&df=d"
                "week", "w" -> "&df=w"
                "month", "m" -> "&df=m"
                "year", "y" -> "&df=y"
                null, "" -> ""
                else -> return ToolExecutionResult("Error: invalid freshness: $freshness", false)
            }
            val html = SafeRemoteImporter.downloadText(
                "https://html.duckduckgo.com/html/?q=${Uri.encode(query)}$freshnessParam",
                512 * 1024,
            )
            val results = parseDdgResults(html).take(count.coerceIn(1, 20))
            val items = JSONArray()
            results.forEach { (title, url, snippet) ->
                items.put(JSONObject().put("title", title).put("url", url).put("snippet", snippet))
            }
            ToolExecutionResult(
                JSONObject().put("query", query).put("results", items).put("count", items.length()).toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: web_search failed: ${t.message}", false)
        }
    }

    suspend fun urlFetch(url: String, maxLength: Int, extractMode: String): ToolExecutionResult {
        if (url.isBlank()) return ToolExecutionResult("Error: url is required", false)
        if (extractMode !in setOf("text", "markdown", "raw")) {
            return ToolExecutionResult("Error: invalid extract_mode: $extractMode", false)
        }
        return try {
            // SafeRemoteImporter rejects non-HTTPS, credentials, private DNS,
            // and unsafe redirects before connecting; this MCP-visible tool is
            // therefore not an SSRF path to device-local services.
            val raw = SafeRemoteImporter.downloadText(url, maxLength.coerceIn(100, MAX_FETCH_BYTES))
            val text = if (extractMode == "raw") raw else stripHtml(raw)
            ToolExecutionResult(text.take(maxLength.coerceIn(100, MAX_FETCH_BYTES)), true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: url_fetch failed: ${t.message}", false)
        }
    }

    private fun parseDdgResults(html: String): List<Triple<String, String, String>> {
        val links = Regex(
            """<a rel="nofollow" class="result__a" href="([^"]+)">(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).findAll(html).toList()
        val snippets = Regex(
            """<a class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).findAll(html).toList()
        return links.mapIndexedNotNull { index, match ->
            val title = stripHtml(match.groupValues[2]).trim()
            val url = extractDdgUrl(match.groupValues[1])
            if (title.isBlank() || url.isBlank()) null
            else Triple(title, url, snippets.getOrNull(index)?.groupValues?.get(1)?.let(::stripHtml)?.trim().orEmpty())
        }
    }

    private fun extractDdgUrl(raw: String): String {
        val marker = "uddg="
        val offset = raw.indexOf(marker)
        return if (offset < 0) raw else Uri.decode(raw.substring(offset + marker.length).substringBefore('&'))
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("""(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>"""), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ── Existing Android controller adapters ────────────────────────────────

    suspend fun calendarRead(
        context: Context,
        sessionId: String,
        start: String?,
        end: String?,
        limit: Int,
        calendar: String?,
        titleFilter: String?,
    ): ToolExecutionResult {
        if ((start == null) != (end == null)) {
            return ToolExecutionResult("Error: start_date and end_date must be supplied together", false)
        }
        val argv = mutableListOf("android-calendar", "list")
        argv.option("start", start)
        argv.option("end", end)
        argv.option("calendar", calendar)
        argv.option("title-filter", titleFilter)
        argv += listOf("--limit", limit.coerceIn(1, 200).toString())
        return offload(context, sessionId, CalendarOffloadHandler(context), argv)
    }

    suspend fun calendarCreate(
        context: Context,
        sessionId: String,
        title: String,
        start: String,
        end: String?,
        description: String?,
        location: String?,
        allDay: Boolean,
    ): ToolExecutionResult {
        if (title.isBlank() || start.isBlank()) return ToolExecutionResult("Error: title and start_time are required", false)
        val argv = mutableListOf("android-calendar", "create", "--title", title, "--start", start)
        argv.option("end", end)
        argv.option("description", description)
        argv.option("location", location)
        if (allDay) argv += "--all-day"
        return offload(context, sessionId, CalendarOffloadHandler(context), argv)
    }

    suspend fun calendarUpdate(
        context: Context,
        sessionId: String,
        eventId: Long,
        title: String?,
        start: String?,
        end: String?,
        description: String?,
        location: String?,
        reminderMinutes: Int?,
    ): ToolExecutionResult {
        if (eventId <= 0) return ToolExecutionResult("Error: event_id is required", false)
        val argv = mutableListOf("android-calendar", "update", "--id", eventId.toString())
        argv.option("title", title)
        argv.option("start", start)
        argv.option("end", end)
        argv.option("description", description)
        argv.option("location", location)
        reminderMinutes?.let { argv += listOf("--alarm", it.coerceAtLeast(0).toString()) }
        return offload(context, sessionId, CalendarOffloadHandler(context), argv)
    }

    suspend fun calendarDelete(context: Context, sessionId: String, eventId: Long): ToolExecutionResult {
        if (eventId <= 0) return ToolExecutionResult("Error: event_id is required; read events first", false)
        return offload(
            context, sessionId, CalendarOffloadHandler(context),
            listOf("android-calendar", "delete", "--id", eventId.toString()),
        )
    }

    suspend fun contactsSearch(context: Context, sessionId: String, query: String, limit: Int): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult("Error: query is required", false)
        return offload(
            context, sessionId, ContactsOffloadHandler(context),
            listOf("android-contacts", "search", query, "--max", limit.coerceIn(1, 100).toString()),
        )
    }

    suspend fun contactsManage(
        context: Context,
        sessionId: String,
        action: String,
        contactId: Long?,
        name: String?,
        phone: String?,
        email: String?,
    ): ToolExecutionResult {
        val argv = mutableListOf("android-contacts", action)
        when (action) {
            "create" -> {
                if (name.isNullOrBlank()) return ToolExecutionResult("Error: name is required for create", false)
                argv.option("name", name)
                argv.option("phone", phone)
                argv.option("email", email)
            }
            "update" -> {
                if (contactId == null || contactId <= 0) return ToolExecutionResult("Error: contact_id is required for update", false)
                if (name == null && phone == null && email == null) return ToolExecutionResult("Error: supply name, phone, or email", false)
                argv += listOf("--id", contactId.toString())
                argv.option("name", name)
                argv.option("phone", phone)
                argv.option("email", email)
            }
            "delete" -> {
                if (contactId == null || contactId <= 0) return ToolExecutionResult("Error: contact_id is required for delete", false)
                argv += contactId.toString()
            }
            else -> return ToolExecutionResult("Error: invalid action: $action", false)
        }
        return offload(context, sessionId, ContactsOffloadHandler(context), argv)
    }

    suspend fun location(context: Context, sessionId: String, timeoutSeconds: Int): ToolExecutionResult = offload(
        context, sessionId, LocationOffloadHandler(context),
        listOf("android-location", "current", "--timeout", timeoutSeconds.coerceIn(1, 30).toString()),
    )

    suspend fun clipboard(context: Context, sessionId: String, action: String, content: String?): ToolExecutionResult {
        val argv = when (action) {
            "read" -> listOf("android-clipboard", "get")
            "write" -> {
                if (content.isNullOrEmpty()) return ToolExecutionResult("Error: content is required for write", false)
                listOf("android-clipboard", "set", "--text", content)
            }
            else -> return ToolExecutionResult("Error: invalid action: $action", false)
        }
        return offload(context, sessionId, ClipboardOffloadHandler(context), argv)
    }

    // ── Time / generic Intent ───────────────────────────────────────────────

    suspend fun currentTime(format: String?, timezone: String?): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val zone = if (timezone.isNullOrBlank()) TimeZone.getDefault() else {
                if (timezone !in TimeZone.getAvailableIDs()) {
                    return@withContext ToolExecutionResult("Error: invalid timezone: $timezone", false)
                }
                TimeZone.getTimeZone(timezone)
            }
            val output = SimpleDateFormat(format?.ifBlank { null } ?: "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .apply { timeZone = zone }
                .format(Date())
            ToolExecutionResult(
                JSONObject().put("time", output).put("timestamp_ms", System.currentTimeMillis()).put("timezone", zone.id).toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: current_time failed: ${t.message}", false)
        }
    }

    suspend fun sendIntent(context: Context, args: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val type = args.optString("type")
            val action = args.optString("action").ifBlank { null }
            val intent = when (type) {
                "activity" -> Intent(action ?: Intent.ACTION_VIEW)
                "broadcast", "service" -> Intent(action ?: return@withContext ToolExecutionResult("Error: action is required for $type", false))
                else -> return@withContext ToolExecutionResult("Error: invalid type: $type", false)
            }
            val data = args.optString("data").ifBlank { null }
            val mimeType = args.optString("mime_type").ifBlank { null }
            when {
                data != null && mimeType != null -> intent.setDataAndType(Uri.parse(data), mimeType)
                data != null -> intent.data = Uri.parse(data)
                mimeType != null -> intent.type = mimeType
            }
            val packageName = args.optString("component_package").ifBlank { null }
            val className = args.optString("component_class").ifBlank { null }
            if ((packageName == null) != (className == null)) {
                return@withContext ToolExecutionResult("Error: component_package and component_class must be supplied together", false)
            }
            if (packageName != null) intent.setClassName(packageName, className!!)
            args.optJSONArray("categories")?.forEachString { intent.addCategory(it) }
            args.optJSONArray("flags")?.forEachString { flag ->
                intent.addFlags(intentFlags[flag] ?: flag.toIntOrNull() ?: 0)
            }
            args.optJSONObject("extras")?.let { extras ->
                extras.keys().forEach { key ->
                    when (val value = extras.opt(key)) {
                        is String -> intent.putExtra(key, value)
                        is Boolean -> intent.putExtra(key, value)
                        is Int -> intent.putExtra(key, value)
                        is Long -> intent.putExtra(key, value)
                        is Double -> intent.putExtra(key, value)
                    }
                }
            }
            when (type) {
                "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "broadcast" -> context.sendBroadcast(intent)
                "service" -> context.startService(intent)
            }
            ToolExecutionResult("intent sent: type=$type action=${intent.action}", true)
        } catch (t: Throwable) {
            ToolExecutionResult("Error: send_intent failed: ${t.message}", false)
        }
    }

    private fun JSONArray.forEachString(block: (String) -> Unit) {
        for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(block)
    }

    private val intentFlags = mapOf(
        "FLAG_ACTIVITY_CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
        "FLAG_ACTIVITY_NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
        "FLAG_ACTIVITY_SINGLE_TOP" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
        "FLAG_GRANT_READ_URI_PERMISSION" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
        "FLAG_GRANT_WRITE_URI_PERMISSION" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

abstract class AndroidSystemHandler : ToolHandler {
    protected fun args(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
}

class AndroidWebSearchHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.web.search",
        description = "Search the public web and return titles, URLs, and snippets.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Search query"),
            "count" to AgentToolParam("integer", "Max results (default 10, max 20)"),
            "freshness" to AgentToolParam("string", "Optional day/week/month/year filter", listOf("day", "week", "month", "year")),
        ),
        required = listOf("query"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidSystemOps.webSearch(args(argsJson).optString("query"), args(argsJson).optInt("count", 10), args(argsJson).optString("freshness").ifBlank { null })
}

class AndroidUrlFetchHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.web.fetch",
        description = "Fetch a public HTTPS URL and return readable text. Private/local destinations are blocked.",
        parameters = mapOf(
            "url" to AgentToolParam("string", "HTTPS URL"),
            "max_length" to AgentToolParam("integer", "Max returned chars (default 20000, max 100000)"),
            "extract_mode" to AgentToolParam("string", "text (default), markdown, or raw", listOf("text", "markdown", "raw")),
        ),
        required = listOf("url"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.urlFetch(a.optString("url"), a.optInt("max_length", 20_000), a.optString("extract_mode", "text"))
    }
}

class AndroidCalendarReadHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.calendar.read", description = "Read calendar events in an optional ISO time range.",
        parameters = mapOf(
            "start_date" to AgentToolParam("string", "Range start (ISO; supply end_date too)"),
            "end_date" to AgentToolParam("string", "Range end (ISO; supply start_date too)"),
            "limit" to AgentToolParam("integer", "Max events (default 50, max 200)"),
            "calendar_name" to AgentToolParam("string", "Optional calendar-name filter"),
            "title_filter" to AgentToolParam("string", "Optional event-title filter"),
        ),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.calendarRead(context, sessionId, a.optString("start_date").ifBlank { null }, a.optString("end_date").ifBlank { null }, a.optInt("limit", 50), a.optString("calendar_name").ifBlank { null }, a.optString("title_filter").ifBlank { null })
    }
}

class AndroidCalendarCreateHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.calendar.create", description = "Create a calendar event.",
        parameters = mapOf(
            "title" to AgentToolParam("string", "Event title"), "start_time" to AgentToolParam("string", "Start (ISO)"),
            "end_time" to AgentToolParam("string", "Optional end (ISO)"), "description" to AgentToolParam("string", "Optional notes"),
            "location" to AgentToolParam("string", "Optional location"), "all_day" to AgentToolParam("boolean", "All-day event"),
        ), required = listOf("title", "start_time"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.calendarCreate(context, sessionId, a.optString("title"), a.optString("start_time"), a.optString("end_time").ifBlank { null }, a.optString("description").ifBlank { null }, a.optString("location").ifBlank { null }, a.optBoolean("all_day"))
    }
}

class AndroidCalendarUpdateHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.calendar.update", description = "Update an event returned by android.calendar.read.",
        parameters = mapOf(
            "event_id" to AgentToolParam("integer", "Event ID"), "title" to AgentToolParam("string", "New title"),
            "start_time" to AgentToolParam("string", "New start (ISO)"), "end_time" to AgentToolParam("string", "New end (ISO)"),
            "description" to AgentToolParam("string", "New notes"), "location" to AgentToolParam("string", "New location"),
            "reminder_minutes" to AgentToolParam("integer", "Minutes before event"),
        ), required = listOf("event_id"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.calendarUpdate(context, sessionId, a.optLong("event_id", -1), a.optString("title").ifBlank { null }, a.optString("start_time").ifBlank { null }, a.optString("end_time").ifBlank { null }, a.optString("description").ifBlank { null }, a.optString("location").ifBlank { null }, if (a.has("reminder_minutes")) a.optInt("reminder_minutes") else null)
    }
}

class AndroidCalendarDeleteHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.calendar.delete", description = "Delete one calendar event by ID. Destructive — requires confirmation.",
        parameters = mapOf("event_id" to AgentToolParam("integer", "Event ID from android.calendar.read")), required = listOf("event_id"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidSystemOps.calendarDelete(context, sessionId, args(argsJson).optLong("event_id", -1))
}

class AndroidContactsSearchHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.contacts.search", description = "Search contacts by name or phone number.",
        parameters = mapOf("query" to AgentToolParam("string", "Name or phone substring"), "limit" to AgentToolParam("integer", "Max results")), required = listOf("query"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.contactsSearch(context, sessionId, a.optString("query"), a.optInt("limit", 30))
    }
}

class AndroidContactsManageHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.contacts.manage", description = "Create, update, or delete a contact. Mutating — requires confirmation.",
        parameters = mapOf(
            "action" to AgentToolParam("string", "create/update/delete", listOf("create", "update", "delete")),
            "contact_id" to AgentToolParam("integer", "Contact ID for update/delete"), "name" to AgentToolParam("string", "Display name"),
            "phone" to AgentToolParam("string", "Phone number"), "email" to AgentToolParam("string", "Email address"),
        ), required = listOf("action"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.contactsManage(context, sessionId, a.optString("action"), if (a.has("contact_id")) a.optLong("contact_id") else null, a.optString("name").ifBlank { null }, a.optString("phone").ifBlank { null }, a.optString("email").ifBlank { null })
    }
}

class AndroidLocationHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.location.get", description = "Get current device location and reverse-geocoded address when available.",
        parameters = mapOf("timeout_seconds" to AgentToolParam("integer", "Fresh-fix timeout (default 8, max 30)")),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidSystemOps.location(context, sessionId, args(argsJson).optInt("timeout_seconds", 8))
}

class AndroidClipboardHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.clipboard", description = "Read or write the device clipboard.",
        parameters = mapOf("action" to AgentToolParam("string", "read/write", listOf("read", "write")), "content" to AgentToolParam("string", "Content for write")), required = listOf("action"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.clipboard(context, sessionId, a.optString("action"), a.optString("content").ifBlank { null })
    }
}

class AndroidTimeHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.time", description = "Get the current date and time.",
        parameters = mapOf("format" to AgentToolParam("string", "Optional SimpleDateFormat pattern"), "timezone" to AgentToolParam("string", "Optional IANA timezone ID")),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidSystemOps.currentTime(a.optString("format").ifBlank { null }, a.optString("timezone").ifBlank { null })
    }
}

class AndroidSendIntentHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.intent.send", description = "Start an Activity/service or send a broadcast. Requires confirmation.",
        parameters = mapOf(
            "type" to AgentToolParam("string", "activity/broadcast/service", listOf("activity", "broadcast", "service")),
            "action" to AgentToolParam("string", "Intent action"), "data" to AgentToolParam("string", "Data URI"),
            "mime_type" to AgentToolParam("string", "MIME type"), "component_package" to AgentToolParam("string", "Explicit package"),
            "component_class" to AgentToolParam("string", "Explicit class"),
            "categories" to AgentToolParam("array", "Intent categories", items = AgentToolParam("string", "Category")),
            "flags" to AgentToolParam("array", "Known Android flag names or integer strings", items = AgentToolParam("string", "Flag")),
            "extras" to AgentToolParam("object", "String/number/boolean extras"),
        ), required = listOf("type"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidSystemOps.sendIntent(context, args(argsJson))
}
