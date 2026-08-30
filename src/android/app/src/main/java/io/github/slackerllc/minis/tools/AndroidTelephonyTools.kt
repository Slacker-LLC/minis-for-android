package io.github.slackerllc.minis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * P10 Gate 3b: read-only Telephony tools.
 *
 *  - android.sms.read: query the SMS provider (inbox/sent/all). Requires the
 *    runtime READ_SMS permission; the system SMS provider also enforces the
 *    default-SMS-role rule on several OEMs, so the tool reports the real
 *    capability state instead of pretending.
 *  - android.call_log.read: query CallLog.Calls. Requires READ_CALL_LOG.
 *
 * Read-only on purpose. Sending SMS / placing calls / call takeover would need
 * the default SMS role / InCallService role — deliberately out of scope and
 * reported as such.
 */
object AndroidTelephonyOps {

    private const val MAX_MESSAGES = 100
    private const val MAX_CALLS = 100

    suspend fun readSms(
        context: Context,
        folder: String,
        limit: Int,
        unreadOnly: Boolean,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolExecutionResult(
                buildString {
                    append("{\"permission\":\"denied\",\"permission_name\":\"READ_SMS\",")
                    append("\"hint\":\"Grant the runtime permission, then call again. On some OEMs reading SMS also requires the default SMS app role.\"}")
                },
                true,
            )
        }
        val uri = when (folder) {
            "sent" -> android.net.Uri.parse("content://sms/sent")
            "all" -> android.net.Uri.parse("content://sms")
            "inbox", "" -> android.net.Uri.parse("content://sms/inbox")
            else -> return@withContext ToolExecutionResult("Error: folder must be inbox|sent|all", false)
        }
        val projection = arrayOf("_id", "address", "body", "date", "read", "thread_id", "type")
        val selection = if (unreadOnly) "read=0" else null
        val out = JSONArray()
        var total = 0
        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                null,
                "date DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow("_id")
                val addrCol = c.getColumnIndexOrThrow("address")
                val bodyCol = c.getColumnIndexOrThrow("body")
                val dateCol = c.getColumnIndexOrThrow("date")
                val readCol = c.getColumnIndexOrThrow("read")
                val threadCol = c.getColumnIndexOrThrow("thread_id")
                val typeCol = c.getColumnIndexOrThrow("type")
                total = c.count
                val take = limit.coerceIn(1, MAX_MESSAGES)
                var i = 0
                while (c.moveToNext() && i < take) {
                    out.put(JSONObject().apply {
                        put("id", c.getLong(idCol))
                        put("address", c.getString(addrCol) ?: "")
                        put("body", c.getString(bodyCol) ?: "")
                        put("date_ms", c.getLong(dateCol))
                        put("read", c.getInt(readCol) != 0)
                        put("thread_id", c.getLong(threadCol))
                        put("type", c.getInt(typeCol))
                    })
                    i++
                }
            }
        } catch (e: SecurityException) {
            return@withContext ToolExecutionResult(
                "{\"error\":\"SMS provider denied\",\"hint\":\"READ_SMS granted but the provider rejected the read — the default SMS app role is required on this device.\"}",
                true,
            )
        }
        ToolExecutionResult(
            JSONObject().apply {
                put("folder", folder)
                put("returned", out.length())
                put("total_matching", total)
                put("messages", out)
            }.toString(2),
            true,
        )
    }

    suspend fun readCallLog(
        context: Context,
        limit: Int,
        minDurationSec: Long?,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolExecutionResult(
                "{\"permission\":\"denied\",\"permission_name\":\"READ_CALL_LOG\",\"hint\":\"Grant the runtime permission, then call again.\"}",
                true,
            )
        }
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NEW,
        )
        val selection = if (minDurationSec != null && minDurationSec > 0) "${CallLog.Calls.DURATION}>=?" else null
        val selectionArgs = if (selection != null) arrayOf(minDurationSec.toString()) else null
        val out = JSONArray()
        var total = 0
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeCol = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateCol = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durCol = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val newCol = c.getColumnIndexOrThrow(CallLog.Calls.NEW)
                total = c.count
                val take = limit.coerceIn(1, MAX_CALLS)
                var i = 0
                while (c.moveToNext() && i < take) {
                    out.put(JSONObject().apply {
                        put("id", c.getLong(idCol))
                        put("number", c.getString(numCol) ?: "")
                        put("name", c.getString(nameCol) ?: "")
                        put("type", c.getInt(typeCol))
                        put("date_ms", c.getLong(dateCol))
                        put("duration_sec", c.getLong(durCol))
                        put("new", c.getInt(newCol) != 0)
                    })
                    i++
                }
            }
        } catch (e: SecurityException) {
            return@withContext ToolExecutionResult(
                "{\"error\":\"Call log provider denied\",\"hint\":\"READ_CALL_LOG granted but the read was rejected.\"}",
                true,
            )
        }
        ToolExecutionResult(
            JSONObject().apply {
                put("returned", out.length())
                put("total_matching", total)
                put("calls", out)
            }.toString(2),
            true,
        )
    }
}

abstract class AndroidTelephonyHandler : AndroidSystemHandler()

class AndroidSmsReadHandler : AndroidTelephonyHandler() {
    override val definition = AgentToolDefinition(
        name = "android.sms.read",
        description = "Read SMS messages (inbox/sent/all) via the SMS provider. Requires the READ_SMS runtime permission; the provider may also require the default SMS app role.",
        parameters = mapOf(
            "folder" to AgentToolParam("string", "inbox (default), sent, or all", listOf("inbox", "sent", "all")),
            "limit" to AgentToolParam("integer", "Max messages (default 20, max 100)"),
            "unread_only" to AgentToolParam("boolean", "Only unread messages (default false)"),
        ),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidTelephonyOps.readSms(
            context,
            a.optString("folder"),
            a.optInt("limit", 20),
            a.optBoolean("unread_only"),
        )
    }
}

class AndroidCallLogReadHandler : AndroidTelephonyHandler() {
    override val definition = AgentToolDefinition(
        name = "android.call_log.read",
        description = "Read the device call log (recent calls) via CallLog.Calls. Requires the READ_CALL_LOG runtime permission.",
        parameters = mapOf(
            "limit" to AgentToolParam("integer", "Max entries (default 20, max 100)"),
            "min_duration_sec" to AgentToolParam("integer", "Only calls at least this long (optional)"),
        ),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidTelephonyOps.readCallLog(
            context,
            a.optInt("limit", 20),
            if (a.has("min_duration_sec")) a.optLong("min_duration_sec") else null,
        )
    }
}
