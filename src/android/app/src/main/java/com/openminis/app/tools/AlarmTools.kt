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
 * Deliberate deviation from Eta: there is no `list_alarms` / `list_active_timers` tool here.
 * Android's Clock API is fire-and-forget, this app stopped keeping its own alarm records on
 * purpose, and a "list" that opened the Clock app would be a lie; the model gets
 * `android.alarm.open` instead, whose result says exactly what it did.
 */
object AlarmTools {
    const val SET = "android.alarm.set"
    const val TIMER = "android.alarm.timer"
    const val OPEN = "android.alarm.open"

    val aliases: Map<String, List<String>> = mapOf(
        SET to listOf("set_alarm", "create_alarm"),
        TIMER to listOf("set_timer", "create_timer"),
        OPEN to listOf("show_alarms", "open_alarm_ui"),
    )

    fun handlers(): List<ToolHandler> = listOf(AlarmSetHandler(), AlarmTimerHandler(), AlarmOpenHandler())

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
        description = "Open the system Clock app so the user can view, edit, pause or cancel alarms and " +
            "timers. Android's Clock API cannot enumerate or cancel them from an app, so this is the " +
            "only honest way to hand those operations to the user.",
        parameters = emptyMap(),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AlarmTools.open(sessionId, context)
}
