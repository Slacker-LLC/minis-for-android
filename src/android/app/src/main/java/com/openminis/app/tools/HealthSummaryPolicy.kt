package com.openminis.app.tools

/**
 * [T-eta-xposed-groups] The health summary's window and its units.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. The window is the only thing a caller may choose, and the weight the
 * database holds is in grams while the answer is in kilograms - both are decisions worth writing
 * down once instead of leaving them inside a query.
 */
object HealthSummaryPolicy {
    const val DEFAULT_DAYS = 7
    const val MAX_DAYS = 30
    const val DAY_MS = 24L * 60 * 60 * 1_000

    fun clampDays(requested: Int?): Int = (requested ?: DEFAULT_DAYS).coerceIn(1, MAX_DAYS)

    fun cutoffFor(nowMs: Long, days: Int): Long = nowMs - days * DAY_MS

    /** Health Connect stores weight in grams; the answer is kilograms. */
    fun weightKgFromRecord(grams: Double): Double = grams / 1_000.0
}
