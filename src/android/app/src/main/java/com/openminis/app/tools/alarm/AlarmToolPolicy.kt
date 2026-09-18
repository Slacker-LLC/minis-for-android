package com.openminis.app.tools.alarm

/**
 * [T-eta-alarm-tools] Validation and argv building for the alarm and timer tools.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (`set_alarm`, `set_timer`) and
 * the alarm table in `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Eta dispatches the system Clock intents itself;
 * this app already owns that dispatch in the `android-alarm` offload handler (single source:
 * the system Clock app, T266), so these rules only turn tool arguments into that CLI's
 * argv — and refuse what it cannot honour instead of quietly changing the request.
 *
 * The 24-hour bound on timers is Eta's (its tool description says "up to 24 hours").
 */
object AlarmToolPolicy {

    const val MAX_LABEL_CHARS = 100
    const val MAX_TIMER_SECONDS = 24 * 60 * 60

    enum class Repeat(val wire: String) {
        ONCE("ONCE"),
        DAILY("DAILY"),
        WEEKDAYS("WEEKDAYS"),
    }

    sealed class Decision {
        data class Ok(val argv: List<String>) : Decision()
        data class Refused(val reason: String) : Decision()
    }

    /** Weekday spellings Eta accepts, mapped to the only sets the system Clock takes. */
    private val ALL_DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri")

    fun alarmArgv(
        hour: Int?,
        minute: Int?,
        label: String?,
        repeatDays: List<String>?,
    ): Decision {
        if (hour == null || hour !in 0..23) return Decision.Refused("hour must be an integer 0-23")
        if (minute == null || minute !in 0..59) return Decision.Refused("minute must be an integer 0-59")
        val repeat = when (val mode = repeatMode(repeatDays)) {
            is RepeatDecision.Refused -> return Decision.Refused(mode.reason)
            is RepeatDecision.Ok -> mode.repeat
        }
        val time = "%02d:%02d".format(hour, minute)
        val argv = mutableListOf("set", "--time", time, "--repeat", repeat.wire)
        boundedLabel(label)?.let { argv += listOf("--label", it) }
        return Decision.Ok(argv)
    }

    fun timerArgv(durationSeconds: Int?, label: String?): Decision {
        if (durationSeconds == null || durationSeconds <= 0) {
            return Decision.Refused("duration_seconds must be a positive integer")
        }
        if (durationSeconds > MAX_TIMER_SECONDS) {
            return Decision.Refused("duration_seconds must be at most $MAX_TIMER_SECONDS (24 hours)")
        }
        val argv = mutableListOf("timer", "--duration", durationSeconds.toString())
        boundedLabel(label)?.let { argv += listOf("--label", it) }
        return Decision.Ok(argv)
    }

    /** Morning/afternoon words Eta's callers use; anything else must be a 24-hour value. */
    fun openArgv(): List<String> = listOf("open")

    fun boundedLabel(label: String?): String? =
        label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL_CHARS)

    private sealed class RepeatDecision {
        data class Ok(val repeat: Repeat) : RepeatDecision()
        data class Refused(val reason: String) : RepeatDecision()
    }

    /**
     * An absent or empty list is a one-shot alarm. A full week is DAILY and Monday-Friday is
     * WEEKDAYS, because that is all `AlarmClock.EXTRA_DAYS` is asked for here; any other set is
     * refused with the supported spellings rather than silently scheduled on the wrong days.
     */
    private fun repeatMode(days: List<String>?): RepeatDecision {
        val normalized = days.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return RepeatDecision.Ok(Repeat.ONCE)
        if (normalized.any { it !in ALL_DAYS }) {
            return RepeatDecision.Refused(
                "repeat_days must use ${ALL_DAYS.joinToString(", ")}",
            )
        }
        val distinct = normalized.distinct()
        return when {
            distinct.size == ALL_DAYS.size -> RepeatDecision.Ok(Repeat.DAILY)
            distinct.size == WEEKDAYS.size && distinct.all { it in WEEKDAYS } ->
                RepeatDecision.Ok(Repeat.WEEKDAYS)
            else -> RepeatDecision.Refused(
                "this app can schedule once, daily or weekdays; for a custom day set ask the user " +
                    "to set it in the Clock app",
            )
        }
    }
}
