package com.openminis.app.xposed

private const val UNKNOWN_LOG_TOKEN = "unknown"

/**
 * [T-eta-xposed-entry] Log-safe renderings of things that must never reach a log line as they are.
 *
 * Ported from Eta `core/LogSafety.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. A module that writes into other people's processes for a living has to
 * keep exception messages - which carry file paths, account names, model output - out of logcat,
 * and has to keep low-cardinality tokens low-cardinality: a token built from user content turns one
 * log line per event into one per keystroke.
 */

/** The exception's type only; [Throwable.message] never reaches the log. */
fun Throwable.safeLogType(): String =
    javaClass.simpleName.takeIf { it.isNotBlank() } ?: Throwable::class.java.simpleName

/**
 * Constrains an externally produced identifier to a single-line, low-cardinality log token;
 * anything that is not stable ASCII of a sane length becomes `unknown`.
 */
fun String?.toSafeLogToken(maxLength: Int = 64): String {
    require(maxLength > 0) { "maxLength must be positive" }
    val value = this ?: return UNKNOWN_LOG_TOKEN
    if (value.isEmpty() || value.length > maxLength) return UNKNOWN_LOG_TOKEN
    return value.takeIf { token ->
        token.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    } ?: UNKNOWN_LOG_TOKEN
}
