package com.openminis.app.tools

import android.content.Context
import android.provider.Settings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.NotificationHistoryRepository
import com.openminis.app.notifications.MinisNotificationListenerService
import com.openminis.app.notifications.NotificationHistoryPolicy
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-notification-history] The two notification tools Eta ships: what is in the shade
 * right now, and what the bounded history still holds.
 *
 * Ported from Eta's `recent_notifications` / `search_notification_history` device tools and
 * `data/repository/NotificationHistoryRepository.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Both are classified sensitive, so the transcript keeps a
 * placeholder while the live turn sees the real rows.
 *
 * The only gate that matters is the system one: without Notification Access the listener is
 * never bound, and both tools answer with the settings action that grants it instead of an
 * empty list that would look like "nothing happened".
 */
object NotificationTools {
    const val RECENT = "notification.recent"
    const val SEARCH = "notification.search"

    val aliases: Map<String, List<String>> = mapOf(
        RECENT to listOf("recent_notifications"),
        SEARCH to listOf("search_notification_history"),
    )

    fun handlers(): List<ToolHandler> = listOf(NotificationRecentHandler(), NotificationSearchHandler())

    internal fun accessRequired(): String = JSONObject()
        .put("error", "notification_access_required")
        .put("message", "Grant Notification Access to Minis in Android Settings before reading notifications.")
        .put("settings_action", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .toString(2)

    internal suspend fun recent(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val allowed = OffloadPermissionManager.checkPermission(RECENT, "Notification shade", sessionId.ifBlank { "global" })
        if (!allowed) return ToolExecutionResult("Error: permission_denied: notification", false)
        if (!MinisNotificationListenerService.isAccessGranted(context)) {
            return ToolExecutionResult(accessRequired(), false)
        }
        val shade = MinisNotificationListenerService.currentShade()
            ?: return ToolExecutionResult(
                JSONObject()
                    .put("error", "notification_listener_unavailable")
                    .put("message", "Notification Access is granted but the listener is not connected yet; try again in a moment.")
                    .toString(2),
                false,
            )
        val args = JSONObject(argsJson)
        val packageFilter = args.optString("package_name", "").trim()
        val limit = NotificationHistoryPolicy.clampRecentLimit(if (args.has("limit")) args.optInt("limit") else null)
        val matched = shade
            .filter { packageFilter.isEmpty() || it.packageName == packageFilter }
            .sortedByDescending { it.postedAt }
        val items = JSONArray().also { array ->
            matched.take(limit).forEach { row ->
                array.put(
                    JSONObject()
                        .put("package_name", row.packageName)
                        .put("title", NotificationHistoryPolicy.bounded(row.title) ?: JSONObject.NULL)
                        .put("text", NotificationHistoryPolicy.bounded(row.text) ?: JSONObject.NULL)
                        .put("sub_text", NotificationHistoryPolicy.bounded(row.subText) ?: JSONObject.NULL)
                        .put("posted_at", row.postedAt),
                )
            }
        }
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("scope", "current notification shade")
                .put("count", items.length())
                .put("available", matched.size)
                .put("limit", limit)
                .put("truncated", matched.size > items.length())
                .put("items", items)
                .toString(2),
            true,
        )
    }

    internal suspend fun search(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val allowed = OffloadPermissionManager.checkPermission(SEARCH, "Notification history", sessionId.ifBlank { "global" })
        if (!allowed) return ToolExecutionResult("Error: permission_denied: notification", false)
        if (!MinisNotificationListenerService.isAccessGranted(context)) {
            return ToolExecutionResult(accessRequired(), false)
        }
        val args = JSONObject(argsJson)
        val query = args.optString("query", "").trim()
        val packageFilter = args.optString("package_name", "").trim()
        val maxAgeHours = NotificationHistoryPolicy.clampMaxAgeHours(
            if (args.has("max_age_hours")) args.optInt("max_age_hours") else null,
        )
        val limit = NotificationHistoryPolicy.clampSearchLimit(if (args.has("limit")) args.optInt("limit") else null)
        val repository = NotificationHistoryRepository(context.applicationContext)
        val rows = withContext(Dispatchers.IO) { repository.search(query, packageFilter, maxAgeHours, limit) }
        val items = JSONArray().also { array -> rows.forEach(array::put) }
        return ToolExecutionResult(
            JSONObject()
                .put("ok", true)
                .put("scope", "stored notification history")
                .put("count", items.length())
                .put("limit", limit)
                .put("max_age_hours", maxAgeHours)
                .put("retention_days", NotificationHistoryPolicy.RETENTION_DAYS)
                .put("truncated", items.length() == limit)
                .put("items", items)
                .toString(2),
            true,
        )
    }
}

class NotificationRecentHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = NotificationTools.RECENT,
        description = "Read the notifications currently in the shade, with title and body. " +
            "Requires the device owner to grant Notification Access to Minis; without it the call " +
            "returns the settings action instead of an empty list. Raw content is not written into the saved conversation.",
        parameters = mapOf(
            "package_name" to AgentToolParam("string", "Optional exact package name filter"),
            "limit" to AgentToolParam("integer", "Max notifications, 1-20, default 10"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        NotificationTools.recent(argsJson, sessionId, context)
}

class NotificationSearchHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = NotificationTools.SEARCH,
        description = "Search the notifications Minis recorded after Notification Access was granted: " +
            "the last ${NotificationHistoryPolicy.RETENTION_DAYS} days, newest first. " +
            "Raw content is not written into the saved conversation.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword matched against title, body and sub-text"),
            "package_name" to AgentToolParam("string", "Optional exact package name filter"),
            "max_age_hours" to AgentToolParam("integer", "How far back to look, 1-168 hours, default 24"),
            "limit" to AgentToolParam("integer", "Max rows, 1-50, default 20"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        NotificationTools.search(argsJson, sessionId, context)
}
