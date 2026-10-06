package com.openminis.app.tools

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Read Android Usage Access data without bypassing package-visibility rules. */
/** One package's usage for part of the window. */
internal data class AppUsage(val packageName: String, val foregroundMs: Long, val lastUsedMs: Long)

/**
 * Daily rows summed per package, ranked by the whole window's foreground time. Ranking the raw daily
 * rows put an app used for an hour on one day above one used for half an hour on each of seven days,
 * and returned the second app as seven rows.
 */
internal fun aggregateUsage(rows: List<AppUsage>, limit: Int): List<AppUsage> =
    rows.filter { it.foregroundMs > 0 }
        .groupBy { it.packageName }
        .map { (pkg, group) -> AppUsage(pkg, group.sumOf { it.foregroundMs }, group.maxOf { it.lastUsedMs }) }
        .sortedWith(compareByDescending<AppUsage> { it.foregroundMs }.thenBy { it.packageName })
        .take(limit)

object AndroidUsageOps {
    suspend fun query(context: Context, days: Int, limit: Int): ToolExecutionResult = withContext(Dispatchers.IO) {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return@withContext ToolExecutionResult(
                JSONObject()
                    .put("error", "usage_access_required")
                    .put("message", "Grant Usage Access to Minis in Android Settings before querying app usage.")
                    .put("settings_action", Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .toString(2),
                false,
            )
        }
        try {
            val spanDays = days.coerceIn(1, 90)
            val now = System.currentTimeMillis()
            val rows = (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - spanDays * 86_400_000L, now)
                // null (e.g. user not yet unlocked) is "unknown", not "no usage"
                ?: return@withContext ToolExecutionResult(
                    JSONObject()
                        .put("error", "usage_unavailable")
                        .put("message", "Android returned no usage statistics (the device may still be locked).")
                        .toString(2),
                    false,
                )
            val usage = aggregateUsage(
                rows.map { AppUsage(it.packageName, it.totalTimeInForeground, it.lastTimeUsed) },
                limit.coerceIn(1, 100),
            )
            val packages = context.packageManager
            val apps = JSONArray()
            usage.forEach { stat ->
                val label = runCatching {
                    packages.getApplicationLabel(packages.getApplicationInfo(stat.packageName, 0)).toString()
                }.getOrDefault(stat.packageName)
                apps.put(
                    JSONObject()
                        .put("package_name", stat.packageName)
                        .put("label", label)
                        .put("foreground_ms", stat.foregroundMs)
                        .put("last_used_ms", stat.lastUsedMs),
                )
            }
            ToolExecutionResult(
                JSONObject().put("days", spanDays).put("apps", apps).put("count", apps.length()).toString(2),
                true,
            )
        } catch (t: Throwable) {
            ToolExecutionResult("Error: app_usage failed: ${t.message}", false)
        }
    }
}

class AndroidAppUsageHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.app.usage",
        description = "List apps by foreground usage. Requires the user-granted Android Usage Access special permission.",
        parameters = mapOf(
            "days" to AgentToolParam("integer", "History window in days (default 7, max 90)"),
            "limit" to AgentToolParam("integer", "Max apps (default 20, max 100)"),
        ),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidUsageOps.query(context, a.optInt("days", 7), a.optInt("limit", 20))
    }
}
