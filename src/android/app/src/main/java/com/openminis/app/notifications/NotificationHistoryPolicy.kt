package com.openminis.app.notifications

/**
 * [T-eta-notification-history] Bounds and query rules for the notification history the
 * device owner may let the assistant read.
 *
 * Ported from Eta `data/repository/NotificationHistoryRepository.kt` and the two device
 * tools that read it (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md.
 * The caps are Eta's: seven days of retention, at most one thousand records, four thousand
 * characters per field, and the tool-facing limits (shade 1-20 default 10, history 1-50
 * default 20 over 1-168 hours default 24).
 */
object NotificationHistoryPolicy {

    const val RETENTION_DAYS = 7
    const val MAX_RECORDS = 1_000
    const val MAX_FIELD_CHARS = 4_000
    const val MAX_KEY_CHARS = 1_000
    const val MAX_PACKAGE_CHARS = 255
    const val HOUR_MS = 60L * 60L * 1_000L
    const val RETENTION_MS = RETENTION_DAYS * 24L * HOUR_MS

    const val RECENT_DEFAULT_LIMIT = 10
    const val RECENT_MAX_LIMIT = 20
    const val SEARCH_DEFAULT_LIMIT = 20
    const val SEARCH_MAX_LIMIT = 50
    const val SEARCH_DEFAULT_MAX_AGE_HOURS = 24
    const val SEARCH_MIN_MAX_AGE_HOURS = 1
    const val SEARCH_MAX_MAX_AGE_HOURS = 168

    fun clampRecentLimit(raw: Int?): Int = (raw ?: RECENT_DEFAULT_LIMIT).coerceIn(1, RECENT_MAX_LIMIT)

    fun clampSearchLimit(raw: Int?): Int = (raw ?: SEARCH_DEFAULT_LIMIT).coerceIn(1, SEARCH_MAX_LIMIT)

    fun clampMaxAgeHours(raw: Int?): Int = (raw ?: SEARCH_DEFAULT_MAX_AGE_HOURS)
        .coerceIn(SEARCH_MIN_MAX_AGE_HOURS, SEARCH_MAX_MAX_AGE_HOURS)

    /** A notification with no title, text or sub-text carries nothing worth storing. */
    fun isRecordable(title: String?, text: String?, subText: String?): Boolean =
        !title.isNullOrBlank() || !text.isNullOrBlank() || !subText.isNullOrBlank()

    fun bounded(value: String?, maxChars: Int = MAX_FIELD_CHARS): String? =
        value?.take(maxChars)

    /**
     * Escapes the LIKE wildcards so a query is matched literally: a user searching for
     * "50%" must not match every notification with "50" in it.
     */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
