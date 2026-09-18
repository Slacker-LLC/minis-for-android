package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.guest.AlarmOffloadHandler
import com.openminis.app.tools.alarm.AlarmToolPolicy
import com.openminis.app.tools.runtime.ToolHandler
import org.json.JSONObject

/**
 * [T-eta-alarm-tools] Alarms and timers as agent tools: Eta's `set_alarm` / `set_timer`,
 * adapted to this app's single-source design.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` and the alarm table in
 * `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The dispatch itself stays where it already lives — the
 * `android-alarm` offload handler, which schedules through the system Clock app and keeps no
 * private bookkeeping (T266) — so these tools add validation, an argv, and a documented
 * surface instead of a second scheduling path.
 *
 * The listing half (Eta's `list_alarms` / `list_active_timers`) reads the clock app's own
 * database through the snapshot in [ClockDatabaseTools]: Android's Clock API is fire-and-forget, so
 * what is set right now exists only inside that app. `android.alarm.open` stays for everything the
 * database cannot answer honestly - editing, pausing and cancelling belong to the user.
 */
object AlarmTools {
    const val SET = "android.alarm.set"
    const val TIMER = "android.alarm.timer"
    const val OPEN = "android.alarm.open"
    const val LIST_ALARMS = "android.alarm.list"
    const val LIST_TIMERS = "android.alarm.timers"

    val aliases: Map<String, List<String>> = mapOf(
        SET to listOf("set_alarm", "create_alarm"),
        TIMER to listOf("set_timer", "create_timer"),
        OPEN to listOf("show_alarms", "open_alarm_ui"),
        LIST_ALARMS to listOf("list_alarms"),
        LIST_TIMERS to listOf("list_active_timers"),
    )

    fun handlers(): List<ToolHandler> = listOf(
        AlarmSetHandler(),
        AlarmTimerHandler(),
        AlarmOpenHandler(),
        AlarmListHandler(),
        AlarmTimersHandler(),
    )

    internal suspend fun dispatch(
        argv: List<String>,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult = AndroidSystemOps.offload(
        context = context,
        sessionId = sessionId,
        handler = AlarmOffloadHandler(context),
        argv = listOf("android-alarm") + argv,
    )

    internal suspend fun set(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val hour = if (args.has("hour")) args.optInt("hour") else null
        val minute = if (args.has("minute")) args.optInt("minute") else null
        val days = args.optJSONArray("repeat_days")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { day -> day.isNotBlank() } }
        }
        return when (
            val decision = AlarmToolPolicy.alarmArgv(
                hour = hour,
                minute = minute,
                label = args.optString("label").takeIf { it.isNotBlank() },
                repeatDays = days,
            )
        ) {
            is AlarmToolPolicy.Decision.Refused ->
                ToolExecutionResult("Error: INVALID_ARGS: ${decision.reason}", false)
            is AlarmToolPolicy.Decision.Ok -> dispatch(decision.argv, sessionId, context)
        }
    }

    internal suspend fun timer(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val seconds = if (args.has("duration_seconds")) args.optInt("duration_seconds") else null
        return when (
            val decision = AlarmToolPolicy.timerArgv(
                durationSeconds = seconds,
                label = args.optString("label").takeIf { it.isNotBlank() },
            )
        ) {
            is AlarmToolPolicy.Decision.Refused ->
                ToolExecutionResult("Error: INVALID_ARGS: ${decision.reason}", false)
            is AlarmToolPolicy.Decision.Ok -> dispatch(decision.argv, sessionId, context)
        }
    }

    internal suspend fun open(sessionId: String, context: Context): ToolExecutionResult =
        dispatch(AlarmToolPolicy.openArgv(), sessionId, context)
}

class AlarmSetHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = AlarmTools.SET,
        description = "Create a system alarm through the device Clock app (no GUI automation). " +
            "hour/minute are local time; repeat_days takes mon..sun and may be omitted for a one-shot " +
            "alarm. Convert relative dates with android.time first. Some OEM Clock apps still show a " +
            "confirmation instead of creating the alarm silently; the result says which happened.",
        parameters = mapOf(
            "hour" to AgentToolParam("integer", "Alarm hour, 0-23, device local time"),
            "minute" to AgentToolParam("integer", "Alarm minute, 0-59"),
            "label" to AgentToolParam("string", "Optional label, at most 100 characters"),
            "repeat_days" to AgentToolParam(
                "array",
                "Optional repeat days; omit for once. All seven means daily, mon-fri means weekdays; any other set must be created in the Clock app",
                items = AgentToolParam("string", "Weekday: mon, tue, wed, thu, fri, sat or sun"),
            ),
        ),
        required = listOf("hour", "minute"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AlarmTools.set(argsJson, sessionId, context)
}

class AlarmTimerHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = AlarmTools.TIMER,
        description = "Start a system countdown timer through the device Clock app, up to 24 hours.",
        parameters = mapOf(
            "duration_seconds" to AgentToolParam("integer", "Timer length in seconds, 1 to 86400"),
            "label" to AgentToolParam("string", "Optional label, at most 100 characters"),
        ),
        required = listOf("duration_seconds"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AlarmTools.timer(argsJson, sessionId, context)
}

class AlarmOpenHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = AlarmTools.OPEN,
        description = "Open the system Clock app so the user can edit, pause or cancel alarms and " +
            "timers. The list tools report what is set; changing or cancelling it belongs to the " +
            "Clock app, and this is how the user gets there.",
        parameters = emptyMap(),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AlarmTools.open(sessionId, context)
}

class AlarmListHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = AlarmTools.LIST_ALARMS,
        description = "List the alarms the device Clock app actually holds, read from its own " +
            "database. Requires an authorized privileged path; a build whose clock database has a " +
            "different schema is reported as unsupported instead of guessed.",
        parameters = mapOf(
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default ${PrivateDatabaseRules.DEFAULT_LIMIT}, " +
                    "max ${PrivateDatabaseRules.MAX_LIMIT})",
            ),
            "enabled_only" to AgentToolParam("boolean", "Only enabled alarms (default true)"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        ClockDatabaseTools.listAlarms(context, sessionId, argsJson)
}

class AlarmTimersHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = AlarmTools.LIST_TIMERS,
        description = "List the countdown timers the device Clock app still holds (running or " +
            "paused), read from its own database. Requires an authorized privileged path.",
        parameters = mapOf(
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default ${PrivateDatabaseRules.DEFAULT_LIMIT}, " +
                    "max ${PrivateDatabaseRules.MAX_LIMIT})",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        ClockDatabaseTools.listTimers(context, sessionId, argsJson)
}
