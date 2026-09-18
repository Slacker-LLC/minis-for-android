package com.openminis.app.tools.context

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [T-eta-device-context] The time half of Eta's `get_current_context`, computed from a
 * clock and a zone instead of from the app's globals so it can be tested.
 *
 * Ported from Eta `agent/tool/DeviceContextTool.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta returns datetime, timezone and a localized weekday — the fields
 * a model needs before turning "tomorrow at 7" into a clock time. This app adds the UTC offset
 * and DST flag because it runs in any locale and every downstream alarm API wants local time
 * with an explicit offset.
 */
object DeviceContextPolicy {

    fun timeEnvironment(
        nowMillis: Long,
        zone: TimeZone,
        locale: Locale = Locale.getDefault(),
    ): JSONObject {
        val instant = Date(nowMillis)
        val iso = formatter("yyyy-MM-dd'T'HH:mm:ssXXX", zone, Locale.US).format(instant)
        val weekday = formatter("EEEE", zone, locale).format(instant)
        val weekdayIso = formatter("EEE", zone, Locale.US).format(instant)
        return JSONObject()
            .put("datetime", iso)
            .put("timezone", zone.id)
            .put("weekday", weekday)
            .put("weekday_short", weekdayIso)
            .put("locale", locale.toLanguageTag())
            .put("utc_offset_minutes", zone.getOffset(nowMillis) / 60_000)
            .put("dst", zone.inDaylightTime(instant))
    }

    /** Local-clock reading of the same instant, for prompts that must not do date math. */
    fun localClock(nowMillis: Long, zone: TimeZone): String =
        formatter("HH:mm", zone, Locale.US).format(Date(nowMillis))

    private fun formatter(pattern: String, zone: TimeZone, locale: Locale): SimpleDateFormat =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }
}
